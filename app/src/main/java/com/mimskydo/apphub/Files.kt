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

data class Volume(
    val name: String,
    val root: File,
    val removable: Boolean,
    val primary: Boolean,
)

enum class FileKind(val glyph: Int) {

    UP(R.drawable.ic_up),
    FOLDER(R.drawable.ic_folder),
    APK(R.drawable.ic_apk),
    IMAGE(R.drawable.ic_image),
    MUSIC(R.drawable.ic_music),
    VIDEO(R.drawable.ic_video),
    ARCHIVE(R.drawable.ic_archive),

    OTHER(R.drawable.ic_file),
}

data class FileItem(
    val file: File,
    val name: String,
    val kind: FileKind,
    val size: Long = 0L,
    val modified: Long = 0L,
) {
    val folder: Boolean get() = kind == FileKind.FOLDER
}

enum class Access { ALLOWED, MISSING }

enum class FileOp {

    DONE,

    LEFT_BEHIND,

    EXISTS,

    REFUSED,
}

object Files {

    private const val TAG = "AppHub"

    val STORAGE_PERMISSIONS = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
    )

    fun access(context: Context): Access = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
            if (Environment.isExternalStorageManager()) Access.ALLOWED else Access.MISSING
        granted(context, STORAGE_PERMISSIONS) -> Access.ALLOWED
        else -> Access.MISSING
    }

    fun openAccessSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val own = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.fromParts("package", context.packageName, null),
        )
        if (ask(context, own)) return true
        return ask(context, Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    }

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

    private fun volumeRootOf(own: File): File? {
        val path = own.absolutePath
        val cut = path.indexOf("/Android/")
        if (cut <= 0) return null
        val root = File(path.substring(0, cut))
        return if (root.isDirectory) root else null
    }

    private fun finite(volumes: List<Volume>): List<Volume> {
        val seen = LinkedHashMap<String, Volume>(volumes.size * 2)
        for (volume in volumes) {
            seen[volume.root.absolutePath] = seen[volume.root.absolutePath] ?: volume
        }
        return seen.values.sortedWith(compareByDescending<Volume> { it.primary }.thenBy { it.root.absolutePath })
    }

    private fun plainName(context: Context, root: File, removable: Boolean): String = when {
        root.absolutePath == Environment.getExternalStorageDirectory().absolutePath ->
            context.getString(R.string.files_volume_internal)
        removable -> context.getString(R.string.files_volume_card)
        else -> context.getString(R.string.files_volume_storage)
    }

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

    fun up(dir: File, volume: Volume?): FileItem? {
        if (volume?.root?.absolutePath == dir.absolutePath) return null
        val parent = dir.parentFile ?: return null
        if (!parent.isDirectory) return null
        return FileItem(
            file = parent,
            name = if (parent.absolutePath == volume?.root?.absolutePath) volume.name else parent.name ?: "/",
            kind = FileKind.UP,
        )
    }

    fun volumeOf(dir: File, volumes: List<Volume>): Volume? = volumes
        .filter { dir.absolutePath == it.root.absolutePath || dir.absolutePath.startsWith(it.root.absolutePath + File.separator) }
        .maxByOrNull { it.root.absolutePath.length }

    fun pathOf(dir: File, volume: Volume?): String {
        val root = volume?.root ?: return dir.absolutePath
        val relative = dir.absolutePath.removePrefix(root.absolutePath).trim(File.separatorChar)
        return if (relative.isEmpty()) "/" else relative
    }

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

    fun kindOf(file: File): FileKind {
        if (file.isDirectory) return FileKind.FOLDER
        val name = file.name ?: return FileKind.OTHER
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return FileKind.OTHER
        return when (name.substring(dot + 1).lowercase(Locale.US)) {
            "apk" -> FileKind.APK
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif" -> FileKind.IMAGE
            "mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "mid" -> FileKind.MUSIC
            "mp4", "m4v", "mkv", "avi", "mov", "3gp", "webm", "wmv", "ts" -> FileKind.VIDEO
            "zip", "rar", "7z", "tar", "gz", "xz", "bz2", "iso", "apks", "apkm", "xapk" -> FileKind.ARCHIVE
            else -> FileKind.OTHER
        }
    }

    fun delete(file: File): FileOp = if (removeTree(file)) FileOp.DONE else FileOp.REFUSED

    fun copy(source: File, into: File): FileOp {
        val target = File(into, source.name ?: return FileOp.REFUSED)
        if (target.exists()) return FileOp.EXISTS
        if (!into.isDirectory || !into.canWrite()) return FileOp.REFUSED
        if (source.isDirectory && into.absolutePath.startsWith(source.absolutePath + File.separator)) {
            return FileOp.REFUSED
        }
        if (copyTree(source, target)) return FileOp.DONE
        removeTree(target)
        return FileOp.REFUSED
    }

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

    fun sizeText(bytes: Long): String = when {
        bytes < 0L -> ""
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
        bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    fun dateText(context: Context, file: File): String =
        DateFormat.getMediumDateFormat(context).format(Date(file.lastModified()))

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

enum class FileVerb { COPY, MOVE, DELETE }

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
