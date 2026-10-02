package com.mimskydo.apphub

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.Interpolator
import androidx.annotation.IntegerRes
import androidx.core.view.animation.PathInterpolatorCompat

object Motion {

    enum class Kind { HOLD, KEY }

    fun standard(): Interpolator = PathInterpolatorCompat.create(0.2f, 0f, 0f, 1f)

    fun emphasized(): Interpolator = PathInterpolatorCompat.create(0.05f, 0.7f, 0.1f, 1f)

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
