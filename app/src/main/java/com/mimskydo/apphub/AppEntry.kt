package com.mimskydo.apphub

import android.app.Activity
import android.graphics.drawable.Drawable
import java.util.Locale

enum class AppState {
    IDLE,

    RUNNING,

    OPEN,

    RECENT,
}

data class AppEntry(
    val label: String,
    val packageName: String,
    val activityName: String,
    val icon: Drawable,
    val pinned: Boolean = false,
    val state: AppState = AppState.IDLE,
    val system: Boolean = false,

    val tool: Tool? = null,
) {

    val key: String get() = tool?.key ?: packageName
}

enum class Tool(
    val icon: Int,
    val title: Int,
    val subtitle: Int,
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

    val key: String get() = "hub:" + name.lowercase(Locale.US)

    fun shown(prefs: Prefs): Boolean = when (this) {
        FILES -> prefs.showFileManager
        BROWSER -> prefs.showBrowser
    }

    fun show(prefs: Prefs, on: Boolean) {
        when (this) {
            FILES -> prefs.showFileManager = on
            BROWSER -> prefs.showBrowser = on
        }
    }
}
