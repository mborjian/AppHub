package com.mahdi.apphub

import android.app.ActivityManager
import android.content.Context

/**
 * "Which of these apps have a process right now?", best effort.
 *
 * Two sources:
 *
 *  * [fromActivityManager] — since API 22 a normal app only sees *its own*
 *    processes, so this silently degrades to "nothing known" until App Hub is
 *    installed as a privileged system app, at which point it returns the
 *    truth. We detect the useless case instead of pretending it worked.
 *  * [fromProcessTable] — with root, `ps -A -o NAME` lists everything. User
 *    app processes are named after their package, with a `:suffix` for any
 *    extra process, so a prefix match is enough.
 */
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
