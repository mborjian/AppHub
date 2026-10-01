package com.mimskydo.apphub

import android.graphics.drawable.Drawable

/** What the row's badge should say, best effort. */
enum class AppState {
    /** nothing known about it */
    IDLE,

    /** a process for this package exists right now (privileged or root view) */
    RUNNING,

    /** the system's own recents list still holds a task for it */
    OPEN,

    /** brought to the front recently - the usage-access view, not a promise */
    RECENT,
}

/** One installed, launchable app as shown in the list. */
data class AppEntry(
    val label: String,
    val packageName: String,
    val activityName: String,
    val icon: Drawable,
    val pinned: Boolean = false,
    val state: AppState = AppState.IDLE,
    /**
     * True for an app the unit came with, including one of those that has been
     * updated in place since.
     *
     * Carried on the entry rather than asked again where it matters: the action
     * card offers to uninstall an app or does not, and one answer read once is
     * what keeps that offer and the board that drew it from disagreeing.
     */
    val system: Boolean = false,

    /**
     * True for one of App Hub's own screens, standing on the main page as a tile
     * - today, the file manager.
     *
     * A tile and not an app, and the difference is read in two places: the card
     * offers a screen what applies to a screen (open it, pin it, take the tile
     * away) rather than what applies to an app, and the board never treats it as
     * something that can be closed or uninstalled.
     */
    val tool: Boolean = false,
)
