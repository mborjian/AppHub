package com.mimskydo.apphub

import android.content.ActivityNotFoundException
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * What one hand-off did.
 *
 * Four answers rather than a boolean, because "the platform found something to
 * open it with", "this unit has nothing that claims that kind of file at all",
 * "the file is not there any more" and "the platform would not let it out" are
 * four different pieces of news - and only the first one tells the driver to look
 * at whichever screen came up.
 */
enum class HandoffOp {

    /** something on the unit answered for this file, and that is what is opening */
    OPENED,

    /** nothing on the unit opens this kind of file - not a failure, an absence */
    UNCLAIMED,

    /** the file is gone: a card that has been pulled out, or a delete behind us */
    MISSING,

    /** the file is there and would not open, or the platform refused the hand-off */
    REFUSED,
}

/**
 * Handing one file to another app to open.
 *
 * This is the one verb in the file manager that has to cross a process boundary,
 * and Android crosses that boundary with a `content://` URI - never a path: not
 * since Android 7 refuses `file://` between apps, and not with this app's storage
 * access, which is real paths into the whole of the unit's storage.
 *
 * The obvious way out is a second `FileProvider` rooted at storage, and that is
 * the one way this app will not take. `update_paths.xml` is the standing rule -
 * a provider that can serve any file is a door - and a provider rooted at the
 * file manager's tree would be that door standing open at every level: every URI
 * it minted would be a read handle into whatever was under its root, including
 * the card that is still plugged in (see `Install.kt`, which reads an APK into an
 * installer session for exactly this reason).
 *
 * What this does instead is hand over **the one file the driver picked**. The URI
 * carries a token, minted a moment earlier for that one row and written down next
 * to nothing else; the file's own name rides along behind it, so the receiving
 * app can read a name and a type without asking anybody. There is no tree behind
 * this and no way to ask for a path by name: a token this app did not just mint
 * means nothing, and what a token means is one file, for as long as a hand-out is
 * still worth holding ([KEEP] and [LIFE]).
 *
 * Nothing is copied. A film on a stick is opened from the stick - the app playing
 * it reads it through the descriptor [HandoffProvider] opens - so nothing is
 * duplicated into this app's cache on a unit with storage to spare for neither.
 *
 * The provider is not exported, and the platform's own check does the rest: what
 * arrives on the other side is a read-only descriptor for that one file, for as
 * long as that app is holding the intent.
 */
object Handoff {

    /** the tag `adb logcat -s AppHub` filters by */
    private const val TAG = "AppHub"

    /** the authorities are off the package id, as they have to be; this is the one hand-off suffix */
    const val SUFFIX = ".handoff"

    /** where hand-outs are written down, in a file of their own rather than in the settings */
    private const val PREFS = "handoff"

    /** what a URI falls back to when the file has no name of its own */
    private const val FILE = "file"

    /** how many hand-outs are held at once, newest kept */
    private const val KEEP = 8

    /**
     * How long a hand-out is held for.
     *
     * A day: long enough for the app that was given the URI to still be reading a
     * film after the driver has been in and out of this screen, and short enough
     * that this is never a growing drawer of read handles into files that were
     * opened once last month.
     */
    private const val LIFE = 24L * 60L * 60L * 1000L

    /**
     * Hand [file] to whichever app on the unit opens that kind of thing.
     *
     * No chooser is raised here on purpose: the platform's own resolver *is* the
     * "open with" screen, and it is the one that offers "just once" against
     * "always" - which is a choice this app has no business making on the driver's
     * behalf.
     */
    fun open(context: Context, file: File): HandoffOp {
        if (!file.exists()) return HandoffOp.MISSING
        if (file.isDirectory || !file.canRead()) return HandoffOp.REFUSED

        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri(context, file), type(file.name))
            // the grant is the whole of the other app's access: one URI, read
            // only, for as long as it is holding this intent
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        return try {
            context.startActivity(intent)
            Log.i(TAG, "handoff ${file.absolutePath} OPENED")
            HandoffOp.OPENED
        } catch (t: ActivityNotFoundException) {
            Log.i(TAG, "handoff ${file.absolutePath} UNCLAIMED")
            HandoffOp.UNCLAIMED
        } catch (t: Throwable) {
            Log.i(TAG, "handoff ${file.absolutePath} REFUSED: $t")
            HandoffOp.REFUSED
        }
    }

    /**
     * The URI for [file], and the token that makes it mean something.
     *
     * The name goes in the path so that a receiving app can read a name and a type
     * out of the URI alone; the token in front of it is the only part anything has
     * to look up.
     */
    fun uri(context: Context, file: File): Uri = Uri.Builder()
        .scheme(ContentResolver.SCHEME_CONTENT)
        .authority(context.packageName + SUFFIX)
        .appendPath(mint(context, file))
        .appendPath(file.name ?: FILE)
        .build()

    /** the file behind a token, or null when this app is not holding that hand-out */
    fun lookUp(context: Context, uri: Uri): File? {
        val token = uri.pathSegments.firstOrNull() ?: return null
        val held = prefs(context).getString(token, null) ?: return null
        val path = held.substringAfter('\n', "")
        return if (path.isEmpty()) null else File(path)
    }

    /** the name the URI carries: the one [uri] put there */
    fun nameIn(uri: Uri): String = uri.lastPathSegment ?: FILE

    /**
     * Write the hand-out down and hand back its token.
     *
     * Written down rather than kept in memory, because the app that was given the
     * URI can come back for it after this app's process has been taken away: the
     * platform wakes the provider up again to answer, and it has to be able to. A
     * `SharedPreferences` is in memory as well as on disk, so this process does not
     * even read the write back off the disk.
     *
     * What is let go of on every mint: everything past [KEEP] and everything older
     * than [LIFE]. A token is a read handle on one file, and a handle nobody can
     * still be holding is worth less than the room its name takes.
     */
    private fun mint(context: Context, file: File): String {
        val token = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val prefs = prefs(context)

        val held = prefs.all.entries
            .mapNotNull { (key, value) -> (value as? String)?.let { key to it } }
            .filter { (_, value) -> alive(value, now) }
            .sortedByDescending { (_, value) -> stamp(value) }

        val editor = prefs.edit().putString(token, "$now\n${file.absolutePath}")
        held.drop(KEEP - 1).forEach { (key, _) -> editor.remove(key) }
        editor.apply()
        return token
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** when a hand-out was written down, in the front half of its record */
    private fun stamp(held: String) = held.substringBefore('\n').toLongOrNull() ?: 0L

    private fun alive(held: String, now: Long) = now - stamp(held) < LIFE

    /**
     * The type the receiving app is told, from the name alone.
     *
     * [Files.kindOf] draws a kind and this names a type, and they are deliberately
     * not the same list: the icons are the kinds a driver recognises on a row, and
     * this is what opens a door - a page, a document and a playlist all sit in the
     * "other" row on screen and each one has an answer here. Where the two do
     * overlap they are the same extensions, and the family fallbacks below cover
     * the kinds that have a family type of their own - an image is an `image`
     * type to any viewer - so the two lists do not
     * have to be kept in step by hand.
     */
    fun type(name: String?): String = when (extension(name)) {
        "apk" -> "application/vnd.android.package-archive"

        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "heic" -> "image/heic"
        "heif" -> "image/heif"

        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "mid" -> "audio/midi"
        "m3u", "m3u8" -> "audio/x-mpegurl"

        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "3gp" -> "video/3gpp"
        "webm" -> "video/webm"
        "wmv" -> "video/x-ms-wmv"
        "ts" -> "video/mp2t"

        // the container the file manager draws as an archive: what is inside is
        // for the app that opens it to find out
        "zip", "apks", "apkm", "xapk" -> "application/zip"
        "rar" -> "application/vnd.rar"
        "7z" -> "application/x-7z-compressed"
        "tar" -> "application/x-tar"
        "gz" -> "application/gzip"
        "xz" -> "application/x-xz"
        "bz2" -> "application/x-bzip2"
        "iso" -> "application/x-iso9660-image"

        "txt", "log", "md" -> "text/plain"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "xml" -> "text/xml"
        "html", "htm" -> "text/html"
        "pdf" -> "application/pdf"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"

        // nothing to go on: the kind the icons would give it, and `octet-stream`
        // for the rest - which is the honest answer, and one no app has to claim
        else -> family(Files.kindOf(File(name ?: FILE)))
    }

    /** the type a whole kind of file shares, for the kinds that have one */
    private fun family(kind: FileKind): String = when (kind) {
        FileKind.IMAGE -> "image/*"
        FileKind.MUSIC -> "audio/*"
        FileKind.VIDEO -> "video/*"
        else -> "application/octet-stream"
    }

    private fun extension(name: String?): String =
        name?.substringAfterLast('.', "")?.lowercase(Locale.US) ?: ""
}

/**
 * The other side of a hand-off: one file per token, read-only.
 *
 * What this provider cannot do is the whole reason it exists instead of a
 * `FileProvider` over the file manager's tree: it has no paths. A URI here is a
 * token this app minted for one file the driver picked to open, and the file
 * behind it is looked up from that record. There is no root to resolve a name
 * against, nothing to enumerate and no way to walk anywhere. An app holding one
 * of these URIs holds a read handle on one file for as long as this app is still
 * holding the hand-out, and can ask for nothing else.
 *
 * Not exported, exactly like the update provider: nothing can open one of these
 * without a URI that arrived on an intent with a read grant on it.
 */
class HandoffProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = Handoff.type(Handoff.nameIn(uri))

    /**
     * The two columns anything reading a file off the platform asks for: what it
     * is called, and how long it is. Answered from the name in the URI and the
     * file on the disk - the rest of what a `FileProvider` puts in a cursor is
     * about a tree this one does not have.
     */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val file = fileFor(uri)
        val cursor = MatrixCursor(columns, 1)
        val row = cursor.newRow()
        columns.forEach { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> row.add(Handoff.nameIn(uri))
                OpenableColumns.SIZE -> row.add(file?.length() ?: 0L)
                else -> row.add(null)
            }
        }
        return cursor
    }

    /**
     * Read-only, whatever mode was asked for: this door hands a file out and never
     * takes one in, so a `"w"` or an `"rw"` is answered with nothing at all.
     */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (!mode.startsWith("r")) return null
        val file = fileFor(uri) ?: return null
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (t: Throwable) {
            Log.i(TAG, "handoff could not open ${file.absolutePath}: $t")
            null
        }
    }

    /**
     * The same descriptor, with the file's own length filled in.
     *
     * `ContentProvider`'s own `openAssetFile` hands out an unknown length, and the
     * app on the other side of this is often a player drawing a seek bar for a
     * film - so the length is worth these three lines.
     */
    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        val file = fileFor(uri) ?: return null
        val fd = openFile(uri, mode) ?: return null
        return AssetFileDescriptor(fd, 0, file.length())
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("a hand-off hands a file out, it takes nothing in")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("a hand-off hands a file out, it takes nothing in")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("a hand-off hands a file out, it takes nothing in")

    private fun fileFor(uri: Uri): File? = context
        ?.let { Handoff.lookUp(it, uri) }
        ?.takeIf { it.exists() && !it.isDirectory }

    private companion object {
        /** the tag `adb logcat -s AppHub` filters by */
        private const val TAG = "AppHub"
    }
}

/**
 * What to tell the driver, from what the hand-off hit.
 *
 * "Handed" rather than "opened" in the one good case, and that is not a hedge:
 * the platform resolved the file to an app and that app is opening, but whether
 * the driver is looking at the file yet is that app's news, not this one's - the
 * same reason an install says "Installing" until the installer finishes.
 */
object HandoffReport {

    fun text(context: Context, op: HandoffOp, label: String): String = when (op) {
        HandoffOp.OPENED -> context.getString(R.string.files_handed_toast, label)
        HandoffOp.UNCLAIMED -> context.getString(R.string.files_unclaimed_toast, label)
        HandoffOp.MISSING -> context.getString(R.string.files_missing_toast, label)
        HandoffOp.REFUSED -> context.getString(R.string.files_open_refused_toast, label)
    }
}
