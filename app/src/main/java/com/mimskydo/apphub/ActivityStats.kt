package com.mimskydo.apphub

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.util.Log
import android.provider.Settings
import java.util.HashMap

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
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                val when_ = event.timeStamp
                if ((out[pkg] ?: 0L) < when_) out[pkg] = when_
            }
        }
        out
    } catch (t: Throwable) {
        emptyMap()
    }

    fun openAccessSettings(context: Context): Boolean {
        for (intent in accessScreens(context)) {
            try {
                context.startActivity(intent)
                Log.i(TAG, "settings opened for ${intent.action}")
                return true
            } catch (t: Throwable) {
                Log.i(TAG, "settings screen refused: $t")
            }
        }
        return false
    }

    private fun accessScreens(context: Context): List<Intent> = listOf(
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ),
        Intent(Settings.ACTION_SETTINGS),
    ).map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    private const val TAG = "AppHub"
}
