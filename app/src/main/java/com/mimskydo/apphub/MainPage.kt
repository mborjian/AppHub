package com.mimskydo.apphub

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
        val rank = HashMap<String, Int>(apps.size * 2)
        if (order.isEmpty()) {
            pinned.forEachIndexed { index, pkg -> rank[pkg] = index }
        } else {
            order.forEachIndexed { index, pkg -> rank[pkg] = index }
        }
        return apps.sortedWith(compareBy { rank[it.packageName] ?: Int.MAX_VALUE })
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
