package com.mahdi.apphub

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import kotlin.math.min

/**
 * One app's window rectangle: how far the app's own window is pushed in from
 * each screen edge, in pixels.
 *
 * Pixels rather than dp, unlike the screen margins in [Prefs]: these four
 * numbers are not padding, they are the bounds a task is given, and the window
 * manager measures those in pixels. A margin of 200 here is 200 screen pixels
 * on every unit, whatever its density - which is also how the example that
 * started this feature is written ("Left: 200px").
 *
 * The profile is off until the user turns it on, so an app with a stored but
 * disabled profile is left exactly as it is.
 */
data class WindowProfile(
    val enabled: Boolean = false,
    val left: Int = 0,
    val right: Int = 0,
    val top: Int = 0,
    val bottom: Int = 0,
) {

    /** The value of one edge, for the screen's rows and wheels. */
    fun margin(edge: Margin): Int = when (edge) {
        Margin.LEFT -> left
        Margin.RIGHT -> right
        Margin.TOP -> top
        Margin.BOTTOM -> bottom
    }

    /** A copy with one edge moved, which is all a turn of a wheel does. */
    fun withMargin(edge: Margin, px: Int): WindowProfile = when (edge) {
        Margin.LEFT -> copy(left = px)
        Margin.RIGHT -> copy(right = px)
        Margin.TOP -> copy(top = px)
        Margin.BOTTOM -> copy(bottom = px)
    }

    /**
     * The rectangle these four margins leave on a [screenWidth] x [screenHeight]
     * display.
     *
     * Each edge is capped at half its side - the same ceiling the screen margins
     * have, and for the same reason: two opposite edges at their maximum would
     * otherwise leave nothing to show. [minSide] is the smallest window the
     * window manager still honours (about 220 dp on Android 10); when the
     * margins would go below it, the far edges give way rather than the near
     * ones, so a rectangle that cannot be honoured is never asked for.
     */
    fun bounds(screenWidth: Int, screenHeight: Int, minSide: Int): Rect {
        val l = left.coerceIn(0, screenWidth / 2)
        val t = top.coerceIn(0, screenHeight / 2)
        val r = (screenWidth - right.coerceIn(0, screenWidth / 2)).coerceAtLeast(l + minSide)
        val b = (screenHeight - bottom.coerceIn(0, screenHeight / 2)).coerceAtLeast(t + minSide)
        return Rect(l, t, min(r, screenWidth), min(b, screenHeight))
    }

    /** The four numbers as one stored line, `enabled|left|right|top|bottom`. */
    fun encode(): String =
        listOf(if (enabled) 1 else 0, left, right, top, bottom).joinToString("|")

    companion object {
        /** null for a missing or unreadable line, so the caller keeps its default. */
        fun decode(stored: String?): WindowProfile? {
            val parts = stored?.split('|') ?: return null
            if (parts.size != 5) return null
            val values = parts.map { it.toIntOrNull() ?: return null }
            return WindowProfile(
                enabled = values[0] != 0,
                left = values[1],
                right = values[2],
                top = values[3],
                bottom = values[4],
            )
        }
    }
}

/**
 * Every app's window profile, in its own SharedPreferences file.
 *
 * A store of its own rather than more keys in [Prefs]: what lives here is per
 * package rather than per app, the file is keyed by package name, and *Reset
 * settings* then has nothing to accidentally throw away (like the pins, these
 * outlive a reset).
 *
 * Three of those packages are never given a profile, whatever the list offers:
 * App Hub itself, the launcher the unit is actually using, and SystemUI. The
 * first two are the apps that decide what is on screen - a rectangle around
 * either of them would be a rectangle around the controls - and the third is
 * the system's own furniture. The launcher is resolved at runtime rather than
 * matched against a list of names, because the factory app is not the only one
 * a unit may be running.
 */
class WindowProfiles(context: Context) {

    private val appContext = context.applicationContext

    private val prefs = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The master switch: off and nothing is watched, whatever the profiles say. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** The profile of one app; off and all-zero until one is stored. */
    fun profile(packageName: String): WindowProfile =
        WindowProfile.decode(prefs.getString(packageName, null)) ?: WindowProfile()

    fun save(packageName: String, profile: WindowProfile) {
        prefs.edit().putString(packageName, profile.encode()).apply()
    }

    /**
     * One edge of one app's rectangle: clamped, written, and turned on in one go.
     *
     * The value is clamped here rather than refused, exactly like the screen
     * margins: a typed 9999 lands on the limit instead of disappearing.
     */
    fun setMargin(packageName: String, edge: Margin, px: Int): WindowProfile {
        // Configuring an app is what turning its profile on means, so a margin
        // is never written into a profile that would then do nothing.
        val next = profile(packageName)
            .withMargin(edge, px.coerceIn(0, maxMargin(edge)))
            .copy(enabled = true)
        save(packageName, next)
        return next
    }

    /**
     * The largest value one edge may take: half of that side of the screen.
     *
     * Half rather than the whole side for the same reason the screen margins use
     * half: two opposite edges at their maximum would otherwise leave nothing of
     * the app to show. It comes from the live display, so the ceiling is the
     * screen the unit actually has.
     */
    fun maxMargin(edge: Margin): Int {
        val screen = WindowControl.screenSize(appContext)
        return if (edge == Margin.LEFT || edge == Margin.RIGHT) screen.x / 2 else screen.y / 2
    }

    /** The profile's own switch - the only way it is turned off again. */
    fun setEnabled(packageName: String, enabled: Boolean): WindowProfile {
        val next = profile(packageName).copy(enabled = enabled)
        save(packageName, next)
        return next
    }

    /** Every package with a stored profile, enabled or not. */
    fun configured(): List<String> = prefs.all.keys.filter { it != KEY_ENABLED }

    /** The packages the watcher has to look after: enabled, and not protected. */
    fun watched(): List<String> = configured().filter { profile(it).enabled && !isProtected(it) }

    /** The apps whose window is never touched, whatever the user asks. */
    fun isProtected(packageName: String): Boolean =
        packageName == appContext.packageName ||
            packageName == home ||
            packageName in SYSTEM_PACKAGES

    /**
     * The launcher the unit is actually using, whatever it is called.
     *
     * Resolved once and kept: the watcher asks this on every poll, and the
     * answer cannot change under it (a launcher that is being replaced is a
     * reboot away, at which point the watcher starts over anyway).
     */
    private val home: String? by lazy {
        try {
            appContext.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo?.packageName
        } catch (t: Throwable) {
            null
        }
    }

    /** A name for a package, for the status line: the app's label where known. */
    fun label(packageName: String): String = try {
        val info = appContext.packageManager.getApplicationInfo(packageName, 0)
        appContext.packageManager.getApplicationLabel(info).toString()
    } catch (t: Throwable) {
        packageName
    }

    private companion object {
        const val FILE = "window_profiles"
        const val KEY_ENABLED = "enabled"

        /** never given a rectangle - see the class comment */
        val SYSTEM_PACKAGES = setOf("com.android.systemui")
    }
}
