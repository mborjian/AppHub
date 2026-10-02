package com.mimskydo.apphub

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale
import kotlin.math.roundToInt

enum class ThemeMode(val id: String, val label: Int, val nightMode: Int) {
    SYSTEM("system", R.string.theme_system, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT("light", R.string.theme_light, AppCompatDelegate.MODE_NIGHT_NO),
    DARK("dark", R.string.theme_dark, AppCompatDelegate.MODE_NIGHT_YES),
    ;

    companion object {
        fun of(id: String?): ThemeMode = values().firstOrNull { it.id == id } ?: SYSTEM
    }
}

enum class Direction(val id: String, val label: Int) {
    SYSTEM("system", R.string.direction_system),
    RTL("rtl", R.string.direction_rtl),
    LTR("ltr", R.string.direction_ltr),
    ;

    companion object {
        fun of(id: String?): Direction = values().firstOrNull { it.id == id } ?: SYSTEM
    }
}

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

enum class Margin(val id: String, val label: Int) {
    LEFT("margin_left", R.string.margin_left),
    RIGHT("margin_right", R.string.margin_right),
    TOP("margin_top", R.string.margin_top),
    BOTTOM("margin_bottom", R.string.margin_bottom),
}

data class Margins(val left: Int, val right: Int, val top: Int, val bottom: Int)

class Prefs(context: Context) {

    private val appContext = context.applicationContext

    private val prefs = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var showNames: Boolean
        get() = prefs.getBoolean(KEY_NAMES, true)
        set(value) = prefs.edit().putBoolean(KEY_NAMES, value).apply()

    fun isHidden(packageName: String): Boolean = hiddenSet().contains(packageName)

    fun setHidden(packageName: String, hidden: Boolean) {
        val next = HashSet(hiddenSet())
        if (hidden) next.add(packageName) else next.remove(packageName)
        prefs.edit().putStringSet(KEY_HIDDEN, next).apply()
    }

    fun setHiddenAll(packageNames: Collection<String>, hidden: Boolean) {
        val next = HashSet(hiddenSet())
        if (hidden) next.addAll(packageNames) else next.removeAll(packageNames.toSet())
        prefs.edit().putStringSet(KEY_HIDDEN, next).apply()
    }

    private fun hiddenSet(): Set<String> = prefs.getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet()

    var pageOrder: List<String>
        get() = (prefs.getString(KEY_ORDER, "") ?: "").split('\n').filter { it.isNotEmpty() }
        set(value) {
            val editor = prefs.edit()
            if (value.isEmpty()) {
                editor.remove(KEY_ORDER)
            } else {
                editor.putString(KEY_ORDER, value.joinToString("\n"))
            }
            editor.apply()
        }

    var shortcutsFilter: String
        get() = prefs.getString(KEY_FILTER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_FILTER, value).apply()

    var iconSize: IconSize
        get() = IconSize.of(prefs.getString(KEY_ICON_SIZE, null))
        set(value) = prefs.edit().putString(KEY_ICON_SIZE, value.name).apply()

    var iconShape: IconShape
        get() = IconShape.of(prefs.getString(KEY_SHAPE, null))
        set(value) = prefs.edit().putString(KEY_SHAPE, value.name).apply()

    var columns: Int
        get() = prefs.getInt(KEY_COLUMNS, 0)
        set(value) = prefs.edit().putInt(KEY_COLUMNS, value).apply()

    var includeSystem: Boolean
        get() = prefs.getBoolean(KEY_SYSTEM, false)
        set(value) = prefs.edit().putBoolean(KEY_SYSTEM, value).apply()

    var showFileManager: Boolean
        get() = prefs.getBoolean(KEY_FILES, true)
        set(value) = prefs.edit().putBoolean(KEY_FILES, value).apply()

    var showBrowser: Boolean
        get() = prefs.getBoolean(KEY_BROWSER, true)
        set(value) = prefs.edit().putBoolean(KEY_BROWSER, value).apply()

    var dotsOfferDismissed: Boolean
        get() = prefs.getBoolean(KEY_DOTS_OFFER, false)
        set(value) = prefs.edit().putBoolean(KEY_DOTS_OFFER, value).apply()

    var pendingUpdate: String?
        get() = prefs.getString(KEY_PENDING_UPDATE, null)
        set(value) {
            val editor = prefs.edit()
            if (value == null) editor.remove(KEY_PENDING_UPDATE) else editor.putString(KEY_PENDING_UPDATE, value)
            editor.apply()
        }

    var developerMode: Boolean
        get() = prefs.getBoolean(KEY_DEVELOPER, false)
        set(value) = prefs.edit().putBoolean(KEY_DEVELOPER, value).apply()

    var logLevel: LogLevel
        get() = LogLevel.of(prefs.getString(KEY_LOG_LEVEL, null))
        set(value) = prefs.edit().putString(KEY_LOG_LEVEL, value.name).apply()

    var adaptToScreen: Boolean
        get() = prefs.getBoolean(KEY_ADAPT, false)
        set(value) = prefs.edit().putBoolean(KEY_ADAPT, value).apply()

    val deviceWidthDp: Int
        get() {
            val config = appContext.resources.configuration
            if (config.screenWidthDp > 0) return config.screenWidthDp

            val metrics = appContext.resources.displayMetrics
            return if (metrics.density > 0f) (metrics.widthPixels / metrics.density).toInt()
            else metrics.widthPixels
        }

    val screenScale: Float
        get() {
            if (!adaptToScreen) return 1f
            val widthDp = deviceWidthDp
            if (widthDp <= 0) return 1f
            return (widthDp / REFERENCE_WIDTH_DP).coerceIn(1f, MAX_SCALE)
        }

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

    fun margin(edge: Margin): Int = prefs.getInt(edge.id, 0).coerceIn(0, maxMargin(edge))

    fun setMargin(edge: Margin, dp: Int) {
        prefs.edit().putInt(edge.id, dp.coerceIn(0, maxMargin(edge))).apply()
    }

    fun maxMargin(edge: Margin): Int {
        val horizontal = edge == Margin.LEFT || edge == Margin.RIGHT
        val config = appContext.resources.configuration
        val screenDp = if (horizontal) config.screenWidthDp else config.screenHeightDp
        if (screenDp > 0) return screenDp / 2

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

    fun columnCount(screenWidthDp: Int): Int {
        if (columns > 0) return columns
        if (screenWidthDp <= 0) return 3
        val m = margins
        val usable = screenWidthDp - m.left - m.right
        return maxOf(2, usable / (iconSize.dp + 50))
    }

    fun reset() {
        val editor = prefs.edit()
        for (key in SETTING_KEYS) editor.remove(key)
        editor.apply()
    }

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

    private fun adaptDensity(config: Configuration, baseDpi: Int): Boolean {
        val scale = screenScale
        if (scale <= 1f) return false

        val dpi = if (config.densityDpi > 0) config.densityDpi else baseDpi
        config.densityDpi = (dpi * scale).roundToInt()

        config.screenWidthDp = denserDp(config.screenWidthDp, scale)
        config.screenHeightDp = denserDp(config.screenHeightDp, scale)
        val smallest = denserDp(config.smallestScreenWidthDp, scale)
        config.smallestScreenWidthDp = smallest

        if (smallest > 0) {
            val cleared = config.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK.inv()
            config.screenLayout = cleared or sizeClass(smallest)
        }
        return true
    }

    private fun denserDp(dp: Int, scale: Float): Int =
        if (dp <= 0) dp else (dp / scale).roundToInt()

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
        const val KEY_PENDING_UPDATE = "pending_update"
        const val KEY_DEVELOPER = "developer_mode"
        const val KEY_LOG_LEVEL = "log_level"

        const val REFERENCE_WIDTH_DP = 400f

        const val MAX_SCALE = 2f

        val SETTING_KEYS = listOf(
            KEY_NAMES, KEY_HIDDEN, KEY_ORDER, KEY_FILTER, KEY_ICON_SIZE,
            KEY_SHAPE, KEY_COLUMNS, KEY_SYSTEM, KEY_FILES, KEY_BROWSER, KEY_SORT,
            KEY_THEME, KEY_DIRECTION, KEY_ADAPT, KEY_DOTS_OFFER,
        ) + Margin.values().map { it.id }
    }
}
