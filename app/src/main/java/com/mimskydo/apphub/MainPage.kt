package com.mimskydo.apphub

import android.content.Context
import androidx.core.content.ContextCompat

/**
 * The main page as a value: which apps it shows, in which order, and which cells
 * it draws.
 *
 * Two screens show that page - the board itself and the preview beside the
 * shortcuts list - and both have to agree on it to the pixel, so neither of them
 * arranges it itself.
 */
object MainPage {

    /**
     * The page as both screens read it: the apps, and App Hub's own screens
     * standing on it as tiles.
     *
     * The tiles are made here rather than read from the package manager, because
     * they *are* this app: the repository skips App Hub's own package when it
     * reads the launcher activities, so its own screens have no launcher entry to
     * be found. One place that decides what the page holds is what keeps the board
     * and the preview from disagreeing about it.
     */
    fun page(
        context: Context,
        apps: List<AppEntry>,
        order: List<String>,
        pinned: List<String>,
    ): List<AppEntry> = arrange(toolTiles(context, pinned) + apps, order, pinned)

    /**
     * App Hub's own screens, as tiles, while their switches are on - in front of
     * the apps, which is where a page with no order of its own and nothing pinned
     * draws them. Once the driver moves a tile anywhere the page has an order, and
     * a tile is in it like anything else.
     *
     * Which screens there are, what they are called and which switch owns them is
     * [Tool]'s business; this only turns them into cells.
     */
    private fun toolTiles(context: Context, pinned: List<String>): List<AppEntry> {
        val prefs = Prefs(context)
        return Tool.entries.filter { it.shown(prefs) }.mapNotNull { tool ->
            val icon = ContextCompat.getDrawable(context, tool.icon) ?: return@mapNotNull null
            AppEntry(
                label = context.getString(tool.title),
                // the package the screen is really installed in, so the card can
                // start it - while the *key* is what the page knows it by, since
                // every tile here shares this one package name
                packageName = context.packageName,
                activityName = tool.screen.name,
                icon = icon,
                pinned = pinned.contains(tool.key),
                tool = tool,
            )
        }
    }

    /**
     * [apps] as the main page arranges them. [apps] arrives in the repository's
     * order, which is the Sort setting's.
     *
     * An explicit [order] - written the first time a tile is moved, on either
     * screen - wins outright: those apps come first, in it, and everything else
     * follows in the order it arrived in. That is also why a newly installed (or
     * newly ticked) app with no place in the order appears at the end: it is the
     * one spot that is never in the way of a deliberate arrangement, and it is
     * easy to find again.
     *
     * Without one, the pinned apps come first, exactly as the board has always
     * drawn them. The sort is stable, so equal ranks keep the repository's order.
     */
    fun arrange(apps: List<AppEntry>, order: List<String>, pinned: List<String>): List<AppEntry> {
        // a tile is ranked by key, not by package: both of this app's own tiles
        // are installed as the same package, so a name would rank them together
        val rank = HashMap<String, Int>(apps.size * 2)
        if (order.isEmpty()) {
            pinned.forEachIndexed { index, pkg -> rank[pkg] = index }
        } else {
            order.forEachIndexed { index, pkg -> rank[pkg] = index }
        }
        return apps.sortedWith(compareBy { rank[it.key] ?: Int.MAX_VALUE })
    }

    /**
     * The cells, in the order the board draws them: the apps, and - only while
     * there is nothing to show - the one cell that spans the board and says so.
     *
     * The gear and Close used to be appended here. They are not cells any more:
     * the tools they opened float over the board (see [MainActivity]), which is
     * what lets the board be nothing but the apps.
     */
    fun cells(apps: List<AppEntry>): List<GridItem> {
        if (apps.isEmpty()) return listOf(GridItem.Panel(PanelKind.EMPTY))
        return apps.map { GridItem.App(it) }
    }
}
