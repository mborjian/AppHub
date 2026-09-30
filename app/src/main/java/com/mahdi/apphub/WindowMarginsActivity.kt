package com.mahdi.apphub

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.content.res.ColorStateList
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/**
 * Which apps get a window rectangle, and what that rectangle is.
 *
 * The list is the grid's own list - [AppRepository], read on a background
 * thread exactly like the main page reads it, respecting the same *Include
 * system apps* switch and the same sort - so there is no second idea of "the
 * installed apps" anywhere in the app.
 *
 * A row is tapped to edit the four numbers of that app's rectangle; the switch
 * on the row is the profile's own on/off. Editing a number turns the profile
 * on, because configuring an app is what turning its profile on means, and the
 * switch is the only way it goes off again (which leaves the numbers alone).
 *
 * The [WindowMarginService] does the actual work; this screen only writes
 * profiles and reports what the unit made of them. Three apps never appear
 * configurable - App Hub itself, the launcher the unit is actually running, and
 * SystemUI - and the row says so rather than leaving a switch that would do
 * nothing.
 */
class WindowMarginsActivity : BaseActivity() {

    private val prefs by lazy { Prefs(this) }
    private val profiles by lazy { WindowProfiles(this) }

    /** reads the app list off the main thread, like the grid does */
    private val worker = Executors.newSingleThreadExecutor()

    /** the apps the grid could show, or null while they are still being read */
    private var apps: List<AppEntry>? = null

    /** true while [refresh] writes the stored values into the views */
    private var refreshing = false

    /** what the last root probe found; the status sentence is built from it */
    private var rootAvailable = true
    private var multiWindow: Boolean? = null

    private lateinit var list: RecyclerView
    private lateinit var empty: View
    private lateinit var emptyTitle: TextView
    private lateinit var count: TextView
    private lateinit var status: TextView
    private lateinit var statusBanner: View
    private lateinit var statusIcon: ImageView
    private lateinit var master: SwitchCompat

    private val adapter by lazy {
        WindowMarginAdapter(
            onOpen = ::editProfile,
            onToggle = ::toggle,
            profile = { entry -> profiles.profile(entry.packageName) },
            isProtected = { entry -> profiles.isProtected(entry.packageName) },
            summary = ::summary,
            protectedNote = { getString(R.string.window_protected) },
            shape = { prefs.iconShape },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_window_margins)

        applyMargins()

        list = findViewById(R.id.windowList)
        empty = findViewById(R.id.windowEmpty)
        emptyTitle = findViewById(R.id.windowEmptyTitle)
        count = findViewById(R.id.headerTrailing)
        status = findViewById(R.id.windowStatus)
        statusBanner = findViewById(R.id.statusBanner)
        statusIcon = findViewById(R.id.statusIcon)

        findViewById<TextView>(R.id.headerTitle).setText(R.string.window_title)
        count.isVisible = true
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        buildRows()
        loadApps()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // a profile may have been refused (or applied) while this screen was in
        // the background, and the master switch may have been changed elsewhere
        refresh()
        probeRoot()
        WindowMarginService.ensure(this)
    }

    /** The one row above the list: the switch for the whole feature. */
    private fun buildRows() {
        val rows = findViewById<LinearLayout>(R.id.windowRows)
        val row = LayoutInflater.from(this).inflate(R.layout.row_setting, rows, false)
        row.findViewById<TextView>(R.id.settingTitle).setText(R.string.window_master_title)
        row.findViewById<TextView>(R.id.settingSubtitle).apply {
            setText(R.string.window_master_subtitle)
            isVisible = true
        }

        master = row.findViewById(R.id.settingSwitch)
        master.isVisible = true
        master.setOnCheckedChangeListener { _, checked ->
            if (refreshing || checked == profiles.enabled) return@setOnCheckedChangeListener
            profiles.enabled = checked
            refresh()
            WindowMarginService.ensure(this)
        }
        row.setOnClickListener { master.toggle() }
        rows.addView(row)
    }

    // ------------------------------------------------------------ data flow

    /**
     * Reads the installed apps, which is the list the grid draws from: what can
     * be configured here has to be what the main page could show.
     */
    private fun loadApps() {
        if (apps != null) return
        val context = applicationContext
        val includeSystem = prefs.includeSystem
        val descending = prefs.sortOrder == SortOrder.DESCENDING
        try {
            worker.execute {
                val read = try {
                    AppRepository(context).loadApps(includeSystem, descending)
                } catch (t: Throwable) {
                    emptyList()
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    apps = read
                    adapter.submit(read)
                    showRequestedApp()
                    refresh()
                }
            }
        } catch (t: Throwable) {
            // the executor was already shut down while finishing
        }
    }

    /**
     * The root probe is a `su` call, so it happens on the worker thread and the
     * sentence is written when it has an answer.
     */
    private fun probeRoot() {
        try {
            worker.execute {
                val root = RootShell.isAvailable()
                val supported = if (root) WindowControl.multiWindow() else null
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    rootAvailable = root
                    multiWindow = supported
                    refresh()
                }
            }
        } catch (t: Throwable) {
            // the executor was already shut down while finishing
        }
    }

    private fun refresh() {
        refreshing = true
        master.isChecked = profiles.enabled
        refreshing = false

        count.text = countText()
        applyStatus()
        adapter.notifyDataSetChanged()

        val loaded = apps
        empty.isVisible = loaded?.isEmpty() != false
        emptyTitle.setText(if (loaded == null) R.string.loading else R.string.window_empty)
    }

    /**
     * The sentence, with a tone: the app's best habit is that it says what the
     * unit actually did, and a sentence that honest deserves more than 12sp at
     * the bottom of a card.
     *
     * The tone is never red - a refusal is information, not a fault.
     */
    private fun applyStatus() {
        status.text = statusText()
        val (icon, tone, wash) = statusTone()
        statusIcon.setImageResource(icon)
        statusIcon.imageTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, tone))
        statusBanner.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, wash))
    }

    /** which glyph, which ink, which wash: applied, refused, blocked or waiting */
    private fun statusTone(): Triple<Int, Int, Int> {
        if (!profiles.enabled || profiles.watched().isEmpty()) {
            return Triple(R.drawable.ic_frame, R.color.hub_blocked, R.color.hub_wash_blocked)
        }
        if (!rootAvailable) {
            return Triple(R.drawable.ic_blocked, R.color.hub_blocked, R.color.hub_wash_blocked)
        }
        return when (WindowControl.lastOutcome) {
            is WindowOutcome.Applied ->
                Triple(R.drawable.ic_check_circle, R.color.hub_ok, R.color.hub_wash_ok)

            is WindowOutcome.Refused ->
                Triple(R.drawable.ic_warning, R.color.hub_warn, R.color.hub_wash_warn)

            else ->
                Triple(R.drawable.ic_frame, R.color.primary_text, R.color.hub_wash_info)
        }
    }

    private fun countText(): CharSequence {
        val all = apps ?: return getString(R.string.loading)
        val watched = profiles.watched().size
        return if (all.isEmpty() || watched == 0) getString(R.string.none)
        else resources.getQuantityString(R.plurals.window_count, watched, watched)
    }

    /**
     * What the feature is doing, in one sentence.
     *
     * The screen can only report: the window manager is the one that decides
     * whether a task may be moved, so a refusal is said out loud instead of
     * being worked around with something global.
     */
    private fun statusText(): CharSequence {
        if (!profiles.enabled) return getString(R.string.window_status_off)

        val watched = profiles.watched()
        if (watched.isEmpty()) return getString(R.string.window_status_none)
        if (!rootAvailable) return getString(R.string.window_status_no_root)

        return when (val outcome = WindowControl.lastOutcome) {
            is WindowOutcome.Applied ->
                getString(R.string.window_status_applied, profiles.label(outcome.packageName))

            is WindowOutcome.Refused -> {
                val name = profiles.label(outcome.packageName)
                if (outcome.windowingMode == FULLSCREEN) {
                    getString(R.string.window_status_fullscreen, name)
                } else {
                    getString(R.string.window_status_refused, name)
                }
            }

            else -> {
                val waiting = getString(R.string.window_status_waiting, watched.size)
                val caveat = getString(R.string.window_status_no_multi_window)
                if (multiWindow == false) "$waiting $caveat" else waiting
            }
        }
    }

    /**
     * Opened from an app's own long-press menu: the sheet of that app is what
     * was asked for, so it is opened straight away, with the list behind it.
     */
    private fun showRequestedApp() {
        val wanted = intent?.getStringExtra(EXTRA_PACKAGE) ?: return
        intent.removeExtra(EXTRA_PACKAGE)
        val loaded = apps ?: return
        val index = loaded.indexOfFirst { it.packageName == wanted }
        if (index < 0) return
        list.scrollToPosition(index)
        editProfile(loaded[index])
    }

    // ------------------------------------------------------------- profiles

    private fun toggle(entry: AppEntry, enabled: Boolean) {
        profiles.setEnabled(entry.packageName, enabled)
        adapter.notifyDataSetChanged()
        refresh()
        WindowMarginService.ensure(this)
    }

    /** The rectangle of one app: four edges, and the profile's own switch. */
    private fun editProfile(entry: AppEntry) {
        val profile = profiles.profile(entry.packageName)
        val rows = Margin.values().map { edge ->
            SheetRow(
                label = getString(edge.label),
                subtitle = getString(R.string.window_margin_value, profile.margin(edge)),
                onClick = { openWheel(entry, edge) },
            )
        } + SheetRow(
            label = getString(
                if (profile.enabled) R.string.window_turn_off else R.string.window_turn_on
            ),
            // The header already says which app this is; repeating its package
            // under the verb was a second line of the same fact. The hairline
            // above it says the more useful thing: the four rows above are the
            // rectangle, this row is the switch.
            groupStart = true,
            onClick = { toggle(entry, !profile.enabled) },
        )

        Sheet.show(
            context = this,
            title = entry.label,
            subtitle = getString(R.string.window_profile_message),
            appIcon = IconCache.drawn(
                resources,
                entry,
                prefs.iconShape,
                resources.getDimensionPixelSize(R.dimen.icon_banner),
            ),
            rows = rows,
        )
    }

    /**
     * The wheel for one edge, exactly like the screen margins: every whole pixel
     * from 0 up to half of that side of the display, typed entry beside it, and
     * the value written on every turn so the list behind shows the result.
     */
    private fun openWheel(entry: AppEntry, edge: Margin) {
        val max = profiles.maxMargin(edge)
        Sheet.showNumber(
            this,
            title = getString(R.string.window_edge_title, entry.label, getString(edge.label)),
            subtitle = getString(R.string.window_range, max),
            minValue = 0,
            maxValue = max,
            value = profiles.profile(entry.packageName).margin(edge),
            unit = getString(R.string.window_unit),
            done = getString(android.R.string.ok),
            extraRows = listOf(
                SheetRow(
                    label = getString(R.string.margin_type_title),
                    subtitle = getString(R.string.margin_type_subtitle),
                    onClick = { openInput(entry, edge) },
                ),
            ),
            onValue = { px -> setMargin(entry, edge, px) },
        )
    }

    /** The same edge, typed: quicker than a wheel once the number is known. */
    private fun openInput(entry: AppEntry, edge: Margin) {
        Sheet.showInput(
            this,
            title = getString(R.string.window_edge_title, entry.label, getString(edge.label)),
            subtitle = getString(R.string.window_range, profiles.maxMargin(edge)),
            value = profiles.profile(entry.packageName).margin(edge),
            unit = getString(R.string.window_unit),
            submit = getString(android.R.string.ok),
            cancel = getString(android.R.string.cancel),
            onSubmit = { px -> setMargin(entry, edge, px) },
        )
    }

    /**
     * Writes one edge, clamped by the store - an over-large entry lands on the
     * limit instead of being refused - and turns the profile on with it.
     */
    private fun setMargin(entry: AppEntry, edge: Margin, px: Int) {
        profiles.setMargin(entry.packageName, edge, px)
        adapter.notifyDataSetChanged()
        refresh()
        WindowMarginService.ensure(this)
    }

    /** "200 / 20 / 0 / 0 px", or "Off" while the profile is off. */
    private fun summary(profile: WindowProfile): CharSequence = if (!profile.enabled) {
        getString(R.string.window_off)
    } else {
        getString(R.string.window_value, profile.left, profile.right, profile.top, profile.bottom)
    }

    companion object {
        /** from the grid's long-press menu: open this app's profile straight away */
        const val EXTRA_PACKAGE = "window_package"

        /** the windowing mode the unit will not resize, from `am stack list` */
        private const val FULLSCREEN = "fullscreen"
    }
}

/**
 * One row per app on the window margins screen.
 *
 * Everything the row needs about a profile arrives as a function, like the
 * shortcuts list: the adapter then has no idea what a profile is or where it is
 * stored, and the screen stays the only place that reads them.
 */
private class WindowMarginAdapter(
    private val onOpen: (AppEntry) -> Unit,
    private val onToggle: (AppEntry, Boolean) -> Unit,
    private val profile: (AppEntry) -> WindowProfile,
    private val isProtected: (AppEntry) -> Boolean,
    private val summary: (WindowProfile) -> CharSequence,
    private val protectedNote: () -> CharSequence,
    /** the shape the board is clipping its icons into */
    private val shape: () -> IconShape,
) : RecyclerView.Adapter<WindowMarginAdapter.Row>() {

    private val items = ArrayList<AppEntry>()

    fun submit(apps: List<AppEntry>) {
        items.clear()
        items.addAll(apps)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row =
        Row(LayoutInflater.from(parent.context).inflate(R.layout.row_window_app, parent, false))

    override fun onBindViewHolder(holder: Row, position: Int) {
        holder.bind(items[position])
    }

    inner class Row(view: View) : RecyclerView.ViewHolder(view) {

        private val icon = view.findViewById<ImageView>(R.id.rowIcon)
        private val label = view.findViewById<TextView>(R.id.rowLabel)
        private val subtitle = view.findViewById<TextView>(R.id.rowSubtitle)
        private val value = view.findViewById<TextView>(R.id.windowValue)
        private val switch = view.findViewById<SwitchCompat>(R.id.windowSwitch)

        private val rowIconPx = view.resources.getDimensionPixelSize(R.dimen.icon_app_row)

        fun bind(entry: AppEntry) {
            val locked = isProtected(entry)
            val current = profile(entry)

            icon.setImageDrawable(IconCache.drawn(itemView.resources, entry, shape(), rowIconPx))
            label.text = entry.label
            subtitle.text = if (locked) protectedNote() else entry.packageName
            value.text = summary(current)

            // the listener is detached first, or putting the stored state back
            // would read as the user's own tap and be written straight back
            switch.setOnCheckedChangeListener(null)
            switch.isChecked = current.enabled
            switch.isEnabled = !locked
            switch.setOnCheckedChangeListener { _, checked ->
                if (!locked) onToggle(entry, checked)
            }

            // a protected app has no rectangle to edit, so its row is inert
            itemView.isClickable = !locked
            itemView.isFocusable = !locked
            itemView.setOnClickListener { if (!locked) onOpen(entry) }
        }
    }
}
