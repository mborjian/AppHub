package com.mahdi.apphub

import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible

/**
 * The undo bar: the app's answer to its own toasts.
 *
 * Hiding a tile, closing an app and pinning one are all reversible, and the app
 * used to say so with a toast that could not be acted on - which told the driver
 * what happened and then left them to find the way back themselves. This is the
 * same message with the way back attached, on the surface the action happened
 * to, holding for five seconds.
 *
 * It is the one inverted surface in the app (dark on a light board), because it
 * floats over the board and is the only thing allowed to.
 */
class UndoBar(private val root: View, private val onUndo: () -> Unit) {

    private val text: TextView = root.findViewById(R.id.undoText)
    private val action: TextView = root.findViewById(R.id.undoAction)

    private val timeout = Motion.duration(root.context, R.integer.undo_timeout)

    private val hide = Runnable { animateOut() }

    init {
        action.setOnClickListener { runUndo() }
    }

    fun show(message: CharSequence) {
        text.text = message
        root.removeCallbacks(hide)
        if (root.isVisible) {
            // already up: the message changes in place rather than the bar
            // bouncing out and back in on a second tap
            return
        }
        root.isVisible = true
        root.alpha = 0f
        root.translationY = root.resources.getDimension(R.dimen.space_4)
        root.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(Motion.duration(root.context, R.integer.motion_deliberate))
            .setInterpolator(Motion.emphasized())
            .start()
        // a bar that outlives its moment is a bar that has to be dismissed
        root.announceForAccessibility(message)
        root.postDelayed(hide, timeout)
    }

    fun dismiss() {
        root.removeCallbacks(hide)
        animateOut()
    }

    private fun animateOut() {
        if (!root.isVisible) return
        root.animate()
            .alpha(0f)
            .translationY(root.resources.getDimension(R.dimen.space_2))
            .setDuration(Motion.duration(root.context, R.integer.motion_medium))
            .setInterpolator(Motion.exit())
            .withEndAction { root.isVisible = false }
            .start()
    }

    /** true while the bar is up, so a caller can skip re-announcing itself */
    val isVisible: Boolean get() = root.isVisible

    fun runUndo() {
        dismiss()
        onUndo()
    }
}
