package com.mimskydo.apphub

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import java.util.concurrent.Executors

class LogsActivity : BaseActivity() {

    private val prefs by lazy { Prefs(this) }

    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var scroller: ScrollView

    private lateinit var text: TextView

    private lateinit var levelValue: TextView

    private lateinit var trailing: TextView

    private var loading = false

    private var shown = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)

        scroller = findViewById(R.id.logsScroll)
        text = findViewById(R.id.logsText)
        levelValue = findViewById(R.id.logLevelValue)
        trailing = findViewById(R.id.headerTrailing)

        findViewById<TextView>(R.id.headerTitle).setText(R.string.logs_title)
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.logLevelRow).setOnClickListener { chooseLevel() }
        findViewById<TextView>(R.id.logsRefresh).setOnClickListener { read() }
        findViewById<TextView>(R.id.logsCopy).setOnClickListener { copy() }
        findViewById<TextView>(R.id.logsSave).setOnClickListener { save() }

        levelValue.text = getString(prefs.logLevel.label)

        applyMargins()
        read()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun chooseLevel() {
        Sheet.show(
            context = this,
            title = getString(R.string.logs_level_title),
            subtitle = getString(R.string.logs_level_subtitle),
            rows = LogLevel.values().map { level ->
                SheetRow(
                    label = getString(level.label),
                    selected = prefs.logLevel == level,
                    onClick = {
                        prefs.logLevel = level
                        levelValue.text = getString(level.label)
                        read()
                    },
                )
            },
        )
    }

    private fun read() {
        if (loading) return
        loading = true
        trailing.isVisible = false
        text.text = getString(R.string.logs_loading)
        worker.execute {
            val dump = Logcat.read(prefs.logLevel)
            post {
                loading = false
                show(dump)
            }
        }
    }

    private fun show(dump: Logcat.Dump) {
        shown = dump.text.ifBlank { getString(R.string.logs_empty) }
        text.text = shown
        trailing.isVisible = true
        trailing.text = getString(if (dump.root) R.string.logs_root else R.string.logs_app_only)
        scroller.post { scroller.fullScroll(View.FOCUS_DOWN) }
    }

    private fun copy() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.logs_title), shown))
        toast(getString(R.string.logs_copied))
    }

    private fun save() {
        val file = Logcat.save(this, shown)
        toast(
            if (file == null) getString(R.string.logs_save_refused)
            else getString(R.string.logs_saved, file.absolutePath)
        )
    }

    private fun post(block: () -> Unit) = runOnUiThread {
        if (!isFinishing && !isDestroyed) block()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }
}
