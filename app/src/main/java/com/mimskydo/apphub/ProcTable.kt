package com.mimskydo.apphub

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import java.io.File

/**
 * "Which apps have a process right now?", answered by reading `/proc` directly.
 *
 * Every framework door to this question is closed on the unit: the task list
 * needs `REAL_GET_TASKS` (`signature|privileged`), `getRunningAppProcesses()`
 * has returned only this app's own processes since API 22, and the install is a
 * plain APK - not root, not `/system/priv-app`. Usage access was the only door
 * left, and it answers a different question: *used lately* is not *open*, and
 * on this unit even that screen could not be opened.
 *
 * `/proc` is the door nobody closed there. On the releases where the platform
 * still mounts it plainly - Android 9, which is what the unit runs - one app can
 * read another's `/proc/<pid>/cmdline`, `status` and `oom_score_adj`, and those
 * three files are everything this needs: the process name names the app, the
 * `Uid` line gives the app's uid (so [PackageManager] can say which packages
 * share it), `VmRSS` gives its memory, and the out-of-memory score says whether
 * it is the app on screen or one waiting in the background. That is a real
 * process list, with no permission and no root.
 *
 * Two rules keep it honest, because a reader like this can lie in a way that
 * looks like a working screen:
 *
 *  * it must find a process that is *not this app's own* before it claims
 *    anything - a table that shows one uid is a table that is not answering,
 *    which is exactly what a later Android's `hidepid` looks like from here;
 *  * a name that cannot be tied to a package this screen knows is dropped
 *    silently rather than drawn as a mystery row. Daemons, native services and
 *    kernel threads are not apps, and no task manager should pretend they are.
 *
 * Nothing here runs on the UI thread: it is one small read per process, but
 * there are a hundred of them.
 */
object ProcTable {

    /** the tag `adb logcat -s AppHub` filters by */
    private const val TAG = "AppHub"

    private const val PROC = "/proc"

    /**
     * The framework's own band for "on screen": `FOREGROUND_APP_ADJ` is 0 and
     * `VISIBLE_APP_ADJ`/`PERCEPTIBLE_APP_ADJ` are 100 and 200, while anything
     * from a service (500) down to a cached process (900+) is behind the glass.
     */
    internal const val FOREGROUND_ADJ = 200

    /** One app with at least one live process. */
    class Live(
        val packageName: String,
        /** the app has a process in the foreground/visible band */
        val foreground: Boolean,
        /** the total resident memory of its processes, when the table gave one */
        val bytes: Long?,
    )

    /**
     * Every package in [packages] that owns a process. Empty means the table did
     * not answer - which the caller must treat as "cannot see", never as "nothing
     * is running".
     */
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
        // one package-manager call per uid, not one per process
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

    /**
     * The package a process runs as, chosen from the packages that share its uid.
     *
     * A process is named after the app it belongs to - `pkg`, `pkg:remote`, and
     * for a few system apps a plain `pkg.something` - which is what makes this
     * possible at all. When several packages share one uid (the Google services
     * are the classic pair) the longest name that still fits wins, because the
     * shorter one is a prefix of the process, not the app that runs it.
     */
    internal fun packageOf(name: String, candidates: List<String>): String? {
        candidates.firstOrNull { name == it || name.startsWith("$it:") }?.let { return it }
        return candidates.filter { name.startsWith("$it.") }.maxByOrNull { it.length }
    }

    /** The band the framework would call `IMPORTANCE_VISIBLE` and above. */
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

    /** `/proc/<pid>/cmdline`, which is NUL-separated; null for a kernel thread. */
    private fun cmdline(pid: Int): String? = try {
        val bytes = File("$PROC/$pid/cmdline").readBytes()
        val end = bytes.indexOf(0.toByte()).let { if (it < 0) bytes.size else it }
        if (end == 0) null else String(bytes, 0, end, Charsets.UTF_8)
    } catch (t: Throwable) {
        null
    }

    private class Status(val uid: Int, val bytes: Long?)

    /** The `Uid` and `VmRSS` lines of `/proc/<pid>/status`. */
    private fun status(pid: Int): Status? = try {
        var uid = -1
        var bytes: Long? = null
        File("$PROC/$pid/status").forEachLine { line ->
            when {
                // "Uid: real effective saved filesystem" - the effective one is
                // the app the process actually runs as
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

    /** /proc/<pid>/oom_score_adj: 0 on screen, 900+ a cached process */
    private fun adj(pid: Int): Int? = try {
        File("$PROC/$pid/oom_score_adj").readText().trim().toIntOrNull()
    } catch (t: Throwable) {
        null
    }

    /** the values after a `status` line's own label */
    private fun fields(line: String): List<String> =
        line.substringAfter(':').trim().split(Regex("\\s+"))
}
