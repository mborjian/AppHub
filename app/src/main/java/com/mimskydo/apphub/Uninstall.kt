package com.mimskydo.apphub

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

/** Which mechanism actually took an app off the unit - shown to the driver, honestly. */
enum class UninstallMethod {

    /**
     * `pm uninstall` through the device's own `su`. The strongest layer because
     * it is the only one that answers: the shell prints `Success` only once the
     * package is already gone.
     */
    ROOT,

    /**
     * `PackageInstaller.uninstall()` with `DELETE_PACKAGES` held. The platform
     * deletes straight through, with no screen in between - what the
     * platform-signed install can do, and the only silent removal there is.
     * The call returning without a throw is the platform accepting the delete;
     * it is dispatched rather than finished, so the board's next read, not this
     * enum, is where the outcome becomes visible.
     */
    PRIVILEGED,

    /**
     * The platform's own uninstaller screen is up. Nothing has been removed
     * yet, and nothing will be unless the driver answers it - which is a thing
     * to say, not a removal to report.
     */
    SYSTEM_DIALOG,

    /** nothing was even attempted: not this app's to remove, or no uninstaller to ask */
    REFUSED,
}

/**
 * Uninstalling one app, in layers, strongest first. Every layer is optional and
 * the app keeps working with none of them.
 *
 *  1. **root** - `su -c pm uninstall <pkg>`. Works when the
 *     device's `su` allows the app's uid; on an AOSP-style `su` a plain app is
 *     refused there and this layer answers false.
 *  2. **`DELETE_PACKAGES`** - `PackageInstaller.uninstall()`, which the
 *     platform runs straight through when that permission is held:
 *     `signature|privileged`, so it belongs to an APK signed with the platform
 *     key *or* installed under `/system/priv-app` - the unit's own build. No
 *     root, and no screen.
 *  3. **`ACTION_DELETE`** - the platform's own uninstaller, hosted by Settings,
 *     which asks the driver and removes the app itself. Every install has this
 *     one, and `REQUEST_DELETE_PACKAGES` is what makes Android willing to show
 *     it to a normal app at all.
 *
 * **The one rule above all of them**: only an app the user installed may be
 * passed to any layer. [AppRepository.isUserInstalled] is asked again here even
 * though the card only draws the row for such an app - a factory app is not
 * this app's to remove, and one answer read twice is cheaper than a rule that
 * depends on a screen being right. App Hub itself is refused for the same
 * reason root exists: `pm uninstall` does not ask.
 *
 * The layer that answered is returned rather than a bare true, because
 * "gone", "about to be gone" and "Android is asking" are three different
 * pieces of news.
 */
object Uninstall {

    /** the tag `adb logcat -s AppHub` filters by */
    private const val TAG = "AppHub"

    private const val PERMISSION_DELETE_PACKAGES = "android.permission.DELETE_PACKAGES"

    /** the action of the status broadcast the platform sends and nobody reads */
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

    /**
     * Layer 2. The permission is checked first for the same reason the
     * force-stop is: without it the platform does not refuse the call, it
     * quietly turns it into a request for user action instead - and a layer
     * that reports a silent removal while a screen is opening is the kind of
     * lie this codebase keeps out.
     */
    private fun uninstallPrivileged(context: Context, packageName: String): Boolean {
        if (!granted(context, PERMISSION_DELETE_PACKAGES)) return false
        return try {
            // With the permission held the platform deletes straight through -
            // no session, no screen. The status receiver is not optional to the
            // SDK, but it has no audience here on purpose: its callback arrives
            // on a binder thread long after this tap, and the board's own re-read
            // is the report the driver can act on. A broadcast nobody listens
            // for keeps the platform's contract without a component that would
            // exist only to log.
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

    /**
     * Layer 3, and the one that runs on an ordinary install. The intent is
     * Android's own uninstaller, so the removal it performs is the platform's,
     * with the platform's own confirmation - this app has only asked for it to
     * be shown. On a build with no uninstaller at all (a stripped unit image)
     * the refusal is a `false` rather than a crash.
     */
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

/**
 * What to tell the driver, from what actually happened.
 *
 * The three sentences are deliberately not one: a removal the platform carried
 * out, a removal it has only been asked to show a screen for, and a refusal are
 * different news, and only one of them says the app is off the unit.
 */
object UninstallReport {

    fun text(context: Context, method: UninstallMethod, label: String): String = when (method) {
        UninstallMethod.ROOT, UninstallMethod.PRIVILEGED ->
            context.getString(R.string.uninstalled_toast, label)
        UninstallMethod.SYSTEM_DIALOG -> context.getString(R.string.uninstall_asking_toast)
        UninstallMethod.REFUSED -> context.getString(R.string.uninstall_refused_toast, label)
    }
}
