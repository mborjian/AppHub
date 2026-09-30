package com.mahdi.apphub

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
)
