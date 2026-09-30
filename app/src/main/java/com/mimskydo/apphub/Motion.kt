package com.mimskydo.apphub

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.Interpolator
import androidx.annotation.IntegerRes
import androidx.core.view.animation.PathInterpolatorCompat

/**
 * Instrument: the app's motion vocabulary, in one place.
 *
 * Three curves, the same three the system uses. `standard` answers a touch,
 * `emphasized` arrives, `exit` leaves - and there is deliberately no fourth,
 * because two curves that differ by 20ms and 0.05 of control-point offset are a
 * detail nobody can see and everybody has to maintain.
 *
 * Durations come from `integers.xml` so a slowdown is one edit.
 *
 * The haptics are a courtesy, never a requirement: a head unit may have no
 * vibrator at all, and every one of these is paired with something visible.
 * `HapticFeedbackConstants.CONFIRM` is API 30 and this app runs on API 28, so it
 * is never used.
 */
object Motion {

    enum class Kind { HOLD, KEY }

    /** Things that answer a touch: ripples, value changes, a mark appearing. */
    fun standard(): Interpolator = PathInterpolatorCompat.create(0.2f, 0f, 0f, 1f)

    /** Things that arrive: sheets, screens, the board lifting. */
    fun emphasized(): Interpolator = PathInterpolatorCompat.create(0.05f, 0.7f, 0.1f, 1f)

    /** Things that leave. */
    fun exit(): Interpolator = PathInterpolatorCompat.create(0.3f, 0f, 1f, 1f)

    fun duration(context: Context, @IntegerRes token: Int): Long =
        context.resources.getInteger(token).toLong()

    fun tap(view: View, kind: Kind = Kind.KEY) {
        val constant = when (kind) {
            Kind.HOLD -> HapticFeedbackConstants.LONG_PRESS
            Kind.KEY -> HapticFeedbackConstants.KEYBOARD_TAP
        }
        view.performHapticFeedback(constant, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
    }
}
