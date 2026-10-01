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

/** Which layer of [Install] the tap actually reached - shown to the driver, honestly. */
enum class InstallMethod {

    /**
     * `INSTALL_PACKAGES` is held, so the platform puts the package on with no
     * screen in between: the unit's own platform-signed install, and the only
     * silent install there is. The commit returning without a throw is the
     * platform taking the session; the install itself finishes behind it.
     */
    SILENT,

    /**
     * The platform's own installer screen is the thing that installs this - it
     * is already opening, and nothing is on the unit until the driver answers
     * it. Every ordinary install goes this way: Android will not let an app put
     * a package on by itself, and it says so with a screen rather than with a
     * refusal.
     */
    ASKING,

    /** nothing was attempted: the file cannot be read, or the platform refused the session */
    REFUSED,
}

/**
 * Installing an APK from the file manager, in one session that ends in either an
 * install or Android's own install screen.
 *
 * The APK is read here and written into a `PackageInstaller` session rather than
 * handed to the installer as a URI. That is not a detour: a file manager must be
 * able to install a package that is on a card the driver just plugged in, and a
 * URI would mean a second provider able to hand out any file on the unit - a
 * door this app deliberately keeps shut (see `update_paths.xml`). A session
 * takes the bytes from a path, and the path can be anywhere.
 *
 * The layer that answered is returned rather than a bare true, because
 * "installing" and "Android is asking" are different pieces of news - and the
 * difference is exactly the permission this app does or does not hold.
 */
object Install {

    /** the tag `adb logcat -s AppHub` filters by */
    private const val TAG = "AppHub"

    private const val PERMISSION_INSTALL_PACKAGES = "android.permission.INSTALL_PACKAGES"

    /** the action of the status broadcast the session reports back on */
    private const val ACTION_INSTALL_STATUS = "com.mimskydo.apphub.INSTALL_STATUS"

    /** the one name a single-APK session has room for */
    private const val BASE_APK = "base.apk"

    fun install(context: Context, apk: File): InstallMethod {
        val size = apk.length()
        if (size <= 0L || !apk.canRead()) return InstallMethod.REFUSED

        return try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                // what the platform reserves room for before it copies anything
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
            // the session is dropped by the platform when nothing committed it,
            // so a refusal here leaves nothing behind on the unit
            Log.i(TAG, "install ${apk.name} refused: $t")
            InstallMethod.REFUSED
        }
    }

    /**
     * Where the session's own news is sent: an explicit broadcast to this app's
     * own receiver, so nothing else can answer for it.
     */
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

/**
 * Where a session's news arrives, and the one place Android's install screen can
 * be opened from.
 *
 * A commit that needs the driver's answer comes back as
 * `STATUS_PENDING_USER_ACTION` carrying the intent of that screen, and the
 * platform does not install the package until it has been answered. It arrives
 * on a binder thread after the tap that started it - possibly after this app's
 * own screen has gone - which is why this is a receiver in the manifest rather
 * than a callback in the file manager.
 *
 * The outcome is logged and not shouted: the installer's screen is the report
 * for an install the driver is being asked about, and the app that was installed
 * appears on the board by itself a moment later.
 */
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

        // the one line worth leaving in a build that ships to a car: which
        // session ended, how, and what the platform said about it
        Log.i(
            TAG,
            "install session=$session status=$status " +
                (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""),
        )
    }

    /** the platform's install screen, from a process that may have no screen of its own up */
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

/**
 * What to tell the driver, from which layer answered.
 *
 * "Installing" rather than "installed" in the silent case, because the platform
 * carries the session out behind the tap: the file is on its way to the
 * installer and nothing has claimed it landed. The asking case names the screen
 * instead of the outcome, because that screen is the install.
 */
object InstallReport {

    fun text(context: Context, method: InstallMethod, label: String): String = when (method) {
        InstallMethod.SILENT -> context.getString(R.string.files_installing_toast, label)
        InstallMethod.ASKING -> context.getString(R.string.files_install_asking_toast)
        InstallMethod.REFUSED -> context.getString(R.string.files_install_refused_toast, label)
    }
}
