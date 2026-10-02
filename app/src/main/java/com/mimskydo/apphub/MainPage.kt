package com.mimskydo.apphub

import android.content.Context
import androidx.core.content.ContextCompat

object MainPage {

    fun page(
        context: Context,
        apps: List<AppEntry>,
        order: List<String>,
        pinned: List<String>,
    ): List<AppEntry> = arrange(toolTiles(context, pinned) + apps, order, pinned)

    private fun toolTiles(context: Context, pinned: List<String>): List<AppEntry> {
        val prefs = Prefs(context)
        return Tool.entries.filter { it.shown(prefs) }.mapNotNull { tool ->
            val icon = ContextCompat.getDrawable(context, tool.icon) ?: return@mapNotNull null
            AppEntry(
                label = context.getString(tool.title),
                packageName = context.packageName,
                activityName = tool.screen.name,
                icon = icon,
                pinned = pinned.contains(tool.key),
                tool = tool,
            )
        }
    }

    fun arrange(apps: List<AppEntry>, order: List<String>, pinned: List<String>): List<AppEntry> {
        val rank = HashMap<String, Int>(apps.size * 2)
        if (order.isEmpty()) {
            pinned.forEachIndexed { index, pkg -> rank[pkg] = index }
        } else {
            order.forEachIndexed { index, pkg -> rank[pkg] = index }
        }
        return apps.sortedWith(compareBy { rank[it.key] ?: Int.MAX_VALUE })
    }

    fun cells(apps: List<AppEntry>): List<GridItem> {
        if (apps.isEmpty()) return listOf(GridItem.Panel(PanelKind.EMPTY))
        return apps.map { GridItem.App(it) }
    }
}
