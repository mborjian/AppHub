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

enum class HandoffOp {

    OPENED,

    UNCLAIMED,

    MISSING,

    REFUSED,
}

object Handoff {

    private const val TAG = "AppHub"

    const val SUFFIX = ".handoff"

    private const val PREFS = "handoff"

    private const val FILE = "file"

    private const val KEEP = 8

    private const val LIFE = 24L * 60L * 60L * 1000L

    fun open(context: Context, file: File): HandoffOp {
        if (!file.exists()) return HandoffOp.MISSING
        if (file.isDirectory || !file.canRead()) return HandoffOp.REFUSED

        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri(context, file), type(file.name))
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

    fun uri(context: Context, file: File): Uri = Uri.Builder()
        .scheme(ContentResolver.SCHEME_CONTENT)
        .authority(context.packageName + SUFFIX)
        .appendPath(mint(context, file))
        .appendPath(file.name ?: FILE)
        .build()

    fun lookUp(context: Context, uri: Uri): File? {
        val token = uri.pathSegments.firstOrNull() ?: return null
        val held = prefs(context).getString(token, null) ?: return null
        val path = held.substringAfter('\n', "")
        return if (path.isEmpty()) null else File(path)
    }

    fun nameIn(uri: Uri): String = uri.lastPathSegment ?: FILE

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

    private fun stamp(held: String) = held.substringBefore('\n').toLongOrNull() ?: 0L

    private fun alive(held: String, now: Long) = now - stamp(held) < LIFE

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

        else -> family(Files.kindOf(File(name ?: FILE)))
    }

    private fun family(kind: FileKind): String = when (kind) {
        FileKind.IMAGE -> "image/*"
        FileKind.MUSIC -> "audio/*"
        FileKind.VIDEO -> "video/*"
        else -> "application/octet-stream"
    }

    private fun extension(name: String?): String =
        name?.substringAfterLast('.', "")?.lowercase(Locale.US) ?: ""
}

class HandoffProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = Handoff.type(Handoff.nameIn(uri))

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
        private const val TAG = "AppHub"
    }
}

object HandoffReport {

    fun text(context: Context, op: HandoffOp, label: String): String = when (op) {
        HandoffOp.OPENED -> context.getString(R.string.files_handed_toast, label)
        HandoffOp.UNCLAIMED -> context.getString(R.string.files_unclaimed_toast, label)
        HandoffOp.MISSING -> context.getString(R.string.files_missing_toast, label)
        HandoffOp.REFUSED -> context.getString(R.string.files_open_refused_toast, label)
    }
}
