package com.mahdi.apphub

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.roundToInt

/**
 * Applies the settings that have to be in place before any view is inflated -
 * the day/night mode and the (optionally forced) layout direction - and the
 * one that is applied right after: the screen margins.
 */
abstract class BaseActivity : AppCompatActivity() {

    /**
     * The scale this screen was built at, see [Prefs.screenScale].
     *
     * A density cannot be changed on a screen that already exists, so it is
     * remembered here to notice when it is no longer the one the settings ask
     * for - see [onResume].
     */
    private var appliedScale = 1f

    override fun attachBaseContext(newBase: Context) {
        val prefs = Prefs(newBase)
        appliedScale = prefs.screenScale
        super.attachBaseContext(prefs.layoutContext(newBase))
    }

    override fun onResume() {
        super.onResume()
        // Every screen is built in the dp space the settings described when it
        // was created, so one that is already up has to be built again when the
        // *Adapt to screen* switch is flipped somewhere else. That is the
        // ordinary case, not an edge one: the grid is sitting behind the
        // settings screen while the switch is turned on.
        if (appliedScale != Prefs(this).screenScale) recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate so the very first window is already correct.
        AppCompatDelegate.setDefaultNightMode(Prefs(this).theme.nightMode)
        super.onCreate(savedInstanceState)
    }

    /**
     * Pads this activity's content by the configured margins.
     *
     * Every screen calls it right after `setContentView`, and the settings
     * screen calls it again whenever a margin is changed - which is why the
     * result of a change is visible there while it is being made: the whole
     * screen (header included) moves, exactly as the grid will.
     *
     * The margin band shows the window background, which is the same colour as
     * the layouts themselves, so no seam appears when the values grow.
     */
    protected fun applyMargins() {
        val content = findViewById<View>(android.R.id.content)
        val root = (content as? ViewGroup)?.getChildAt(0) ?: return
        val prefs = Prefs(this)
        val m = prefs.margins
        // The unit's own density, not the adapted one: a margin holds off an
        // overlay the *unit* draws at its own size, and that overlay does not
        // grow when this app's icons do. A stored dp therefore keeps meaning
        // the same pixels whichever way *Adapt to screen* is set.
        val density = prefs.deviceDensity
        root.setPadding(
            (m.left * density).roundToInt(),
            (m.top * density).roundToInt(),
            (m.right * density).roundToInt(),
            (m.bottom * density).roundToInt(),
        )
    }
}
