package com.mahdi.apphub

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.text.Collator

/**
 * Reads the apps to show in the grid.
 *
 * All PackageManager work happens on a background thread: querying the
 * activities plus loading ~n icons takes long enough to drop frames.
 */
class AppRepository(private val context: Context) {

    /**
     * @param includeSystem also list factory/system apps, not just the ones
     *   the user installed themselves.
     */
    fun loadApps(
        includeSystem: Boolean = false,
        descending: Boolean = false,
    ): List<AppEntry> {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)

        val resolved = pm.queryIntentActivities(launcherIntent, 0)
        val seenPackages = HashSet<String>(resolved.size)
        val out = ArrayList<AppEntry>(resolved.size)

        for (info in resolved) {
            val activity = info.activityInfo ?: continue
            val pkg = activity.packageName ?: continue

            // one cell per app, even when it exposes several launcher activities
            if (pkg == context.packageName || !seenPackages.add(pkg)) continue
            if (!includeSystem && !isUserInstalled(pm, pkg)) continue

            out += AppEntry(
                label = info.loadLabel(pm).toString(),
                packageName = pkg,
                activityName = activity.name,
                icon = info.loadIcon(pm),
            )
        }

        // Collator so Persian/Arabic names sort the way a reader expects,
        // not by UTF-16 code point.
        val collator = Collator.getInstance()
        out.sortWith(Comparator { a, b -> collator.compare(a.label, b.label) })
        if (descending) out.reverse()
        return out
    }

    private fun isUserInstalled(pm: PackageManager, pkg: String): Boolean =
        try {
            val info = pm.getApplicationInfo(pkg, 0)
            // system + updated-system apps are excluded by default: the hub is
            // for the apps the user installed themselves
            (info.flags and (ApplicationInfo.FLAG_SYSTEM or
                ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
}
