package com.mimskydo.apphub

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import android.text.format.DateFormat
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.text.Collator
import java.util.Date
import java.util.Locale

/**
 * One place files can live: the unit's own storage, or a card or stick plugged
 * into it.
 *
 * [root] rather than a URI: this is the path-based view of storage, and the one
 * the driver reads on the unit's own screen is a path.
 */
data class Volume(
    /** what the driver sees: "Internal storage", or the card's own printed name */
    val name: String,
    val root: File,
    val removable: Boolean,
    val primary: Boolean,
)

/**
 * What a row's icon is drawn from: a drawable resource, best effort - the name
 * of the file, not what is inside it.
 */
enum class FileKind(val glyph: Int) {

    /** the way back up, which is a place and not a file */
    UP(R.drawable.ic_up),
    FOLDER(R.drawable.ic_folder),
    APK(R.drawable.ic_apk),
    IMAGE(R.drawable.ic_image),
    MUSIC(R.drawable.ic_music),
    VIDEO(R.drawable.ic_video),
    ARCHIVE(R.drawable.ic_archive),

    /** one of everything else, drawn as one page rather than as nothing */
    OTHER(R.drawable.ic_file),
}

/** One row of a folder: a folder, a file, or the step back up to its parent. */
data class FileItem(
    val file: File,
    /**
     * The one line the row shows.
     *
     * A field rather than `file.name`, because the way back up is named after
     * the place it leads to - "Internal storage", not "emulated".
     */
    val name: String,
    val kind: FileKind,
    val size: Long = 0L,
    val modified: Long = 0L,
) {
    val folder: Boolean get() = kind == FileKind.FOLDER
}

/** Whether this install may read the unit's storage at all. */
enum class Access { ALLOWED, MISSING }

/**
 * What one delete, copy or move did.
 *
 * Three refusals and a partial, rather than a boolean, because "there is already
 * one of those there", "the unit would not let it happen" and "the copy landed
 * but the original is still there" are three different pieces of news.
 */
enum class FileOp {

    /** everything the tap asked for happened */
    DONE,

    /**
     * A move across volumes is a copy plus a delete, and only the first half
     * happened: there are now two of this file, and the one on the card the
     * driver was carrying it away from is still there.
     */
    LEFT_BEHIND,

    /** something of that name is already in the folder it was going to, so nothing was written */
    EXISTS,

    /** nothing changed */
    REFUSED,
}

/**
 * The files on the unit, read and written through real paths.
 *
 * **Why paths and not the Storage Access Framework.** A "pick a folder and I
 * will look after it" grant is the only access Android hands out without a
 * permission, and it is the wrong shape for this screen: a file manager that
 * cannot show a card until the driver has separately granted the card is a
 * file manager with a form in front of it. So this asks for the whole of
 * storage, on the three versions that spell that three different ways (see
 * [access]), and everything here is a `java.io.File`.
 *
 * **The one thing it does not do**: `Android/data` and `Android/obb`, which
 * from Android 11 on no app may read - not even with all files access. A folder
 * that refuses to be read says so on the screen instead of looking empty.
 *
 * Every operation here is a plain blocking file call: this object is used from
 * the file manager's own worker thread, never from the UI thread.
 */
object Files {

    /** the tag `adb logcat -s AppHub` filters by */
    private const val TAG = "AppHub"

    /**
     * What Android 10 and below ask for the whole of storage: both of them, at
     * runtime, and the write is what makes the mount a writing one - see the
     * legacy flag on the application in the manifest.
     */
    val STORAGE_PERMISSIONS = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
    )

    /**
     * Whether the files can be read at all, on this version of Android.
     *
     * From Android 11 on there is one permission for the whole of storage and it
     * is granted in a system screen of its own - `All files access` - so it is
     * checked as a state rather than requested as a runtime permission. Below
     * that, the plain storage pair is the mechanism, and on the unit's Android 10
     * it is the one that gives real paths back.
     */
    fun access(context: Context): Access = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            if (Environment.isExternalStorageManager()) Access.ALLOWED else Access.MISSING
        granted(context, STORAGE_PERMISSIONS) -> Access.ALLOWED
        else -> Access.MISSING
    }

    /**
     * The one screen that can grant it on Android 11 and up, opened to this
     * app's own row rather than to the list of every app that asks.
     *
     * False below Android 11, where there is no such screen: there the permission
     * is requested in place, like any other runtime permission.
     */
    fun openAccessSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val own = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", context.packageName, null),
        )
        if (ask(context, own)) return true
        // some builds ship the list of apps without the per-app page
        return ask(context, Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    }

    /**
     * Every volume the driver can browse, primary first, the cards and sticks
     * after it.
     *
     * Read two ways, because the good one is newer than the unit: from Android 11
     * the platform's own `StorageManager` knows each volume's printed name
     * ("SanDisk SD card") and whether it is the emulated one; below that the list
     * comes from the directories this app was handed for each volume - one per
     * attached volume - with the app's own corner of each cut off to leave the
     * volume's root.
     */
    fun volumes(context: Context): List<Volume> = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) namedVolumes(context)
        else appFolderVolumes(context)
    } catch (t: Throwable) {
        Log.i(TAG, "volume list failed: $t")
        emptyList()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun namedVolumes(context: Context): List<Volume> = finite(
        context.getSystemService(StorageManager::class.java)
            ?.storageVolumes
            .orEmpty()
            .mapNotNull { volume ->
                val root = volume.directory ?: return@mapNotNull null
                if (!root.isDirectory) return@mapNotNull null
                Volume(
                    name = volume.getDescription(context)
                        ?.takeIf { it.isNotBlank() }
                        ?: plainName(context, root, volume.isRemovable),
                    root = root,
                    removable = volume.isRemovable,
                    primary = volume.isPrimary,
                )
            }
    )

    private fun appFolderVolumes(context: Context): List<Volume> = finite(
        context.getExternalFilesDirs(null)
            .orEmpty()
            .filterNotNull()
            .mapNotNull { own ->
                val root = volumeRootOf(own) ?: return@mapNotNull null
                val removable = Environment.isExternalStorageRemovable(root)
                Volume(
                    name = plainName(context, root, removable),
                    root = root,
                    removable = removable,
                    primary = root.absolutePath == Environment.getExternalStorageDirectory().absolutePath,
                )
            }
    )

    /**
     * `/storage/1234-ABCD/Android/data/<pkg>/files` -> `/storage/1234-ABCD`.
     *
     * The app's own directory on a volume is the only part of that volume the
     * platform tells this app about, and what it says first is the volume: what
     * is in front of `/Android/` is the root of the card.
     */
    private fun volumeRootOf(own: File): File? {
        val path = own.absolutePath
        val cut = path.indexOf("/Android/")
        if (cut <= 0) return null
        val root = File(path.substring(0, cut))
        return if (root.isDirectory) root else null
    }

    /** the same path once, on the versions that can list a volume twice */
    private fun finite(volumes: List<Volume>): List<Volume> {
        val seen = LinkedHashMap<String, Volume>(volumes.size * 2)
        for (volume in volumes) {
            seen[volume.root.absolutePath] = seen[volume.root.absolutePath] ?: volume
        }
        return seen.values.sortedWith(compareByDescending<Volume> { it.primary }.thenBy { it.root.absolutePath })
    }

    /** what a volume is called when the platform has no printed name for it */
    private fun plainName(context: Context, root: File, removable: Boolean): String = when {
        root.absolutePath == Environment.getExternalStorageDirectory().absolutePath ->
            context.getString(R.string.files_volume_internal)
        removable -> context.getString(R.string.files_volume_card)
        else -> context.getString(R.string.files_volume_storage)
    }

    /**
     * What is in [dir], folders first, or null when this app is not allowed to
     * look.
     *
     * Null is not empty, and the screen says the difference: a folder the
     * platform keeps shut (`Android/data` on Android 11 and up) must not read as
     * a folder with nothing in it.
     */
    fun list(dir: File): List<FileItem>? {
        val children = try {
            dir.listFiles()
        } catch (t: Throwable) {
            null
        } ?: return null

        val collator = Collator.getInstance()
        return children
            .map { child ->
                FileItem(
                    file = child,
                    name = child.name ?: child.absolutePath,
                    kind = kindOf(child),
                    size = child.length(),
                    modified = child.lastModified(),
                )
            }
            .sortedWith(
                compareByDescending<FileItem> { it.folder }
                    .thenBy(collator) { it.name }
            )
    }

    /** the step back up to [dir]'s parent, named after the place it leads to */
    fun up(dir: File, volume: Volume?): FileItem? {
        // a volume's root is the top of what there is to see: above it lies the
        // part of the filesystem this app has no business drawing
        if (volume?.root?.absolutePath == dir.absolutePath) return null
        val parent = dir.parentFile ?: return null
        if (!parent.isDirectory) return null
        return FileItem(
            file = parent,
            name = if (parent.absolutePath == volume?.root?.absolutePath) volume.name else parent.name ?: "/",
            kind = FileKind.UP,
        )
    }

    /** the volume a folder belongs to, i.e. the longest root that is in front of it */
    fun volumeOf(dir: File, volumes: List<Volume>): Volume? = volumes
        .filter { dir.absolutePath == it.root.absolutePath || dir.absolutePath.startsWith(it.root.absolutePath + File.separator) }
        .maxByOrNull { it.root.absolutePath.length }

    /** the path of [dir] as the driver reads it: relative to the volume it is on */
    fun pathOf(dir: File, volume: Volume?): String {
        val root = volume?.root ?: return dir.absolutePath
        val relative = dir.absolutePath.removePrefix(root.absolutePath).trim(File.separatorChar)
        return if (relative.isEmpty()) "/" else relative
    }

    /** [dir] and every folder between it and its volume's root, the root first */
    fun chain(dir: File, volume: Volume?): List<File> {
        val root = volume?.root ?: return listOf(dir)
        val out = ArrayList<File>()
        var node: File? = dir
        while (node != null && node.absolutePath.startsWith(root.absolutePath)) {
            out += node
            if (node.absolutePath == root.absolutePath) break
            node = node.parentFile
        }
        return out.reversed()
    }

    /** what a row's icon is drawn from */
    fun kindOf(file: File): FileKind {
        if (file.isDirectory) return FileKind.FOLDER
        val name = file.name ?: return FileKind.OTHER
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return FileKind.OTHER
        return when (name.substring(dot + 1).lowercase(Locale.US)) {
            // only the one the platform installs by itself: an .apkm or an
            // .xapk is a bundle with the APKs inside it, which is an archive
            "apk" -> FileKind.APK
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif" -> FileKind.IMAGE
            "mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "mid" -> FileKind.MUSIC
            "mp4", "m4v", "mkv", "avi", "mov", "3gp", "webm", "wmv", "ts" -> FileKind.VIDEO
            "zip", "rar", "7z", "tar", "gz", "xz", "bz2", "iso", "apks", "apkm", "xapk" -> FileKind.ARCHIVE
            else -> FileKind.OTHER
        }
    }

    /**
     * Take [file] off the unit, folder and all.
     *
     * A folder means everything in it: there is no recycle bin on Android, which
     * is why the screen asks first and says so in the question.
     */
    fun delete(file: File): FileOp = if (removeTree(file)) FileOp.DONE else FileOp.REFUSED

    /** Copy [source] into the folder [into], keeping its name. */
    fun copy(source: File, into: File): FileOp {
        val target = File(into, source.name ?: return FileOp.REFUSED)
        if (target.exists()) return FileOp.EXISTS
        if (!into.isDirectory || !into.canWrite()) return FileOp.REFUSED
        // a folder cannot be copied into itself, however the driver got here
        if (source.isDirectory && into.absolutePath.startsWith(source.absolutePath + File.separator)) {
            return FileOp.REFUSED
        }
        if (copyTree(source, target)) return FileOp.DONE
        // half a file is worse than no file: what was written is taken back
        removeTree(target)
        return FileOp.REFUSED
    }

    /**
     * Move [source] into the folder [into].
     *
     * Within one volume that is a rename, and nothing is read or written. Across
     * volumes the platform will not rename, so it is a copy and then a delete -
     * and when the second half is refused the driver is told there are two of
     * it rather than that it moved.
     */
    fun move(source: File, into: File): FileOp {
        val target = File(into, source.name ?: return FileOp.REFUSED)
        if (target.exists()) return FileOp.EXISTS
        if (!into.isDirectory || !into.canWrite()) return FileOp.REFUSED
        if (source.isDirectory && into.absolutePath.startsWith(source.absolutePath + File.separator)) {
            return FileOp.REFUSED
        }
        if (source.renameTo(target)) return FileOp.DONE
        if (copy(source, into) != FileOp.DONE) return FileOp.REFUSED
        return if (delete(source) == FileOp.DONE) FileOp.DONE else FileOp.LEFT_BEHIND
    }

    /**
     * A size as one short line: "512 B", "2 KB", "8.4 MB", "1.2 GB".
     *
     * Latin digits and one decimal place, deliberately: this is the column a
     * driver compares file to file, and it must not be re-shaped by the language
     * the rest of the screen is in.
     */
    fun sizeText(bytes: Long): String = when {
        bytes < 0L -> ""
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
        bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    /** When [file] was last written, in the unit's own date format. */
    fun dateText(context: Context, file: File): String =
        DateFormat.getMediumDateFormat(context).format(Date(file.lastModified()))

    /** [source] and everything under it, written to [target] */
    private fun copyTree(source: File, target: File): Boolean {
        try {
            if (source.isDirectory) {
                if (!target.isDirectory && !target.mkdirs()) return false
                val children = source.listFiles() ?: return false
                for (child in children) {
                    if (!copyTree(child, File(target, child.name))) return false
                }
                return true
            }
            source.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return true
        } catch (t: Throwable) {
            Log.i(TAG, "copy ${source.absolutePath} refused: $t")
            return false
        }
    }

    /** [file] and everything under it */
    private fun removeTree(file: File): Boolean {
        if (!file.exists()) return true
        if (file.isDirectory) {
            val children = try {
                file.listFiles()
            } catch (t: Throwable) {
                null
            } ?: return false
            for (child in children) if (!removeTree(child)) return false
        }
        return try {
            file.delete()
        } catch (t: Throwable) {
            false
        }
    }

    private fun ask(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (t: Throwable) {
        false
    }

    private fun granted(context: Context, permissions: Array<String>): Boolean =
        permissions.all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
}

/** Which verb a [FileReport] is about: the same outcome reads differently per verb. */
enum class FileVerb { COPY, MOVE, DELETE }

/**
 * What to tell the driver, from what actually happened.
 *
 * Five endings and not two, because a file that was already there, a folder the
 * unit would not let anyone write to, and a move that copied but could not take
 * the original away are three different pieces of news - and only one of them
 * means the file is where the driver thinks it is.
 */
object FileReport {

    fun text(context: Context, verb: FileVerb, op: FileOp, name: String): String = when (op) {
        FileOp.DONE -> context.getString(
            when (verb) {
                FileVerb.COPY -> R.string.files_copied_toast
                FileVerb.MOVE -> R.string.files_moved_toast
                FileVerb.DELETE -> R.string.files_deleted_toast
            },
            name,
        )

        // a move's second half was refused: there are two of this file now, and
        // saying "moved" there would be the one lie this file manager can tell
        FileOp.LEFT_BEHIND -> context.getString(R.string.files_copied_twice_toast, name)

        FileOp.EXISTS -> context.getString(R.string.files_exists_toast, name)

        FileOp.REFUSED -> context.getString(
            when (verb) {
                FileVerb.COPY -> R.string.files_copy_refused_toast
                FileVerb.MOVE -> R.string.files_move_refused_toast
                FileVerb.DELETE -> R.string.files_delete_refused_toast
            },
            name,
        )
    }
}
