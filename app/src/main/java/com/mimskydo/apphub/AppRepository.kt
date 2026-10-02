package com.mimskydo.apphub

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.text.Collator

class AppRepository(private val context: Context) {

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

            if (pkg == context.packageName || !seenPackages.add(pkg)) continue

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

        val collator = Collator.getInstance()
        out.sortWith(Comparator { a, b -> collator.compare(a.label, b.label) })
        if (descending) out.reverse()
        return out
    }

    companion object {

        fun isUserInstalled(context: Context, packageName: String): Boolean = try {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            (info.flags and (ApplicationInfo.FLAG_SYSTEM or
                ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }
}
