package com.mimskydo.apphub

import android.app.ActivityManager
import android.content.Context

object RunningApps {

    fun fromActivityManager(context: Context): Set<String> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val processes = try {
            am.runningAppProcesses
        } catch (t: Throwable) {
            null
        } ?: return emptySet()

        val out = HashSet<String>()
        for (process in processes) {
            for (pkg in process.pkgList ?: continue) {
                if (pkg != context.packageName) out.add(pkg)
            }
        }
        return out
    }

    fun fromProcessTable(processNames: Set<String>,
                         packages: Collection<String>): Set<String> {
        if (processNames.isEmpty()) return emptySet()
        val out = HashSet<String>()
        for (pkg in packages) {
            if (processNames.any { it == pkg || it.startsWith("$pkg:") }) out.add(pkg)
        }
        return out
    }
}
