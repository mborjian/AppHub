package com.mimskydo.apphub

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

enum class OpenSource {

    TASK,

    PROCESS,

    USAGE,
}

data class OpenApp(
    val packageName: String,
    val source: OpenSource,
    val taskId: Int?,
    val bytes: Long? = null,
)

data class OpenAccess(
    val tasks: Boolean,
    val usage: Boolean,
    val forceStop: Boolean,
    val removeTask: Boolean,
    val root: Boolean,
) {

}

data class OpenSnapshot(
    val open: List<OpenApp>,
    val running: Set<String>,
    val access: OpenAccess,
    val source: OpenSource?,
    val foreground: Set<String> = emptySet(),
) {

    private val byPackage: Map<String, OpenApp> by lazy {
        open.associateBy { it.packageName }
    }

    fun taskId(packageName: String): Int? = byPackage[packageName]?.taskId

    fun state(packageName: String): AppState = when {
        packageName in foreground -> AppState.RUNNING
        packageName in running -> AppState.OPEN
        byPackage[packageName]?.source == OpenSource.TASK -> AppState.OPEN
        byPackage.containsKey(packageName) -> AppState.RECENT
        else -> AppState.IDLE
    }

    fun bytes(packageName: String): Long? = byPackage[packageName]?.bytes

    val exact: Boolean get() = source == OpenSource.TASK || source == OpenSource.PROCESS
}

object OpenApps {

    const val RECENT_WINDOW_MILLIS = 30L * 60L * 1000L

    private const val MAX_TASKS = 40

    private const val TAG = "AppHub"

    private const val PERMISSION_TASKS = "android.permission.REAL_GET_TASKS"
    private const val PERMISSION_FORCE_STOP = "android.permission.FORCE_STOP_PACKAGES"
    private const val PERMISSION_REMOVE_TASKS = "android.permission.REMOVE_TASKS"

    fun access(context: Context): OpenAccess {
        val root = RootShell.isAvailable()
        return OpenAccess(
            tasks = granted(context, PERMISSION_TASKS),
            usage = ActivityStats.hasAccess(context),
            forceStop = granted(context, PERMISSION_FORCE_STOP) || root,
            removeTask = granted(context, PERMISSION_REMOVE_TASKS) || root,
            root = root,
        )
    }

    fun read(
        context: Context,
        packages: Collection<String> = emptyList(),
        windowMillis: Long = RECENT_WINDOW_MILLIS,
    ): OpenSnapshot {
        val access = access(context)

        if (access.tasks) {
            val tasks = recentTasks(context)
            if (tasks.list.isNotEmpty() || tasks.raw > 0) {
                return OpenSnapshot(tasks.list, emptySet(), access, OpenSource.TASK)
            }
        }

        val processes = RunningApps.fromActivityManager(context)
        if (processes.isNotEmpty()) {
            return OpenSnapshot(
                processes.map { OpenApp(it, OpenSource.PROCESS, null) },
                processes,
                access,
                OpenSource.PROCESS,
            )
        }

        val table = ProcTable.read(context, packages)
        if (table.isNotEmpty()) {
            return OpenSnapshot(
                open = table.map { OpenApp(it.packageName, OpenSource.PROCESS, null, it.bytes) },
                running = table.map { it.packageName }.toSet(),
                access = access,
                source = OpenSource.PROCESS,
                foreground = table.filter { it.foreground }.map { it.packageName }.toSet(),
            )
        }

        if (RootShell.isAvailable()) {
            val names = RootShell.processNames()
            if (names.isNotEmpty()) {
                val under = RunningApps.fromProcessTable(names, packages)
                return OpenSnapshot(
                    under.map { OpenApp(it, OpenSource.PROCESS, null) },
                    under,
                    access,
                    OpenSource.PROCESS,
                )
            }
        }

        if (access.usage) {
            val recent = ActivityStats.lastUsed(context, windowMillis)
            val open = recent.entries
                .sortedByDescending { it.value }
                .map { OpenApp(it.key, OpenSource.USAGE, null) }
            return OpenSnapshot(
                open = open,
                running = emptySet(),
                access = access,
                source = if (open.isEmpty()) null else OpenSource.USAGE,
            )
        }

        Log.i(
            TAG,
            "open apps: nothing answered (tasks=${access.tasks} root=${access.root} " +
                "usage=${access.usage})",
        )
        return OpenSnapshot(emptyList(), emptySet(), access, null)
    }

    private class Tasks(val list: List<OpenApp>, val raw: Int)

    private fun recentTasks(context: Context): Tasks {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val tasks = try {
            @Suppress("DEPRECATION")
            am.getRecentTasks(MAX_TASKS, ActivityManager.RECENT_IGNORE_UNAVAILABLE)
        } catch (t: Throwable) {
            null
        } ?: return Tasks(emptyList(), 0)

        val out = ArrayList<OpenApp>(tasks.size)
        val seen = HashSet<String>()
        for (task in tasks) {
            val packageName = task.baseIntent?.component?.packageName
                ?: task.origActivity?.packageName
                ?: task.topActivity?.packageName
                ?: continue
            if (packageName == context.packageName) continue
            if (!seen.add(packageName)) continue
            out += OpenApp(packageName, OpenSource.TASK, task.taskId)
        }
        return Tasks(out, tasks.size)
    }

    private fun granted(context: Context, permission: String): Boolean = try {
        context.packageManager.checkPermission(permission, context.packageName) ==
            PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }
}
