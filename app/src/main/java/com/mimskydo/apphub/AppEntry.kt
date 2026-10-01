package com.mimskydo.apphub

import android.app.Activity
import android.graphics.drawable.Drawable
import java.util.Locale

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
     * Which of App Hub's own screens this is, or null for a real app.
     *
     * A tile and not an app, and the difference is read in two places: the card
     * offers a screen what applies to a screen (open it, pin it, take the tile
     * away) rather than what applies to an app, and the board never treats it as
     * something that can be closed or uninstalled.
     */
    val tool: Tool? = null,
) {

    /**
     * What the board knows this tile by.
     *
     * The package name for an app. For a screen of this app, a name no package
     * could have: pinning, the page order, the search filter and the shortcuts
     * screen all read a tile by one string, and both of App Hub's own tiles are
     * installed as the same package - so pinning one on that name would pin the
     * other, and the page could not tell them apart.
     */
    val key: String get() = tool?.key ?: packageName
}

/**
 * One of App Hub's own screens, standing on the main page as a tile while its
 * switch in the settings is on.
 *
 * The table is here rather than spread across the board, the metrics screen and
 * the settings, because all three need the same facts about a tile and two tiles
 * must not be able to disagree about them. The board is built by
 * [MainPage.page], which reads this; nothing else decides what a tile is.
 */
enum class Tool(
    /** the tile's own icon, filled edge to edge like the icons beside it */
    val icon: Int,
    val title: Int,
    /** the one line the card's header shows, instead of a package name */
    val subtitle: Int,
    /** the screen this tile stands for */
    val screen: Class<out Activity>,
) {

    FILES(
        icon = R.drawable.ic_folder_tile,
        title = R.string.files_title,
        subtitle = R.string.files_card_subtitle,
        screen = FileManagerActivity::class.java,
    ),

    BROWSER(
        icon = R.drawable.ic_web_tile,
        title = R.string.browser_title,
        subtitle = R.string.browser_card_subtitle,
        screen = BrowserActivity::class.java,
    ),

    ;

    /**
     * What the board and the settings file know this tile by.
     *
     * A `:` cannot appear in a package name, so a key here can never collide with
     * an app that is really installed - and it is deliberately not the package
     * name both tiles share.
     */
    val key: String get() = "hub:" + name.lowercase(Locale.US)

    /** whether the settings ask for this tile to be on the board */
    fun shown(prefs: Prefs): Boolean = when (this) {
        FILES -> prefs.showFileManager
        BROWSER -> prefs.showBrowser
    }

    /**
     * The one place that answer is written, whoever writes it: the settings row
     * and the tile's own card go through here, which is what keeps a switch and a
     * card from disagreeing about the same tile.
     */
    fun show(prefs: Prefs, on: Boolean) {
        when (this) {
            FILES -> prefs.showFileManager = on
            BROWSER -> prefs.showBrowser = on
        }
    }
}
