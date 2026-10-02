package com.mimskydo.apphub

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class LogLevel(val id: String, val label: Int) {
    VERBOSE("V", R.string.log_level_verbose),
    DEBUG("D", R.string.log_level_debug),
    INFO("I", R.string.log_level_info),
    WARN("W", R.string.log_level_warn),
    ERROR("E", R.string.log_level_error),
    ;

    companion object {
        fun of(stored: String?): LogLevel = values().firstOrNull { it.name == stored } ?: INFO
    }
}

object Logcat {

    private const val TAG = "AppHub"

    data class Dump(val text: String, val root: Boolean)

    fun read(level: LogLevel): Dump {
        val command = "logcat -d -v time -t $LINES '*:${level.id}'"
        val fromRoot = if (RootShell.isAvailable()) RootShell.command(command) else null
        val text = fromRoot ?: direct(level)
        Log.i(TAG, "logs read: lines=${text.lineSequence().count()} root=${fromRoot != null} level=${level.name}")
        return Dump(text, fromRoot != null)
    }

    fun save(context: Context, text: String): File? {
        val name = "apphub-log-${stamp()}.txt"
        val folders = ArrayList<File>(3)
        try {
            folders += Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        } catch (t: Throwable) {
            Log.i(TAG, "downloads folder unavailable: $t")
        }
        context.getExternalFilesDir(null)?.let { folders += it }
        folders += context.filesDir

        for (folder in folders) {
            try {
                folder.mkdirs()
                val file = File(folder, name)
                file.writeText(text)
                Log.i(TAG, "logs saved to ${file.absolutePath}")
                return file
            } catch (t: Throwable) {
                Log.i(TAG, "logs not saved in $folder: $t")
            }
        }
        return null
    }

    private fun direct(level: LogLevel): String = try {
        val process = ProcessBuilder(
            "logcat", "-d", "-v", "time", "-t", LINES.toString(), "*:${level.id}",
        ).redirectErrorStream(true).start()
        if (process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.inputStream.bufferedReader().use { it.readText() }
        } else {
            process.destroyForcibly()
            ""
        }
    } catch (t: Throwable) {
        Log.i(TAG, "logcat refused: $t")
        ""
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private const val LINES = 1200

    private const val TIMEOUT_SECONDS = 8L
}
