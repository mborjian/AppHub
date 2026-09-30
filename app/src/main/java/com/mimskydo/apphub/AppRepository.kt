package com.mimskydo.apphub

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

            // The same answer decides two things at once: whether a factory app
            // is on the board at all, and whether the app card may offer to take
            // this one off the unit - see [Uninstall].
            val system = !isUserInstalled(context, pkg)
            if (!includeSystem && system) continue

            out += AppEntry(
                label = info.loadLabel(pm).toString(),
                packageName = pkg,
                activityName = activity.name,
                icon = info.loadIcon(pm),
                system = system,
            )
        }

        // Collator so Persian/Arabic names sort the way a reader expects,
        // not by UTF-16 code point.
        val collator = Collator.getInstance()
        out.sortWith(Comparator { a, b -> collator.compare(a.label, b.label) })
        if (descending) out.reverse()
        return out
    }

    companion object {

        /**
         * True for an app the user put on the unit: not one the unit came with,
         * and not one of those that has since been updated in place.
         *
         * Read in exactly two places - the board deciding what to draw, and the
         * card deciding whether to offer to uninstall - which is the point: a
         * factory app is one thing, and both have to agree on what it is.
         */
        fun isUserInstalled(context: Context, packageName: String): Boolean = try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            (info.flags and (ApplicationInfo.FLAG_SYSTEM or
                ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}
