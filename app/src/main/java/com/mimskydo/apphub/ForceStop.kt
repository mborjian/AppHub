package com.mimskydo.apphub

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/** Which mechanism actually closed an app - shown to the driver, honestly. */
enum class CloseMethod {

    /**
     * A real force-stop: the app is gone, its task is gone, and it does not
     * come back until somebody opens it. root or `FORCE_STOP_PACKAGES`.
     */
    FORCE_STOP,

    /**
     * The task was taken away like a swipe in the recents list: the windows
     * close and the recents entry goes, but the process is left to be reaped
     * when the system needs the memory. Needs `REMOVE_TASKS`.
     */
    TASK_REMOVED,

    /**
     * The app's background processes were killed - the only thing an ordinary
     * install may do to another app.
     */
    BACKGROUND,

    /** nothing was even attempted: this install may not close other apps */
    NONE,
}

/**
 * Closing an app, in layers, strongest first. Every layer is optional and the
 * app keeps working with none of them.
 *
 *  1. **root** - `su -c am force-stop <pkg>`. Works when the device's `su`
 *     allows the app's uid. On an AOSP-style `su` (which only allows root and
 *     shell) a plain app is refused there, and this layer answers false.
 *  2. **`FORCE_STOP_PACKAGES`** - the hidden `ActivityManager.forceStopPackage()`
 *     which needs that permission: `signature|privileged`, so it is held by an
 *     APK signed with the platform key *or* installed under `/system/priv-app`.
 *     This is the layer the unit uses, and it needs no root at runtime.
 *  3. **`REMOVE_TASKS`** - the hidden `ActivityManager.removeTask(taskId)`,
 *     which closes the app's task the way the recents screen does. This is the
 *     only close that a *phone* can be talked into with adb, and only where
 *     that permission is not role-managed.
 *  4. **`killBackgroundProcesses()`** - the last resort: it reaches an app's
 *     background processes and nothing else, and a foreground process can never
 *     be killed by anyone, in any layer. That is a platform guarantee, not a
 *     gap in this app. Since Android 14 the platform has taken this layer away
 *     for other apps as well (see [canKillBackground]), which is why a plain
 *     phone install cannot close anything any more - and why the app says so
 *     instead of reporting a close that did not happen.
 *
 * The layer that answered is returned rather than a bare true, because "closed"
 * and "closed in background" are different news and the driver can act on the
 * difference.
 *
 * The app card is gated on the same question this object answers first: `Close`
 * is drawn only where [OpenAccess.forceStop] is true - root, or
 * `FORCE_STOP_PACKAGES` - so an install whose reach stops at the two weak layers
 * is never shown a close that could only answer *still open*. Those layers
 * still run as fallbacks behind a real one that is refused at the moment of the
 * tap, and the report keeps naming what actually happened.
 */
object ForceStop {

    /** the tag `adb logcat -s AppHub` filters by */
    private const val TAG = "AppHub"

    private const val PERMISSION_FORCE_STOP = "android.permission.FORCE_STOP_PACKAGES"
    private const val PERMISSION_REMOVE_TASKS = "android.permission.REMOVE_TASKS"

    /**
     * @param taskId the app's task, when the tasks source could read one: it is
     *               what lets layer 3 close the window instead of only the
     *               processes behind it. `null` skips that layer.
     */
    fun close(context: Context, packageName: String, taskId: Int? = null): CloseMethod {
        if (RootShell.forceStop(packageName)) return CloseMethod.FORCE_STOP
        if (forceStopPackage(context, packageName)) return CloseMethod.FORCE_STOP

        val removed = taskId != null && removeTask(context, taskId)
        val killed = canKillBackground(context) && killBackground(context, packageName)
        return when {
            removed -> CloseMethod.TASK_REMOVED
            killed -> CloseMethod.BACKGROUND
            else -> CloseMethod.NONE
        }
    }

    /** True when this install may call the hidden force-stop (layer 2). */
    fun isPrivileged(context: Context): Boolean = granted(context, PERMISSION_FORCE_STOP)

    /** True when a task can be taken away the way recents does it (layer 3). */
    fun canRemoveTasks(context: Context): Boolean =
        granted(context, PERMISSION_REMOVE_TASKS) || RootShell.isAvailable()

    /**
     * Layer 2. The platform checks the permission, not the call, so this asks
     * first: a refused call would be a `SecurityException` on some builds and a
     * silent no-op on others, and neither is worth causing.
     */
    private fun forceStopPackage(context: Context, packageName: String): Boolean {
        if (!isPrivileged(context)) return false
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val method = ActivityManager::class.java
                .getMethod("forceStopPackage", String::class.java)
            method.isAccessible = true
            method.invoke(am, packageName)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Layer 3: close one task. `removeTask(int)` is hidden and was moved around
     * between releases (it lives on `ActivityTaskManager` from Android 10 on),
     * so the call is looked up and its absence is a `false` rather than a crash
     * - the layers below still run.
     */
    private fun removeTask(context: Context, taskId: Int): Boolean {
        if (!canRemoveTasks(context)) return false
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (owner in listOf("android.app.ActivityManager", "android.app.ActivityTaskManager")) {
            try {
                val type = Class.forName(owner)
                val method = type.getMethod("removeTask", Int::class.javaPrimitiveType)
                method.isAccessible = true
                if (method.invoke(am, taskId) == true) return true
            } catch (t: Throwable) {
                // no such method on this release: try the other one, then give up
            }
        }
        return false
    }

    /**
     * Layer 4, and the reason it is asked about rather than assumed.
     *
     * `KILL_BACKGROUND_PROCESSES` is a normal permission, granted at install
     * time - and granted is not enough. Since **Android 14** the platform
     * documents that this call "can kill only the background processes of your
     * own app", whatever the caller does with it, so on those releases the
     * call returns having done nothing at all. Verified on an Android 15
     * emulator as a controlled pair: the same cached app, killed by `adb shell
     * am kill` and not killed by this app's own call one moment before.
     *
     * Reporting a close there would be the app claiming work the platform has
     * taken away, which is worse than saying nothing happened - so up to
     * Android 13 it is a close, and from Android 14 it is not, unless the force
     * stop above it (root, or the platform-signed install) did the job.
     *
     * It is *this* install's permission, not the target's: the first version of
     * this asked whether the app being closed holds it, which no normal app
     * does, so every close answered "nothing was even attempted" while looking
     * exactly like a close that had run.
     */
    private fun canKillBackground(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            granted(context, android.Manifest.permission.KILL_BACKGROUND_PROCESSES)

    /**
     * @return true when the platform accepted the call. It is not proof that a
     *         process died - nothing here is synchronous - but a refused call is
     *         proof that nothing did, and swallowing that refusal was how this
     *         layer came to report work it had not done.
     */
    private fun killBackground(context: Context, packageName: String): Boolean = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.killBackgroundProcesses(packageName)
        true
    } catch (t: Throwable) {
        Log.i(TAG, "killBackgroundProcesses($packageName) refused: $t")
        false
    }

    private fun granted(context: Context, permission: String): Boolean = try {
        context.packageManager.checkPermission(permission, context.packageName) ==
            PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }
}

/**
 * What to tell the driver, from what actually happened.
 *
 * The check beats the mechanism: a layer that ran is not the same as an app
 * that closed, so [CloseMethod.NONE] says *still open* rather than being
 * reported as closed because something was tried.
 */
object CloseReport {

    fun text(context: Context, method: CloseMethod, label: String): String =
        when (method) {
            CloseMethod.BACKGROUND ->
                context.getString(R.string.closed_background_toast, label)
            CloseMethod.NONE -> context.getString(R.string.close_refused_toast, label)
            else -> context.getString(R.string.closed_toast, label)
        }
}
