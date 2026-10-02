package com.mimskydo.apphub

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import java.io.File

object ProcTable {

    private const val TAG = "AppHub"

    private const val PROC = "/proc"

    internal const val FOREGROUND_ADJ = 200

    class Live(
        val packageName: String,
        val foreground: Boolean,
        val bytes: Long?,
    )

    fun read(context: Context, packages: Collection<String>): List<Live> {
        val known = packages.toHashSet()
        if (known.isEmpty()) return emptyList()

        val entries = try {
            File(PROC).list()
        } catch (t: Throwable) {
            null
        } ?: return emptyList()

        val manager = context.packageManager
        val myUid = Process.myUid()
        val rows = HashMap<String, Row>()
        val byUid = HashMap<Int, List<String>>()
        var sawAnotherUid = false

        for (entry in entries) {
            val pid = entry.toIntOrNull() ?: continue
            val name = cmdline(pid) ?: continue
            val status = status(pid) ?: continue
            if (status.uid == myUid) continue
            sawAnotherUid = true

            val candidates = byUid.getOrPut(status.uid) { candidates(manager, status.uid, known) }
            val packageName = packageOf(name, candidates) ?: continue
            val row = rows.getOrPut(packageName) { Row() }
            row.foreground = row.foreground || isForeground(adj(pid))
            status.bytes?.let { row.bytes = (row.bytes ?: 0L) + it }
        }

        if (!sawAnotherUid) {
            Log.i(TAG, "the process table shows only this app's own processes; not claiming it")
            return emptyList()
        }
        return rows.map { Live(it.key, it.value.foreground, it.value.bytes) }
            .sortedBy { it.packageName }
    }

    internal fun packageOf(name: String, candidates: List<String>): String? {
        candidates.firstOrNull { name == it || name.startsWith("$it:") }?.let { return it }
        return candidates.filter { name.startsWith("$it.") }.maxByOrNull { it.length }
    }

    internal fun isForeground(adj: Int?): Boolean = adj != null && adj <= FOREGROUND_ADJ

    private class Row {
        var foreground = false
        var bytes: Long? = null
    }

    private fun candidates(
        manager: PackageManager,
        uid: Int,
        known: Set<String>,
    ): List<String> = try {
        manager.getPackagesForUid(uid)?.filter { it in known }.orEmpty()
    } catch (t: Throwable) {
        emptyList()
    }

    private fun cmdline(pid: Int): String? = try {
        val bytes = File("$PROC/$pid/cmdline").readBytes()
        val end = bytes.indexOf(0.toByte()).let { if (it < 0) bytes.size else it }
        if (end == 0) null else String(bytes, 0, end, Charsets.UTF_8)
    } catch (t: Throwable) {
        null
    }

    private class Status(val uid: Int, val bytes: Long?)

    private fun status(pid: Int): Status? = try {
        var uid = -1
        var bytes: Long? = null
        File("$PROC/$pid/status").forEachLine { line ->
            when {
                line.startsWith("Uid:") ->
                    uid = fields(line).getOrNull(1)?.toIntOrNull() ?: -1
                line.startsWith("VmRSS:") ->
                    bytes = fields(line).getOrNull(0)?.toLongOrNull()?.times(1024)
            }
        }
        if (uid < 0) null else Status(uid, bytes)
    } catch (t: Throwable) {
        null
    }

    private fun adj(pid: Int): Int? = try {
        File("$PROC/$pid/oom_score_adj").readText().trim().toIntOrNull()
    } catch (t: Throwable) {
        null
    }

    private fun fields(line: String): List<String> =
        line.substringAfter(':').trim().split(Regex("\\s+"))
}
