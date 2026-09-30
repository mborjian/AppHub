package com.mimskydo.apphub

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.isVisible
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Every option the grid has, on one screen.
 *
 * Rows are built by the `add*Row` helpers rather than declared in XML: a
 * screenful of copies of the same twenty-line block is how these screens drift
 * apart.
 */
class SettingsActivity : BaseActivity() {

    private val prefs by lazy { Prefs(this) }
    private val windowProfiles by lazy { WindowProfiles(this) }

    /** reads the app list off the main thread, like the grid does */
    private val worker = Executors.newSingleThreadExecutor()

    /**
     * The apps the main page could show, or null while they are still being
     * read - the shortcuts row's value is built from it.
     */
    private var apps: List<AppEntry>? = null

    /** true while [refresh] writes the stored values into the rows */
    private var refreshing = false

    private lateinit var rows: LinearLayout

    /** rows whose right-hand text is recomputed from [Prefs] */
    private val valueRows = ArrayList<Pair<View, () -> CharSequence>>()

    /** switches whose position is recomputed from [Prefs] as well */
    private val switchRows = ArrayList<Pair<View, () -> Boolean>>()

    private lateinit var usageRow: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        rows = findViewById(R.id.settingsRows)
        findViewById<TextView>(R.id.headerTitle).setText(R.string.settings_title)
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }

        applyMargins()
        buildRows()
        refresh()
        loadApps()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    /**
     * After a recreation (theme, direction and "reset" all recreate this screen)
     * the framework puts the switches back where they were, and those are the
     * *old* positions - after a reset, exactly the values that were just cleared.
     * Restoring them would fire the listeners and undo the reset, so this window
     * is treated like [refresh]: the change is seen and nothing is written. The
     * rows also share one switch id, so a single saved position is restored into
     * every one of them, which is a second reason not to trust it.
     *
     * The stored values win a moment later: [onResume] re-reads them.
     */
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        refreshing = true
        super.onRestoreInstanceState(savedInstanceState)
        refreshing = false
    }

    override fun onResume() {
        super.onResume()
        // usage access is granted on a system screen, so the row can only be
        // decided once we are back here
        refresh()
    }

    /**
     * Reads the installed apps, which is the list the grid draws from: the
     * shortcuts screen has to offer exactly what the grid could show. Skipped
     * once the list is in hand - the same read is what the grid does on every
     * resume.
     */
    private fun loadApps() {
        if (apps != null) return
        val context = applicationContext
        val includeSystem = prefs.includeSystem
        try {
            worker.execute {
                val list = try {
                    AppRepository(context).loadApps(includeSystem)
                } catch (t: Throwable) {
                    emptyList()
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    apps = list
                    refresh()
                }
            }
        } catch (t: Throwable) {
            // the executor was already shut down while finishing
        }
    }

    // ----------------------------------------------------------------- rows

    /**
     * The screen, in four groups: what the board looks like, where its things
     * go, what this app is allowed to know about the unit, and the rows that
     * change something rather than display something.
     *
     * Twenty-one identical rows in one column is a list to be read; four named
     * groups is a screen to be scanned.
     */
    private fun buildRows() {
        addSection(R.string.section_look)

        addSwitchRow(
            R.string.set_names_title, R.string.set_names_subtitle,
            value = { prefs.showNames },
        ) { prefs.showNames = it }

        addChoiceRow(
            R.string.set_size_title, R.string.set_size_subtitle,
            value = { getString(prefs.iconSize.label) },
            options = IconSize.values().toList(),
            label = { getString(it.label) },
            selected = { prefs.iconSize == it },
            apply = { prefs.iconSize = it },
        )

        // Right under the icon size, because it is the other half of the same
        // question - but not one of its four values: this one is about the
        // screen, and it moves the text and the spacing with the icons.
        addSwitchRow(
            R.string.set_adapt_title, R.string.set_adapt_subtitle,
            value = { prefs.adaptToScreen },
            note = { scaleValue() },
        ) {
            prefs.adaptToScreen = it
            // A density is applied before the first view is inflated, so this
            // screen has to be built again for its own result to show - which
            // is also the point: the answer to the switch is the screen it is
            // on, not a sentence about it.
            recreate()
        }

        addChoiceRow(
            R.string.set_shape_title, R.string.set_shape_subtitle,
            value = { getString(prefs.iconShape.label) },
            options = IconShape.values().toList(),
            label = { getString(it.label) },
            selected = { prefs.iconShape == it },
            apply = { prefs.iconShape = it },
        )

        addChoiceRow(
            R.string.set_theme_title, R.string.set_theme_subtitle,
            value = { getString(prefs.theme.label) },
            options = ThemeMode.values().toList(),
            label = { getString(it.label) },
            selected = { prefs.theme == it },
            apply = {
                prefs.theme = it
                AppCompatDelegate.setDefaultNightMode(it.nightMode)
                recreate()
            },
        )

        addSection(R.string.section_layout)

        addValueRow(
            R.string.set_shortcuts_title, R.string.set_shortcuts_subtitle,
            value = { shortcutsValue() },
        ) { openShortcuts() }

        addChoiceRow(
            R.string.set_grid_title, R.string.set_grid_subtitle,
            value = { gridLabel(prefs.columns) },
            options = COLUMN_OPTIONS,
            label = { gridLabel(it) },
            selected = { prefs.columns == it },
            apply = { prefs.columns = it },
        )

        addChoiceRow(
            R.string.set_sort_title, R.string.set_sort_subtitle,
            value = { getString(prefs.sortOrder.label) },
            options = SortOrder.values().toList(),
            label = { getString(it.label) },
            selected = { prefs.sortOrder == it },
            apply = {
                prefs.sortOrder = it
                // A hand-arranged page and a sort cannot both decide where a
                // tile goes, and asking for a sort is asking for the page to be
                // sorted again: the arrangement is dropped rather than left to
                // win silently, which would make this row look broken.
                prefs.pageOrder = emptyList()
            },
        )

        addChoiceRow(
            R.string.set_direction_title, R.string.set_direction_subtitle,
            value = { getString(prefs.direction.label) },
            options = Direction.values().toList(),
            label = { getString(it.label) },
            selected = { prefs.direction == it },
            apply = {
                prefs.direction = it
                recreate()
            },
        )

        addValueRow(
            R.string.set_margins_title, R.string.set_margins_subtitle,
            value = { marginsValue() },
        ) { openMarginsSheet() }

        addSection(R.string.section_behaviour)

        addSwitchRow(
            R.string.set_system_title, R.string.set_system_subtitle,
            value = { prefs.includeSystem },
        ) {
            prefs.includeSystem = it
            // the factory apps join or leave the grid with this switch, so the
            // shortcuts list has to be read again
            apps = null
            loadApps()
        }

        addValueRow(
            R.string.set_window_title, R.string.set_window_subtitle,
            value = { windowMarginsValue() },
        ) { openWindowMargins() }

        // This row disappears once the access is granted - it is an offer, not
        // a status line.
        usageRow = addActionRow(
            R.string.set_usage_title, R.string.set_usage_subtitle,
            action = getString(R.string.enable),
        ) { openUsageAccess() }

        addSection(R.string.section_maintenance)

        addActionRow(R.string.set_reset_title, R.string.set_reset_subtitle) { confirmReset() }

        addBackRow()
    }

    /** the name of a group, above the rows it holds */
    private fun addSection(titleRes: Int) {
        val view = LayoutInflater.from(this).inflate(R.layout.row_section, rows, false)
        view.findViewById<TextView>(R.id.sectionTitle).setText(titleRes)
        rows.addView(view)
    }

    private fun addSwitchRow(
        titleRes: Int,
        subtitleRes: Int,
        value: () -> Boolean,
        note: (() -> CharSequence)? = null,
        onChange: (Boolean) -> Unit,
    ) {
        val row = inflateRow(titleRes, subtitleRes)
        val switch = row.findViewById<SwitchCompat>(R.id.settingSwitch)
        switch.isVisible = true
        switch.isChecked = value()
        switch.setOnCheckedChangeListener { _, checked ->
            // A change that matches what is already stored was not a decision:
            // it came from restoring or re-reading the screen, so it is not
            // written back.
            if (!refreshing && checked != value()) onChange(checked)
        }
        row.setOnClickListener { switch.toggle() }
        switchRows += row to value

        // A switch that has something to say about what it did - the adaptation
        // reports the factor it is drawing at - says it on the right, and is
        // then re-read with every other value on the screen.
        if (note != null) {
            row.findViewById<TextView>(R.id.settingValue).isVisible = true
            valueRows += row to note
        }
        rows.addView(row)
    }

    private fun <T> addChoiceRow(
        titleRes: Int,
        subtitleRes: Int,
        value: () -> CharSequence,
        options: List<T>,
        label: (T) -> CharSequence,
        selected: (T) -> Boolean,
        apply: (T) -> Unit,
    ) {
        val row = inflateRow(titleRes, subtitleRes)
        row.findViewById<TextView>(R.id.settingValue).isVisible = true
        row.findViewById<ImageView>(R.id.settingChevron).isVisible = true
        valueRows += row to value

        row.setOnClickListener {
            Sheet.show(
                this,
                title = row.findViewById<TextView>(R.id.settingTitle).text,
                rows = options.map { option ->
                    SheetRow(
                        label = label(option),
                        selected = selected(option),
                        // The value is written to the prefs, and this row's
                        // right-hand text is read *back* from them - so it has
                        // to be read back now. Waiting for the next resume meant
                        // a choice was only visible on the screen after leaving
                        // it and coming back, which reads as a setting that did
                        // not take.
                        onClick = {
                            apply(option)
                            refresh()
                        },
                    )
                },
            )
        }
        rows.addView(row)
    }

    /**
     * A row whose right-hand value is recomputed from [Prefs] and that opens
     * [onClick] instead of applying a choice itself - used by the margins row,
     * which leads to another sheet rather than to a value.
     */
    private fun addValueRow(
        titleRes: Int,
        subtitleRes: Int,
        value: () -> CharSequence,
        onClick: () -> Unit,
    ) {
        val row = inflateRow(titleRes, subtitleRes)
        row.findViewById<TextView>(R.id.settingValue).isVisible = true
        row.findViewById<ImageView>(R.id.settingChevron).isVisible = true
        valueRows += row to value
        row.setOnClickListener { onClick() }
        rows.addView(row)
    }

    /**
     * The last row of the screen: the same thing the arrow in the header does.
     *
     * Reaching the end of a list on a head unit should not mean aiming at a
     * small target in the corner, and the grid is where back is expected to
     * lead anyway.
     */
    private fun addBackRow() {
        val row = inflateRow(R.string.settings_back, R.string.set_back_subtitle)
        row.findViewById<ImageView>(R.id.settingChevron).apply {
            isVisible = true
            rotation = 180f                  // points back, like the header arrow
        }
        row.setOnClickListener { finish() }
        rows.addView(row)
    }

    private fun addActionRow(
        titleRes: Int,
        subtitleRes: Int,
        action: String? = null,
        onClick: () -> Unit,
    ): View {
        val row = inflateRow(titleRes, subtitleRes)
        row.findViewById<ImageView>(R.id.settingChevron).isVisible = true
        if (action != null) {
            val value = row.findViewById<TextView>(R.id.settingValue)
            value.isVisible = true
            value.text = action
        }
        row.setOnClickListener { onClick() }
        rows.addView(row)
        return row
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

    // -------------------------------------------------------------- refresh

    private fun refresh() {
        refreshing = true
        valueRows.forEach { (row, value) ->
            row.findViewById<TextView>(R.id.settingValue).text = value()
        }
        switchRows.forEach { (row, value) ->
            row.findViewById<SwitchCompat>(R.id.settingSwitch).isChecked = value()
        }
        refreshing = false

        usageRow.isVisible = !ActivityStats.hasAccess(this)
    }

    /** "Auto (4 columns)" or "4 columns", from the stored option */
    private fun gridLabel(columns: Int): CharSequence = if (columns == 0) {
        getString(R.string.grid_auto, prefs.columnCount(resources.configuration.screenWidthDp))
    } else {
        getString(R.string.grid_fixed, columns)
    }

    /**
     * "×2": what *Adapt to screen* is doing, in one number.
     *
     * The row can then be read without leaving the screen to see the result,
     * and "×1" says what the switch being off means rather than leaving an
     * empty cell beside it.
     */
    private fun scaleValue(): CharSequence {
        val scale = prefs.screenScale
        val whole = scale.toInt().toFloat()
        val number = if (scale == whole) scale.toInt().toString()
        else String.format(Locale.US, "%.1f", scale)
        return getString(R.string.adapt_value, number)
    }

    // ------------------------------------------------------------ shortcuts

    /** How many apps the main page shows out of how many there are */
    private fun shortcutsValue(): CharSequence {
        val list = apps ?: return getString(R.string.loading)
        if (list.isEmpty()) return getString(R.string.none)
        val shown = list.count { !prefs.isHidden(it.packageName) }
        return if (shown == list.size) {
            getString(R.string.shortcuts_all, list.size)
        } else {
            getString(R.string.shortcuts_count, shown, list.size)
        }
    }

    /**
     * Shortcuts are not a picker but a screen of their own: the apps are
     * independent of each other, they are far too many for a menu, and the page
     * they add up to has to be visible while they are chosen - see
     * [ShortcutsActivity].
     *
     * This row only carries the result and stays a value row, so the count is
     * re-read on the way back ([onResume] -> [refresh]) exactly like the value
     * of any other setting.
     */
    private fun openShortcuts() {
        startActivity(Intent(this, ShortcutsActivity::class.java))
    }

    // -------------------------------------------------------------- margins

    /** "0 / 0 / 0 / 0 dp" - left / right / top / bottom, the order of the rows */
    private fun marginsValue(): CharSequence {
        val m = prefs.margins
        return getString(R.string.margins_value, m.left, m.right, m.top, m.bottom)
    }

    /** One row per edge, each showing the value it currently has. */
    private fun openMarginsSheet() {
        Sheet.show(
            this,
            title = getString(R.string.set_margins_title),
            subtitle = getString(R.string.set_margins_message),
            rows = Margin.values().map { edge ->
                SheetRow(
                    label = getString(edge.label),
                    subtitle = getString(R.string.margin_value, prefs.margin(edge)),
                    onClick = { openMarginWheel(edge) },
                )
            },
        )
    }

    /**
     * The wheel for one edge: every whole dp from 0 up to half of that side of
     * the screen, so a value can be dialled in rather than picked off a list -
     * or typed, which is the second row of this sheet.
     */
    private fun openMarginWheel(edge: Margin) {
        val max = prefs.maxMargin(edge)
        Sheet.showNumber(
            this,
            title = getString(edge.label),
            subtitle = getString(R.string.margin_range, max),
            minValue = 0,
            maxValue = max,
            value = prefs.margin(edge),
            unit = getString(R.string.margin_unit),
            done = getString(android.R.string.ok),
            extraRows = listOf(
                SheetRow(
                    label = getString(R.string.margin_type_title),
                    subtitle = getString(R.string.margin_type_subtitle),
                    onClick = { openMarginInput(edge, max) },
                ),
            ),
            onValue = { dp -> setMargin(edge, dp) },
        )
    }

    /**
     * The same edge, typed: quicker than a wheel once the number is known. The
     * value is clamped like any other, so an over-large entry lands on the
     * limit instead of being refused.
     */
    private fun openMarginInput(edge: Margin, max: Int) {
        Sheet.showInput(
            this,
            title = getString(edge.label),
            subtitle = getString(R.string.margin_range, max),
            value = prefs.margin(edge),
            unit = getString(R.string.margin_unit),
            submit = getString(android.R.string.ok),
            cancel = getString(android.R.string.cancel),
            onSubmit = { dp -> setMargin(edge, dp) },
        )
    }

    /**
     * Writes the value and shows the result at once: this screen is padded by
     * the same margins as the grid, so turning the wheel moves it immediately.
     * The sheet stays open while it does, which is what makes it a preview.
     */
    private fun setMargin(edge: Margin, dp: Int) {
        prefs.setMargin(edge, dp)
        applyMargins()
        refresh()
    }

    // ------------------------------------------------------- window margins

    /** "2 apps", or the switch's own state while the feature is off. */
    private fun windowMarginsValue(): CharSequence {
        if (!windowProfiles.enabled) return getString(R.string.window_off)
        val watched = windowProfiles.watched().size
        return if (watched == 0) getString(R.string.none)
        else resources.getQuantityString(R.plurals.window_count, watched, watched)
    }

    /**
     * The apps are picked and their four numbers set on a screen of their own,
     * the way the shortcuts are: this row carries the result, and it is re-read
     * on the way back ([onResume] -> [refresh]) like every other value here.
     */
    private fun openWindowMargins() {
        startActivity(Intent(this, WindowMarginsActivity::class.java))
    }

    // -------------------------------------------------------------- actions

    private fun openUsageAccess() {
        if (ActivityStats.openAccessSettings(this)) return
        toast(getString(R.string.settings_unavailable))
    }

    private fun confirmReset() {
        Sheet.show(
            this,
            title = getString(R.string.set_reset_title),
            subtitle = getString(R.string.set_reset_message),
            rows = listOf(
                SheetRow(
                    label = getString(R.string.set_reset_confirm),
                    danger = true,
                    onClick = {
                        prefs.reset()
                        AppCompatDelegate.setDefaultNightMode(prefs.theme.nightMode)
                        recreate()
                    },
                ),
                SheetRow(label = getString(android.R.string.cancel)),
            ),
        )
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /** 0 is "auto"; below 2 a grid is pointless */
        val COLUMN_OPTIONS = listOf(0, 2, 3, 4, 5, 6)
    }
}
