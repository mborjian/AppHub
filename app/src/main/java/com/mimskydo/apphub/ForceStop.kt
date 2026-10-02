package com.mimskydo.apphub

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

enum class CloseMethod {

    FORCE_STOP,

    TASK_REMOVED,

    BACKGROUND,

    NONE,
}

object ForceStop {

    private const val TAG = "AppHub"

    private const val PERMISSION_FORCE_STOP = "android.permission.FORCE_STOP_PACKAGES"
    private const val PERMISSION_REMOVE_TASKS = "android.permission.REMOVE_TASKS"

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

    fun isPrivileged(context: Context): Boolean = granted(context, PERMISSION_FORCE_STOP)

    fun canRemoveTasks(context: Context): Boolean =
        granted(context, PERMISSION_REMOVE_TASKS) || RootShell.isAvailable()

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
            }
        }
        return false
    }

    private fun canKillBackground(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            granted(context, android.Manifest.permission.KILL_BACKGROUND_PROCESSES)

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

object CloseReport {

    fun text(context: Context, method: CloseMethod, label: String): String =
        when (method) {
            CloseMethod.BACKGROUND ->
                context.getString(R.string.closed_background_toast, label)
            CloseMethod.NONE -> context.getString(R.string.close_refused_toast, label)
            else -> context.getString(R.string.closed_toast, label)
        }
}
