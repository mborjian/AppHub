package com.mimskydo.apphub

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale
import kotlin.math.roundToInt

/** Light / dark / whatever the unit is set to. */
enum class ThemeMode(val id: String, val label: Int, val nightMode: Int) {
    SYSTEM("system", R.string.theme_system, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT("light", R.string.theme_light, AppCompatDelegate.MODE_NIGHT_NO),
    DARK("dark", R.string.theme_dark, AppCompatDelegate.MODE_NIGHT_YES),
    ;

    companion object {
        fun of(id: String?): ThemeMode = values().firstOrNull { it.id == id } ?: SYSTEM
    }
}

/**
 * Which way this app's own layout runs. Deliberately independent of the
 * language used for strings: forcing RTL here also reorders English text.
 */
enum class Direction(val id: String, val label: Int) {
    SYSTEM("system", R.string.direction_system),
    RTL("rtl", R.string.direction_rtl),
    LTR("ltr", R.string.direction_ltr),
    ;

    companion object {
        fun of(id: String?): Direction = values().firstOrNull { it.id == id } ?: SYSTEM
    }
}

/**
 * Icon edge length in dp - the grid cell grows with it.
 *
 * Sized with adaptive icons in mind: one of those draws its artwork inset to
 * the middle two thirds of its box, so 64dp of box is closer to 48dp of visible
 * icon, which is what a launcher icon is normally drawn at.
 */
enum class IconSize(val dp: Int, val label: Int) {
    SMALL(48, R.string.size_small),
    MEDIUM(64, R.string.size_medium),
    LARGE(80, R.string.size_large),
    HUGE(96, R.string.size_huge),
    ;

    companion object {
        fun of(id: String?): IconSize = values().firstOrNull { it.name == id } ?: MEDIUM
    }
}

/** How an app icon is clipped. [ORIGINAL] leaves the launcher's art alone. */
enum class IconShape(val label: Int) {
    ORIGINAL(R.string.shape_original),
    ROUNDED(R.string.shape_rounded),
    CIRCLE(R.string.shape_circle),
    IPHONE(R.string.shape_iphone),
    SAMSUNG(R.string.shape_samsung),
    ;

    companion object {
        fun of(id: String?): IconShape = values().firstOrNull { it.name == id } ?: ROUNDED
    }
}

enum class SortOrder(val id: String, val label: Int) {
    ASCENDING("asc", R.string.sort_asc),
    DESCENDING("desc", R.string.sort_desc),
    ;

    companion object {
        fun of(id: String?): SortOrder = values().firstOrNull { it.id == id } ?: ASCENDING
    }
}

/**
 * One edge of the screen, with the key its value is stored under.
 *
 * The four edges are physical, not `start`/`end`: what hides part of the
 * screen is a rail on one side of the display, and that side does not move
 * when the layout direction is flipped.
 */
enum class Margin(val id: String, val label: Int) {
    LEFT("margin_left", R.string.margin_left),
    RIGHT("margin_right", R.string.margin_right),
    TOP("margin_top", R.string.margin_top),
    BOTTOM("margin_bottom", R.string.margin_bottom),
}

/** The four margins as one value. */
data class Margins(val left: Int, val right: Int, val top: Int, val bottom: Int)

/**
 * Everything the settings screen can change.
 *
 * Lives in the same SharedPreferences file as [PinnedApps], so [reset] removes
 * its own keys one by one rather than clearing the file - clearing would throw
 * the user's pinned apps away too.
 */
class Prefs(context: Context) {

    private val appContext = context.applicationContext

    private val prefs = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var showNames: Boolean
        get() = prefs.getBoolean(KEY_NAMES, true)
        set(value) = prefs.edit().putBoolean(KEY_NAMES, value).apply()

    /**
     * The apps that are kept off the main page.
     *
     * Stored as the *hidden* ones rather than the shown ones, so an app the
     * user installs later is on the main page without anyone having to tick it,
     * and uninstalling an app leaves nothing behind that has to be cleaned up.
     */
    fun isHidden(packageName: String): Boolean = hiddenSet().contains(packageName)

    fun setHidden(packageName: String, hidden: Boolean) {
        // the set from SharedPreferences must not be edited in place
        val next = HashSet(hiddenSet())
        if (hidden) next.add(packageName) else next.remove(packageName)
        prefs.edit().putStringSet(KEY_HIDDEN, next).apply()
    }

    /** Tick or untick every one of them, which is what the All / None pair does. */
    fun setHiddenAll(packageNames: Collection<String>, hidden: Boolean) {
        val next = HashSet(hiddenSet())
        if (hidden) next.addAll(packageNames) else next.removeAll(packageNames.toSet())
        prefs.edit().putStringSet(KEY_HIDDEN, next).apply()
    }

    private fun hiddenSet(): Set<String> = prefs.getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet()

    /**
     * The main page's own order, once the user has moved a tile in the
     * shortcuts preview. Empty means they never have, and the page keeps the
     * arrangement the Sort and Pin settings describe.
     *
     * Stored as package names rather than as positions, so an app that is
     * uninstalled or hidden and later comes back lands where it was left
     * instead of pushing everything else along.
     */
    var pageOrder: List<String>
        get() = (prefs.getString(KEY_ORDER, "") ?: "").split('\n').filter { it.isNotEmpty() }
        set(value) {
            val editor = prefs.edit()
            if (value.isEmpty()) {
                // no arrangement at all: the key goes rather than holding an
                // empty string, so "the page is sorted" has one representation
                editor.remove(KEY_ORDER)
            } else {
                editor.putString(KEY_ORDER, value.joinToString("\n"))
            }
            editor.apply()
        }

    /**
     * What the shortcuts list was last filtered by.
     *
     * Remembered rather than cleared, so coming back to the list lands on the
     * apps that were being worked on; the field's own clear button is one tap
     * away, and the count above the list always says how many are showing.
     */
    var shortcutsFilter: String
        get() = prefs.getString(KEY_FILTER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FILTER, value).apply()

    var iconSize: IconSize
        get() = IconSize.of(prefs.getString(KEY_ICON_SIZE, null))
        set(value) = prefs.edit().putString(KEY_ICON_SIZE, value.name).apply()

    var iconShape: IconShape
        get() = IconShape.of(prefs.getString(KEY_SHAPE, null))
        set(value) = prefs.edit().putString(KEY_SHAPE, value.name).apply()

    /** 0 = fit as many columns as the screen allows (the default). */
    var columns: Int
        get() = prefs.getInt(KEY_COLUMNS, 0)
        set(value) = prefs.edit().putInt(KEY_COLUMNS, value).apply()

    var includeSystem: Boolean
        get() = prefs.getBoolean(KEY_SYSTEM, false)
        set(value) = prefs.edit().putBoolean(KEY_SYSTEM, value).apply()

    /**
     * True while the file manager stands on the main page as a tile of its own.
     *
     * A setting and not an entry in the hidden-apps set, because the tile is not
     * an app: App Hub's own screens are skipped when the board reads the
     * launcher activities, so the switch on the settings screen and the tile on
     * the page are one decision - and the tile's own card writes *this* one when
     * it is hidden, which is what keeps the two from disagreeing.
     */
    var showFileManager: Boolean
        get() = prefs.getBoolean(KEY_FILES, true)
        set(value) = prefs.edit().putBoolean(KEY_FILES, value).apply()

    /**
     * True while the web browser tile stands on the main page.
     *
     * Its own switch, beside the file manager's, and the tile's own card writes
     * this same one when it is hidden - one decision, one record, whichever
     * screen made it.
     */
    var showBrowser: Boolean
        get() = prefs.getBoolean(KEY_BROWSER, true)
        set(value) = prefs.edit().putBoolean(KEY_BROWSER, value).apply()

    /**
     * True once the offer to switch the running marks on has been waved away.
     *
     * The offer is a banner on the board rather than a row in the settings,
     * because an unexplained absence of dots is a bug from the driver's side of
     * the screen - and a switch nobody knows to look for is not an offer.
     */
    var dotsOfferDismissed: Boolean
        get() = prefs.getBoolean(KEY_DOTS_OFFER, false)
        set(value) = prefs.edit().putBoolean(KEY_DOTS_OFFER, value).apply()

    /**
     * True once the task manager's "this is what was used recently, not what is
     * open" banner has been waved away.
     *
     * Dismissible because it is a limitation of the phone it is running on and
     * not a thing to do: on the unit, where the install reads the task list,
     * the banner never appears at all.
     */
    var tasksOfferDismissed: Boolean
        get() = prefs.getBoolean(KEY_TASKS_OFFER, false)
        set(value) = prefs.edit().putBoolean(KEY_TASKS_OFFER, value).apply()

    /**
     * The version an update was installing when this app's own process was
     * replaced, or null when none was.
     *
     * Installing over a running package kills it, so the run that started an
     * update can never report how it went: this is what lets the next launch
     * say which of the two happened. Deliberately not part of [SETTING_KEYS] -
     * it is not a setting, and it clears itself the first time it is read.
     */
    var pendingUpdate: String?
        get() = prefs.getString(KEY_PENDING_UPDATE, null)
        set(value) {
            val editor = prefs.edit()
            if (value == null) editor.remove(KEY_PENDING_UPDATE) else editor.putString(KEY_PENDING_UPDATE, value)
            editor.apply()
        }

    /**
     * Draw everything at a size that suits the screen this app is running on.
     *
     * Not a fifth icon size: a switch, and it applies to the whole app - the
     * icons, the text, and every padding and margin in the layouts, because
     * what it changes is the dp space they are all written in (see
     * [screenScale]).
     */
    var adaptToScreen: Boolean
        get() = prefs.getBoolean(KEY_ADAPT, false)
        set(value) = prefs.edit().putBoolean(KEY_ADAPT, value).apply()

    /**
     * The screen's own width in dp, before anything of ours is applied.
     *
     * Read from the *application's* configuration rather than from a screen's:
     * [layoutContext] rewrites the width of every configuration this app is
     * inflated with, and the number that decides how much to scale must never
     * be a scaled one - a second pass through it would compound the two.
     */
    val deviceWidthDp: Int
        get() {
            val config = appContext.resources.configuration
            if (config.screenWidthDp > 0) return config.screenWidthDp

            // some head units report 0 there; fall back to the real pixels
            val metrics = appContext.resources.displayMetrics
            return if (metrics.density > 0f) (metrics.widthPixels / metrics.density).toInt()
            else metrics.widthPixels
        }

    /**
     * How much of everything this app draws is scaled up on this screen: 1
     * while the switch is off.
     *
     * The app is written in dp, and a dp is a fixed fraction of nothing: on the
     * unit's 1024dp-wide panel the 64dp icon that fills a third of a phone
     * covers a sixteenth of the screen, viewed from further away than any phone
     * is. This is that width over the width a phone has, so an icon keeps the
     * share of the *display* it would have on a phone instead of the share of
     * whatever dp space the panel claims - and it is capped, because a 4K panel
     * should not be turned into a phone.
     */
    val screenScale: Float
        get() {
            if (!adaptToScreen) return 1f
            val widthDp = deviceWidthDp
            if (widthDp <= 0) return 1f
            return (widthDp / REFERENCE_WIDTH_DP).coerceIn(1f, MAX_SCALE)
        }

    /**
     * The unit's own density: dp to pixels for the few things that must *not*
     * follow [screenScale].
     *
     * The screen margins are one of those. What they keep clear of this app's
     * content is a rail the unit draws at a fixed size, and it does not grow
     * when the icons do - so the stored dp has to keep meaning the same pixels
     * whichever way that switch is set.
     */
    val deviceDensity: Float
        get() = appContext.resources.displayMetrics.density

    var sortOrder: SortOrder
        get() = SortOrder.of(prefs.getString(KEY_SORT, null))
        set(value) = prefs.edit().putString(KEY_SORT, value.id).apply()

    var theme: ThemeMode
        get() = ThemeMode.of(prefs.getString(KEY_THEME, null))
        set(value) = prefs.edit().putString(KEY_THEME, value.id).apply()

    var direction: Direction
        get() = Direction.of(prefs.getString(KEY_DIRECTION, null))
        set(value) = prefs.edit().putString(KEY_DIRECTION, value.id).apply()

    /**
     * How far one edge of this app's content is moved inwards, in dp (0 = off).
     *
     * Plenty of factory launchers draw their own overlay across the screen
     * edges - a shortcut rail, a clock, a climate strip - which can sit on top
     * of another app's content. These four margins move this app's content
     * away from the edges so nothing important is hidden behind one.
     */
    fun margin(edge: Margin): Int = prefs.getInt(edge.id, 0).coerceIn(0, maxMargin(edge))

    fun setMargin(edge: Margin, dp: Int) {
        prefs.edit().putInt(edge.id, dp.coerceIn(0, maxMargin(edge))).apply()
    }

    /**
     * The largest value one edge may take: half of that side of the screen.
     *
     * A ceiling rather than a list of allowed values - any whole number below
     * it is valid - and half the side rather than the whole one because two
     * opposite edges at the maximum would otherwise have nothing left to show.
     * The side comes from the current configuration, so the bound is the size
     * the app is actually running at (and follows the unit's density).
     */
    fun maxMargin(edge: Margin): Int {
        val horizontal = edge == Margin.LEFT || edge == Margin.RIGHT
        val config = appContext.resources.configuration
        val screenDp = if (horizontal) config.screenWidthDp else config.screenHeightDp
        if (screenDp > 0) return screenDp / 2

        // some head units report 0 there; fall back to the real pixels
        val metrics = appContext.resources.displayMetrics
        val pixels = if (horizontal) metrics.widthPixels else metrics.heightPixels
        val widthDp = if (metrics.density > 0f) (pixels / metrics.density).toInt() else pixels
        return maxOf(1, widthDp / 2)
    }

    val margins: Margins
        get() = Margins(
            left = margin(Margin.LEFT),
            right = margin(Margin.RIGHT),
            top = margin(Margin.TOP),
            bottom = margin(Margin.BOTTOM),
        )

    /**
     * How many cells fit side by side.
     *
     * An explicit [columns] wins; otherwise the cell width follows the chosen
     * icon size, so bigger icons simply mean fewer columns - computed over the
     * width the margins leave, so auto stays honest once an edge is moved in.
     */
    fun columnCount(screenWidthDp: Int): Int {
        if (columns > 0) return columns
        if (screenWidthDp <= 0) return 3
        val m = margins
        val usable = screenWidthDp - m.left - m.right
        // +50dp of card padding and label room around the icon
        return maxOf(2, usable / (iconSize.dp + 50))
    }

    /** Back to the shipped defaults, without touching pinned apps. */

    fun reset() {
        val editor = prefs.edit()
        for (key in SETTING_KEYS) editor.remove(key)
        editor.apply()
    }

    /**
     * [base] wrapped so that the layout direction can be forced and the screen
     * adapted to. Only the `screenLayout` direction bits are changed - `locale`
     * stays as it is, so forcing RTL does not swap the strings to Persian.
     */
    fun layoutContext(base: Context): Context {
        val forced = when (direction) {
            Direction.SYSTEM -> null
            Direction.RTL -> Locale("fa", "IR")
            Direction.LTR -> Locale.US
        }

        val config = Configuration(base.resources.configuration)
        forced?.let { config.setLayoutDirection(it) }
        val scaled = adaptDensity(config, base.resources.displayMetrics.densityDpi)

        return if (forced == null && !scaled) base else base.createConfigurationContext(config)
    }

    /**
     * Moves a configuration into a denser dp space, which is what scales
     * everything at once: an icon size in dp, a text size in sp (the scaled
     * density follows the density), and every padding, margin, corner and cell
     * in the layouts. One lever instead of one per dimension, so nothing can be
     * left behind at the old size.
     *
     * The screen's own size in dp is a function of the density, so it is
     * recomputed here as well: the framework does not do it for a configuration
     * an app overrides, and a grid still laying its cells out for the old, much
     * wider screen would draw eight columns into the room of four.
     *
     * @return true when anything was changed.
     */
    private fun adaptDensity(config: Configuration, baseDpi: Int): Boolean {
        val scale = screenScale
        if (scale <= 1f) return false

        val dpi = if (config.densityDpi > 0) config.densityDpi else baseDpi
        config.densityDpi = (dpi * scale).roundToInt()

        config.screenWidthDp = denserDp(config.screenWidthDp, scale)
        config.screenHeightDp = denserDp(config.screenHeightDp, scale)
        val smallest = denserDp(config.smallestScreenWidthDp, scale)
        config.smallestScreenWidthDp = smallest

        // Which of small/normal/large/xlarge a screen is, is decided by that
        // same width, so it is brought in line with it - otherwise resources
        // would be picked for a size the app is no longer laid out at.
        if (smallest > 0) {
            val cleared = config.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK.inv()
            config.screenLayout = cleared or sizeClass(smallest)
        }
        return true
    }

    /** the same side of the screen, measured in the new dp */
    private fun denserDp(dp: Int, scale: Float): Int =
        if (dp <= 0) dp else (dp / scale).roundToInt()

    /** the AOSP thresholds, for the width the screen has after scaling */
    private fun sizeClass(smallestWidthDp: Int): Int = when {
        smallestWidthDp >= 720 -> Configuration.SCREENLAYOUT_SIZE_XLARGE
        smallestWidthDp >= 480 -> Configuration.SCREENLAYOUT_SIZE_LARGE
        smallestWidthDp >= 320 -> Configuration.SCREENLAYOUT_SIZE_NORMAL
        else -> Configuration.SCREENLAYOUT_SIZE_SMALL
    }

    private companion object {
        const val FILE = "apphub"
        const val KEY_NAMES = "names"
        const val KEY_HIDDEN = "hidden_apps"
        const val KEY_ORDER = "page_order"
        const val KEY_FILTER = "shortcuts_filter"
        const val KEY_ICON_SIZE = "icon_size"
        const val KEY_SHAPE = "icon_shape"
        const val KEY_COLUMNS = "columns"
        const val KEY_SYSTEM = "system_apps"
        const val KEY_FILES = "file_manager"
        const val KEY_BROWSER = "web_browser"
        const val KEY_SORT = "sort"
        const val KEY_THEME = "theme"
        const val KEY_DIRECTION = "direction"
        const val KEY_ADAPT = "adapt_screen"
        const val KEY_DOTS_OFFER = "dots_offer_dismissed"
        const val KEY_TASKS_OFFER = "tasks_offer_dismissed"
        const val KEY_PENDING_UPDATE = "pending_update"

        /** the width in dp a phone has, which is what the screen is measured against */
        const val REFERENCE_WIDTH_DP = 400f

        /** how far the adaptation may go, so a very wide panel is not a phone */
        const val MAX_SCALE = 2f

        val SETTING_KEYS = listOf(
            KEY_NAMES, KEY_HIDDEN, KEY_ORDER, KEY_FILTER, KEY_ICON_SIZE,
            KEY_SHAPE, KEY_COLUMNS, KEY_SYSTEM, KEY_FILES, KEY_BROWSER, KEY_SORT,
            KEY_THEME, KEY_DIRECTION, KEY_ADAPT, KEY_DOTS_OFFER, KEY_TASKS_OFFER,
        ) + Margin.values().map { it.id }
    }
}
