package com.mimskydo.apphub

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import java.util.HashMap

/**
 * Optional "recently active" view, built on UsageStats.
 *
 * UsageStats needs a one-time opt-in by the user (Settings → Apps → Special
 * access → Usage access), which is why [hasAccess] is checked before every
 * query and the UI offers a shortcut to that screen.
 */
object ActivityStats {

    fun hasAccess(context: Context): Boolean = try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        mode == AppOpsManager.MODE_ALLOWED
    } catch (t: Throwable) {
        false
    }

    /**
     * Which packages were brought to the front inside the window, and when.
     *
     * `ACTIVITY_RESUMED` is the whole signal, and that is the correction this
     * function needed. The previous version removed a package again on
     * `ACTIVITY_PAUSED`, which reads like "it left" but is not: an app is
     * paused *every* time it is covered - including by this hub - so with the
     * hub in front the answer was always empty, and a phone with usage access
     * was told there was nothing open while six apps had a task each.
     *
     * `PAUSED` and `STOPPED` arrive both when an app is left and when its task
     * is taken away, so neither can decide that it is gone. Only "was resumed
     * recently" is left, which is why the caller labels this source *recently
     * used* rather than *open*.
     */
    fun lastUsed(context: Context, windowMillis: Long): Map<String, Long> = try {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val events = usm.queryEvents(end - windowMillis, end)
        val out = HashMap<String, Long>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            if (pkg == context.packageName) continue
            // ACTIVITY_RESUMED is the API 29 name of the old MOVE_TO_FOREGROUND
            // event (same value), which is why one constant covers both
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                val when_ = event.timeStamp
                if ((out[pkg] ?: 0L) < when_) out[pkg] = when_
            }
        }
        out
    } catch (t: Throwable) {
        emptyMap()
    }

    /** @return true when a usage-access screen could be opened. */
    fun openAccessSettings(context: Context): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (t: Throwable) {
        false
    }
}
