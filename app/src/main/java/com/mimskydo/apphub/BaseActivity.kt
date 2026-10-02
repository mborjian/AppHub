package com.mimskydo.apphub

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.roundToInt

abstract class BaseActivity : AppCompatActivity() {

    private var appliedScale = 1f

    override fun attachBaseContext(newBase: Context) {
        val prefs = Prefs(newBase)
        appliedScale = prefs.screenScale
        super.attachBaseContext(prefs.layoutContext(newBase))
    }

    override fun onResume() {
        super.onResume()
        if (appliedScale != Prefs(this).screenScale) recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(Prefs(this).theme.nightMode)
        super.onCreate(savedInstanceState)
    }

    protected fun applyMargins() {
        val content = findViewById<View>(android.R.id.content)
        val root = (content as? ViewGroup)?.getChildAt(0) ?: return
        val prefs = Prefs(this)
        val m = prefs.margins
        val density = prefs.deviceDensity
        root.setPadding(
            (m.left * density).roundToInt(),
            (m.top * density).roundToInt(),
            (m.right * density).roundToInt(),
            (m.bottom * density).roundToInt(),
        )
    }
}
