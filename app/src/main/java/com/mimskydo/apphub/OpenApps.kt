package com.mimskydo.apphub

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * How a package came to be in the list, best first.
 *
 * The distinction is not decoration: the board's dot says which one answered,
 * because "this app is open" and "this app was used twenty minutes ago" are
 * different claims and only one of them is a promise.
 */
enum class OpenSource {

    /** the system's own recents list still holds a task for it */
    TASK,

    /** it has a process right now */
    PROCESS,

    /** it was brought to the front recently (usage access) */
    USAGE,
}

/** One app that is open - or, for [OpenSource.USAGE], was open not long ago. */
data class OpenApp(
    val packageName: String,
    val source: OpenSource,
    val taskId: Int?,
    /** what the process table could read about its memory, when it could */
    val bytes: Long? = null,
)

/**
 * What this *install* may read and close, asked once and shown to the driver.
 *
 * These are permission checks, not guesses: every one of them is the exact
 * condition the framework checks itself, so a `false` here is the reason a
 * button will not work rather than a hunch about it.
 */
data class OpenAccess(
    /** `REAL_GET_TASKS`: the recents list answers, so "open" can be exact */
    val tasks: Boolean,
    /** the one-time Usage access opt-in */
    val usage: Boolean,
    /** root, or `FORCE_STOP_PACKAGES` (platform-signed or a privileged install) */
    val forceStop: Boolean,
    /** root, or `REMOVE_TASKS`: a task can be closed like a swipe in recents */
    val removeTask: Boolean,
    /** root: which also reads the process table and force-stops anything */
    val root: Boolean,
) {

}

/**
 * One read of "what is open", whichever of the sources this install can reach.
 *
 * Every source is tried in the order of how much it can be trusted, and the one
 * that answered is kept. A source that *cannot* answer is indistinguishable
 * from one that answered "nothing" only by asking the framework - so the two
 * cheap sources prove themselves by returning somebody else's package: an app
 * that is refused sees only its own processes, and an app that is not allowed
 * to read tasks sees only its own task, and both are then skipped instead of
 * being reported as an empty device.
 */
data class OpenSnapshot(
    /** every open app: the strongest source that answered */
    val open: List<OpenApp>,
    /** packages with a process, for the board's dot */
    val running: Set<String>,
    val access: OpenAccess,
    /** null when nothing could see anything */
    val source: OpenSource?,
    /**
     * the packages whose own process is the one in front, so a row can say
     * *Running* rather than *Open*; empty for the sources that cannot tell
     */
    val foreground: Set<String> = emptySet(),
) {

    private val byPackage: Map<String, OpenApp> by lazy {
        open.associateBy { it.packageName }
    }

    /** the task id behind an app, when the tasks source answered */
    fun taskId(packageName: String): Int? = byPackage[packageName]?.taskId

    /**
     * How the board draws this app, strongest first: the process in front is
     * *Running*, any other live process and any task the system still holds are
     * *Open*, and the usage view is *Recently open*. The word matters - it is
     * the difference between a promise and a guess.
     */
    fun state(packageName: String): AppState = when {
        packageName in foreground -> AppState.RUNNING
        packageName in running -> AppState.OPEN
        byPackage[packageName]?.source == OpenSource.TASK -> AppState.OPEN
        byPackage.containsKey(packageName) -> AppState.RECENT
        else -> AppState.IDLE
    }

    /** the memory the process table read for an app, when it could read one */
    fun bytes(packageName: String): Long? = byPackage[packageName]?.bytes

    /** an exact view (tasks or processes) rather than the recent-use proxy */
    val exact: Boolean get() = source == OpenSource.TASK || source == OpenSource.PROCESS
}

/**
 * "Which apps are open?", answered by the strongest source this install may use.
 *
 * The layers, strongest first:
 *
 *  1. **[OpenSource.TASK]** - `ActivityManager.getRecentTasks()`. With
 *     `REAL_GET_TASKS` this is the system's own answer to the question: every
 *     task still in recents, with its id, which is also what makes `Close`
 *     able to take a whole window away. A plain install is refused it (the
 *     method is documented to return at least its own tasks and nothing
 *     sensitive), which the caller detects rather than mis-reports.
 *  2. **[OpenSource.PROCESS]** - `getRunningAppProcesses()` with
 *     `REAL_GET_TASKS`, or `ps -A` under root. A process is *more* than open
 *     (an app can be running with no window) and *less* than open (a task can
 *     outlive its process), which is why both are reported as they are.
 *  3. **[OpenSource.PROCESS]** again - [ProcTable], which reads `/proc` itself
 *     and maps uids and process names back to packages. On the releases where
 *     that table is still world-readable this is the same truth as layer 2
 *     without needing anything the install does not have; the reader proves it
 *     can see another app before it is believed.
 *  4. **[OpenSource.USAGE]** - the usage-access opt-in, last, and labelled for
 *     what it is: apps that were brought to the front recently.
 *
 * `getRecentTasks()` is deprecated, and it is still the only non-root door to
 * the task list in the platform - the replacement the deprecation note points
 * at is a launcher-side API (`LauncherApps`), which needs the default-launcher
 * role rather than a permission. Deprecated is not the same as removed: on
 * Android 10 (the unit) it answers in full for a caller that holds the
 * permission, which is exactly how this was verified.
 */
object OpenApps {

    /** how far back the usage source looks: a car is not a stopwatch */
    const val RECENT_WINDOW_MILLIS = 30L * 60L * 1000L

    private const val MAX_TASKS = 40

    /** the tag `adb logcat -s AppHub` filters by */
    private const val TAG = "AppHub"

    private const val PERMISSION_TASKS = "android.permission.REAL_GET_TASKS"
    private const val PERMISSION_FORCE_STOP = "android.permission.FORCE_STOP_PACKAGES"
    private const val PERMISSION_REMOVE_TASKS = "android.permission.REMOVE_TASKS"

    /** the permission checks only - cheap enough for the UI thread */
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

    /**
     * Reads the open apps. Never call this on the UI thread: the root layer
     * runs `su`, and a `su` that decides to ask a human blocks.
     *
     * @param packages the installed packages, so a process name can be matched
     *                 back to the app that owns it (the root layer only)
     */
    fun read(
        context: Context,
        packages: Collection<String> = emptyList(),
        windowMillis: Long = RECENT_WINDOW_MILLIS,
    ): OpenSnapshot {
        val access = access(context)

        // 1. the system's own recents list. It is believed when it answers:
        //    the permission is held *and* the call returned something at all -
        //    an empty answer with the app's own task in it is the truth (only
        //    App Hub is open), while an answer with no tasks whatsoever means
        //    the platform is not talking to this caller and the sources below
        //    get their turn.
        if (access.tasks) {
            val tasks = recentTasks(context)
            if (tasks.list.isNotEmpty() || tasks.raw > 0) {
                return OpenSnapshot(tasks.list, emptySet(), access, OpenSource.TASK)
            }
        }

        // 2. the process table, as the framework is willing to hand it over
        val processes = RunningApps.fromActivityManager(context)
        if (processes.isNotEmpty()) {
            return OpenSnapshot(
                processes.map { OpenApp(it, OpenSource.PROCESS, null) },
                processes,
                access,
                OpenSource.PROCESS,
            )
        }

        // 2b. the process table, read straight out of /proc. On the releases
        //     where it is still world-readable - Android 9 among them, and the
        //     unit is one - this is the whole answer, with no permission and no
        //     root. The reader only claims what it can prove: a table showing
        //     nobody but this app is a table that is not answering, and an
        //     empty answer here must not become "nothing is running".
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

        // 3. the process table under root - the one layer that bypasses the
        //    framework's own visibility rules
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

        // 4. usage access: not "open", "used lately", and the screen says so
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

        // Nothing answered, and this line is the only place that says *why*.
        // The screen can only repeat that it cannot see: a unit with no root, no
        // privileged install, no process table and no usage opt-in is four
        // problems that look identical from the driver's seat.
        Log.i(
            TAG,
            "open apps: nothing answered (tasks=${access.tasks} root=${access.root} " +
                "usage=${access.usage})",
        )
        return OpenSnapshot(emptyList(), emptySet(), access, null)
    }

    /**
     * The tasks the system is still holding, as packages.
     *
     * `RECENT_IGNORE_UNAVAILABLE` is deprecated along with the method and is
     * still the flag this list wants: a task whose components cannot be
     * resolved any more is not an app anybody can close.
     *
     * @param list the apps, ours left out
     * @param raw  how many tasks came back in total, ours included: the proof
     *             that the platform answered this caller at all
     */
    private class Tasks(val list: List<OpenApp>, val raw: Int)

    private fun recentTasks(context: Context): Tasks {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val tasks = try {
            @Suppress("DEPRECATION")
            am.getRecentTasks(MAX_TASKS, ActivityManager.RECENT_IGNORE_UNAVAILABLE)
        } catch (t: Throwable) {
            // a SecurityException on the platforms that hardened this further
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
