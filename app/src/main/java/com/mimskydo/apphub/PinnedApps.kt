package com.mimskydo.apphub

import android.content.Context

/**
 * The apps the user pinned, in the order they were pinned (newest first).
 * Stored as a newline-joined string because SharedPreferences has no ordered
 * set type.
 */
class PinnedApps(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<String> =
        (prefs.getString(KEY, "") ?: "")
            .split('\n')
            .filter { it.isNotEmpty() }

    fun isPinned(packageName: String): Boolean = packageName in all()

    fun pin(packageName: String) {
        val next = all().toMutableList()
        next.remove(packageName)
        next.add(0, packageName)
        save(next)
    }

    fun unpin(packageName: String) {
        save(all().filter { it != packageName })
    }

    private fun save(packages: List<String>) {
        prefs.edit().putString(KEY, packages.joinToString("\n")).apply()
    }

    private companion object {
        const val PREFS = "apphub"
        const val KEY = "pinned"
    }
}
