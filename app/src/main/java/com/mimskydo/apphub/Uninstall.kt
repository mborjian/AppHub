package com.mimskydo.apphub

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

enum class UninstallMethod {

    ROOT,

    PRIVILEGED,

    SYSTEM_DIALOG,

    REFUSED,
}

object Uninstall {

    private const val TAG = "AppHub"

    private const val PERMISSION_DELETE_PACKAGES = "android.permission.DELETE_PACKAGES"

    private const val ACTION_UNINSTALL_STATUS = "com.mimskydo.apphub.UNINSTALL_STATUS"

    fun uninstall(context: Context, entry: AppEntry): UninstallMethod {
        val packageName = entry.packageName

        if (packageName == context.packageName) return UninstallMethod.REFUSED
        if (!AppRepository.isUserInstalled(context, packageName)) return UninstallMethod.REFUSED

        if (RootShell.uninstall(packageName)) return UninstallMethod.ROOT
        if (uninstallPrivileged(context, packageName)) return UninstallMethod.PRIVILEGED
        if (askTheSystem(context, packageName)) return UninstallMethod.SYSTEM_DIALOG
        return UninstallMethod.REFUSED
    }

    private fun uninstallPrivileged(context: Context, packageName: String): Boolean {
        if (!granted(context, PERMISSION_DELETE_PACKAGES)) return false
        return try {
            val status = PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_UNINSTALL_STATUS),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            context.packageManager.packageInstaller.uninstall(packageName, status.intentSender)
            true
        } catch (t: Throwable) {
            Log.i(TAG, "PackageInstaller.uninstall($packageName) refused: $t")
            false
        }
    }

    private fun askTheSystem(context: Context, packageName: String): Boolean = try {
        context.startActivity(
            Intent(Intent.ACTION_DELETE, Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    private fun granted(context: Context, permission: String): Boolean = try {
        context.packageManager.checkPermission(permission, context.packageName) ==
            PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }
}

object UninstallReport {

    fun text(context: Context, method: UninstallMethod, label: String): String = when (method) {
        UninstallMethod.ROOT, UninstallMethod.PRIVILEGED ->
            context.getString(R.string.uninstalled_toast, label)
        UninstallMethod.SYSTEM_DIALOG -> context.getString(R.string.uninstall_asking_toast)
        UninstallMethod.REFUSED -> context.getString(R.string.uninstall_refused_toast, label)
    }
}
