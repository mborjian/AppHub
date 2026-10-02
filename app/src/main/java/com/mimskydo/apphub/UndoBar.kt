package com.mimskydo.apphub

import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible

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

    val isVisible: Boolean get() = root.isVisible

    fun runUndo() {
        dismiss()
        onUndo()
    }
}
