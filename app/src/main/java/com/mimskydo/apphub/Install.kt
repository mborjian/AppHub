package com.mimskydo.apphub

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.IntentCompat
import java.io.File

enum class InstallMethod {

    SILENT,

    ASKING,

    REFUSED,
}

object Install {

    private const val TAG = "AppHub"

    private const val PERMISSION_INSTALL_PACKAGES = "android.permission.INSTALL_PACKAGES"

    private const val ACTION_INSTALL_STATUS = "com.mimskydo.apphub.INSTALL_STATUS"

    private const val BASE_APK = "base.apk"

    fun install(context: Context, apk: File): InstallMethod {
        val size = apk.length()
        if (size <= 0L || !apk.canRead()) return InstallMethod.REFUSED

        return try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                setSize(size)
            }
            installer.openSession(installer.createSession(params)).use { session ->
                session.openWrite(BASE_APK, 0, size).use { output ->
                    apk.inputStream().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }
                session.commit(status(context))
            }
            if (granted(context, PERMISSION_INSTALL_PACKAGES)) InstallMethod.SILENT
            else InstallMethod.ASKING
        } catch (t: Throwable) {
            Log.i(TAG, "install ${apk.name} refused: $t")
            InstallMethod.REFUSED
        }
    }

    private fun status(context: Context): IntentSender = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, InstallStatusReceiver::class.java).setAction(ACTION_INSTALL_STATUS),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    ).intentSender

    private fun granted(context: Context, permission: String): Boolean = try {
        context.packageManager.checkPermission(permission, context.packageName) ==
            PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }
}

class InstallStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val session = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val ask = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
            if (ask != null && show(context, ask)) return
            Log.i(TAG, "install session=$session wanted the installer screen and there is none")
            return
        }

        Log.i(
            TAG,
            "install session=$session status=$status " +
                (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""),
        )
    }

    private fun show(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (t: Throwable) {
        Log.i(TAG, "installer screen refused: $t")
        false
    }

    private companion object {
        private const val TAG = "AppHub"
    }
}

object InstallReport {

    fun text(context: Context, method: InstallMethod, label: String): String = when (method) {
        InstallMethod.SILENT -> context.getString(R.string.files_installing_toast, label)
        InstallMethod.ASKING -> context.getString(R.string.files_install_asking_toast)
        InstallMethod.REFUSED -> context.getString(R.string.files_install_refused_toast, label)
    }
}
