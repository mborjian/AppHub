package com.mimskydo.apphub

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.isVisible
import java.util.concurrent.Executors
import kotlin.math.abs

class DeveloperActivity : BaseActivity() {

    private val prefs by lazy { Prefs(this) }

    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var rows: LinearLayout

    private val valueRows = ArrayList<Pair<View, () -> CharSequence?>>()

    private var tlsValue: CharSequence? = null

    private var clockValue: CharSequence? = null

    private var deviceValue: CharSequence? = null

    private var checking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_developer)

        rows = findViewById(R.id.developerRows)
        findViewById<TextView>(R.id.headerTitle).setText(R.string.developer_title)
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }

        applyMargins()
        buildRows()
        refresh()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun buildRows() {
        addSection(R.string.section_developer)

        addSwitchRow(
            R.string.dev_mode_title, R.string.dev_mode_subtitle,
            value = { prefs.developerMode },
        ) { on ->
            prefs.developerMode = on
            if (!on) {
                toast(getString(R.string.dev_mode_off))
                finish()
            }
        }

        addActionRow(R.string.dev_logs_title, R.string.dev_logs_subtitle) {
            startActivity(Intent(this, LogsActivity::class.java))
        }

        addValueRow(
            R.string.dev_tls_title, R.string.dev_tls_subtitle,
            value = { tlsValue },
        ) { runTls() }

        addValueRow(
            R.string.dev_clock_title, R.string.dev_clock_subtitle,
            value = { clockValue },
        ) { runClock() }

        addValueRow(
            R.string.dev_device_title, R.string.dev_device_subtitle,
            value = { deviceValue },
        ) { runDevice() }

        addBackRow()
    }

    private fun runTls() {
        if (checking) return
        checking = true
        tlsValue = getString(R.string.dev_checking)
        refresh()
        worker.execute {
            val report = DevTools.tls(this)
            post {
                checking = false
                tlsValue = null
                refresh()
                showReport(R.string.dev_tls_title, report, rerun = { runTls() })
            }
        }
    }

    private fun runClock() {
        if (checking) return
        checking = true
        clockValue = getString(R.string.dev_checking)
        refresh()
        worker.execute {
            val clock = DevTools.clock(this)
            post {
                checking = false
                clockValue = clock.skew?.let { DevTools.skewText(it) }
                refresh()
                showClock(clock)
            }
        }
    }

    private fun runDevice() {
        if (checking) return
        checking = true
        deviceValue = getString(R.string.dev_checking)
        refresh()
        worker.execute {
            val report = DevTools.device(this)
            post {
                checking = false
                deviceValue = null
                refresh()
                showReport(R.string.dev_device_title, report)
            }
        }
    }

    private fun showClock(clock: DevTools.Clock) {
        val message = StringBuilder(DevTools.clockText(clock))
        val skew = clock.skew
        if (skew != null && abs(skew) > SKEW_ALARM) {
            message.append("\n\n").append(getString(R.string.dev_clock_advice))
        }

        val sheetRows = ArrayList<SheetRow>(5)
        if (!clock.autoTime) {
            sheetRows += SheetRow(
                label = getString(R.string.dev_auto_time_enable),
                onClick = { enableAutoTime() },
            )
        }
        sheetRows += SheetRow(
            label = getString(R.string.dev_date_settings),
            onClick = { openDateSettings() },
        )
        sheetRows += SheetRow(
            label = getString(R.string.dev_copy),
            onClick = { copy(message.toString()) },
        )
        sheetRows += SheetRow(
            label = getString(R.string.dev_run_again),
            onClick = { runClock() },
        )
        sheetRows += SheetRow(label = getString(android.R.string.cancel))

        Sheet.show(
            context = this,
            title = getString(R.string.dev_clock_title),
            message = message.toString(),
            rows = sheetRows,
        )
    }

    private fun showReport(titleRes: Int, report: String, rerun: (() -> Unit)? = null) {
        val sheetRows = ArrayList<SheetRow>(3)
        if (rerun != null) {
            sheetRows += SheetRow(label = getString(R.string.dev_run_again), onClick = rerun)
        }
        sheetRows += SheetRow(
            label = getString(R.string.dev_copy),
            onClick = { copy(report) },
        )
        sheetRows += SheetRow(label = getString(android.R.string.cancel))

        Sheet.show(
            context = this,
            title = getString(titleRes),
            message = report,
            rows = sheetRows,
        )
    }

    private fun enableAutoTime() {
        worker.execute {
            val on = DevTools.enableAutoTime(this)
            post {
                toast(getString(if (on) R.string.dev_auto_time_done else R.string.dev_auto_time_refused))
                if (on) runClock()
            }
        }
    }

    private fun openDateSettings() {
        try {
            startActivity(Intent(Settings.ACTION_DATE_SETTINGS))
        } catch (t: Throwable) {
            toast(getString(R.string.settings_unavailable))
        }
    }

    private fun copy(report: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.developer_title), report))
        toast(getString(R.string.dev_copied))
    }

    private fun addSection(titleRes: Int) {
        val view = LayoutInflater.from(this).inflate(R.layout.row_section, rows, false)
        view.findViewById<TextView>(R.id.sectionTitle).setText(titleRes)
        rows.addView(view)
    }

    private fun addSwitchRow(
        titleRes: Int,
        subtitleRes: Int,
        value: () -> Boolean,
        onChange: (Boolean) -> Unit,
    ) {
        val row = inflateRow(titleRes, subtitleRes)
        val switch = row.findViewById<SwitchCompat>(R.id.settingSwitch)
        switch.isVisible = true
        switch.isChecked = value()
        switch.setOnCheckedChangeListener { _, checked ->
            if (checked != value()) onChange(checked)
        }
        row.setOnClickListener { switch.toggle() }
        rows.addView(row)
    }

    private fun addValueRow(
        titleRes: Int,
        subtitleRes: Int,
        value: () -> CharSequence?,
        onClick: () -> Unit,
    ) {
        val row = inflateRow(titleRes, subtitleRes)
        row.findViewById<TextView>(R.id.settingValue).isVisible = true
        row.findViewById<ImageView>(R.id.settingChevron).isVisible = true
        valueRows += row to value
        row.setOnClickListener { onClick() }
        rows.addView(row)
    }

    private fun addActionRow(
        titleRes: Int,
        subtitleRes: Int,
        onClick: () -> Unit,
    ) {
        val row = inflateRow(titleRes, subtitleRes)
        row.findViewById<ImageView>(R.id.settingChevron).isVisible = true
        row.setOnClickListener { onClick() }
        rows.addView(row)
    }

    private fun addBackRow() {
        val row = inflateRow(R.string.settings_back, R.string.set_back_subtitle)
        row.findViewById<ImageView>(R.id.settingChevron).apply {
            isVisible = true
            rotation = 180f
        }
        row.setOnClickListener { finish() }
        rows.addView(row)
    }

    private fun inflateRow(titleRes: Int, subtitleRes: Int): View {
        val row = LayoutInflater.from(this).inflate(R.layout.row_setting, rows, false)
        row.findViewById<TextView>(R.id.settingTitle).setText(titleRes)
        row.findViewById<TextView>(R.id.settingSubtitle).apply {
            setText(subtitleRes)
            isVisible = true
        }
        return row
    }

    private fun refresh() {
        valueRows.forEach { (row, value) ->
            val text = value()
            val view = row.findViewById<TextView>(R.id.settingValue)
            view.text = text ?: ""
            view.isVisible = text != null
        }
    }

    private fun post(block: () -> Unit) = runOnUiThread {
        if (!isFinishing && !isDestroyed) block()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val SKEW_ALARM = 5 * 60 * 1000L
    }
}
