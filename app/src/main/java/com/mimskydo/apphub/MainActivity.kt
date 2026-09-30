package com.mimskydo.apphub

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlin.system.exitProcess

/**
 * The board: a tile per app, and nothing that is not an app.
 *
 *   tap            -> launch that app
 *   hold           -> the board lifts; every tile grows a "..." and the page
 *                     can be rearranged
 *   the pill       -> tools: search, settings, edit, task manager, close all,
 *                     close the hub
 *
 * Two things left this screen. The gear and Close cells that used to end the
 * grid put a red "quit the app you are looking at" one mis-tap away from an
 * icon, and made the settings reachable only by scrolling past every installed
 * app; both live behind the pill now, which floats and is therefore always one
 * tap away. And the toast that used to report a hidden tile is an undo bar:
 * everything on this screen that can be undone now says so, and offers it.
 */
class MainActivity : BaseActivity() {

    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var grid: RecyclerView
    private lateinit var adapter: AppAdapter
    private lateinit var pill: View
    private lateinit var searchRow: View
    private lateinit var searchField: EditText
    private lateinit var editBar: View
    private lateinit var undo: UndoBar

    private val prefs by lazy { Prefs(this) }
    private val pins by lazy { PinnedApps(this) }
    private val windowProfiles by lazy { WindowProfiles(this) }

    /** every app, already decorated with pin + running state */
    private var loaded: List<AppEntry> = emptyList()

    /** what the last read could see, kept for the task ids a Close can use */
    private var openSnapshot: OpenSnapshot? = null

    /** the tiles the board is actually drawing, i.e. [loaded] minus hidden, minus filtered */
    private var shown: List<AppEntry> = emptyList()

    private var appliedColumns = 0

    /** true until the first answer from the app repository, either way */
    private var loading = true

    /** the repository threw: a failure is not an empty board */
    private var failed = false

    /** what is typed in the search cap, squashed, and the same text as typed */
    private var needle = ""
    private var query = ""

    /** the board is lifted; the tiles are draggable and every tile says so */
    private var editing = false

    /** the last reversible thing the driver did, offered back for five seconds */
    private var undoAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        applyMargins()

        grid = findViewById(R.id.appGrid)
        pill = findViewById(R.id.hubPill)
        searchRow = findViewById(R.id.searchRow)
        searchField = findViewById(R.id.searchField)
        editBar = findViewById(R.id.editBar)
        undo = UndoBar(findViewById(R.id.undoBar)) { undoAction?.invoke() }

        adapter = AppAdapter(
            onOpen = ::openApp,
            onMore = ::showActions,
            onHold = { lift() },
            onPanelAction = ::onPanelAction,
        )
        grid.adapter = adapter

        wireTools()
        wireSearch()
        wireEditBar()
        wireBanner()
        wireReorder()
        wireScrollFade()
    }

    override fun onResume() {
        super.onResume()
        // re-read on every resume so an app installed while this screen was in
        // the background shows up without a restart, and so a settings change
        // (including the screen margins) is picked up on the way back
        applyMargins()
        reload()
        // opening the hub is also what re-arms the window-margins watcher: a
        // unit that refused the start after a reboot gets another offer here
        WindowMarginService.ensure(this)
    }

    override fun onPause() {
        super.onPause()
        // the board is put down when it leaves the screen: coming back to a
        // board that is still lifted is a state nobody asked to keep
        putDown()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    /** back leaves the search, or the lift, before it leaves the board */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        when {
            editing -> putDown()
            searchRow.isVisible -> closeSearch()
            else -> super.onBackPressed()
        }
    }

    // ------------------------------------------------------------ data flow

    private fun background(block: () -> Unit) {
        try {
            worker.execute(block)
        } catch (t: Throwable) {
            // executor already shut down while finishing
        }
    }

    private fun post(block: () -> Unit) = runOnUiThread {
        if (!isFinishing && !isDestroyed) block()
    }

    private fun reload() {
        val context = applicationContext
        val includeSystem = prefs.includeSystem
        val descending = prefs.sortOrder == SortOrder.DESCENDING

        loading = true
        failed = false
        render()

        background {
            val apps = try {
                AppRepository(context).loadApps(includeSystem, descending)
            } catch (t: Throwable) {
                // a failure is not an empty board: the two say different things,
                // and only one of them is worth retrying
                post {
                    loading = false
                    failed = true
                    render()
                }
                return@background
            }
            val packages = apps.map { it.packageName }
            val pinned = pins.all()

            // show the board immediately, the running marks follow a moment later
            post {
                loading = false
                loaded = apps.map { it.copy(pinned = it.packageName in pinned) }
                render()
            }

            // the same reader the task manager uses, so the dots and that
            // screen can never disagree about which apps are open
            val open = OpenApps.read(context, packages)

            val decorated = apps.map { entry ->
                entry.copy(
                    pinned = entry.packageName in pinned,
                    state = open.state(entry.packageName),
                )
            }

            post {
                loaded = decorated
                openSnapshot = open
                render()
            }
        }
    }

    /**
     * The cells for the current state of the board, in the order it draws them.
     *
     * Hidden apps are dropped here rather than in the repository: the rest of
     * this screen (pinned order, "close all") still knows about them, and the
     * latter still only closes what the screen is showing.
     */
    private fun cells(): List<GridItem> {
        if (failed) return listOf(GridItem.Panel(PanelKind.ERROR))

        val all = MainPage.arrange(
            apps = loaded.filter { !prefs.isHidden(it.packageName) },
            order = prefs.pageOrder,
            pinned = pins.all(),
        )

        if (loading && loaded.isEmpty()) {
            // the shape of the answer is known, so the placeholder has it too
            val columns = prefs.columnCount(resources.configuration.screenWidthDp)
            return List(maxOf(columns, 2) * SKELETON_ROWS) { GridItem.Skeleton }
        }

        shown = if (needle.isEmpty()) all
        else all.filter { Filter.matches(Filter.key(it.label, it.packageName), needle) }

        return when {
            all.isEmpty() -> listOf(GridItem.Panel(PanelKind.EMPTY))
            shown.isEmpty() -> listOf(GridItem.Panel(PanelKind.NO_MATCH, query))
            else -> shown.map { GridItem.App(it) }
        }
    }

    private fun render() {
        adapter.configure(iconPx(), prefs.iconShape, prefs.showNames)
        adapter.setEditing(editing)

        // only rebuild the layout manager when the column count really changed,
        // otherwise the scroll position resets on every resume
        val columns = prefs.columnCount(resources.configuration.screenWidthDp)
        if (appliedColumns != columns || grid.layoutManager == null) {
            grid.layoutManager = gridLayoutManager(columns)
            appliedColumns = columns
        }

        adapter.submit(cells())
        updateChrome()
    }

    private fun iconPx(): Int = (prefs.iconSize.dp * resources.displayMetrics.density).roundToInt()

    /** the empty-state cell has to span the grid, everything else is one cell */
    private fun gridLayoutManager(columns: Int) = GridLayoutManager(this, columns).apply {
        spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (adapter.spansFullWidth(position)) columns else 1
        }
    }

    // ----------------------------------------------------------------- chrome

    private fun updateChrome() {
        pill.isVisible = !editing
        editBar.isVisible = editing

        // ... and only while the dots really are missing: an exact view of what
        // is open (the task list, or root) marks the board without any opt-in,
        // so asking for one there would be asking for nothing
        val showBanner = !loading && !failed && loaded.isNotEmpty() &&
            openSnapshot?.exact != true &&
            !ActivityStats.hasAccess(this) && !prefs.dotsOfferDismissed
        findViewById<View>(R.id.dotsBanner).isVisible = showBanner
    }

    /** Tools, in one floating sheet: the two cells the board used to end with. */
    private fun wireTools() {
        pill.setOnClickListener {
            Sheet.showBottom(
                context = this,
                rows = listOf(
                    SheetRow(
                        label = getString(R.string.find_app),
                        icon = ContextCompat.getDrawable(this, R.drawable.ic_search),
                        onClick = { openSearch() },
                    ),
                    SheetRow(
                        label = getString(R.string.settings_title),
                        icon = ContextCompat.getDrawable(this, R.drawable.ic_tune),
                        onClick = { startActivity(Intent(this, SettingsActivity::class.java)) },
                    ),
                    SheetRow(
                        label = getString(R.string.edit_page),
                        icon = ContextCompat.getDrawable(this, R.drawable.ic_edit),
                        onClick = { lift() },
                    ),
                    SheetRow(
                        label = getString(R.string.task_manager),
                        icon = ContextCompat.getDrawable(this, R.drawable.ic_tasks),
                        onClick = { startActivity(Intent(this, TaskManagerActivity::class.java)) },
                    ),
                    SheetRow(
                        label = getString(R.string.close_all),
                        icon = ContextCompat.getDrawable(this, R.drawable.ic_close),
                        danger = true,
                        groupStart = true,
                        onClick = { closeAll() },
                    ),
                    SheetRow(
                        label = getString(R.string.close_hub),
                        icon = ContextCompat.getDrawable(this, R.drawable.ic_power),
                        danger = true,
                        // App Hub itself is the one thing the close layers
                        // cannot do for us, so it ends its own process.
                        onClick = {
                            finishAffinity()
                            exitProcess(0)
                        },
                    ),
                    SheetRow(
                        label = getString(android.R.string.cancel),
                        groupStart = true,
                    ),
                ),
            )
        }
    }

    /** the pill gets out of the way while the board is being read */
    private fun wireScrollFade() {
        val restore = Runnable { fadePill(1f) }
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (editing) return
                fadePill(0.4f)
                grid.removeCallbacks(restore)
                grid.postDelayed(restore, Motion.duration(this@MainActivity, R.integer.motion_medium))
            }
        })
    }

    private fun fadePill(alpha: Float) {
        if (pill.alpha == alpha) return
        pill.animate()
            .alpha(alpha)
            .setDuration(Motion.duration(this, R.integer.motion_fast))
            .setInterpolator(Motion.standard())
            .start()
    }

    // ----------------------------------------------------------------- search

    private fun wireSearch() {
        searchField.doAfterTextChanged { text ->
            val typed = text?.toString().orEmpty()
            query = typed
            needle = Filter.key(typed)
            render()
        }
        findViewById<ImageView>(R.id.searchClear).setOnClickListener {
            searchField.setText("")
        }
    }

    private fun openSearch() {
        if (searchRow.isVisible) return
        searchRow.isVisible = true
        searchRow.alpha = 0f
        searchRow.translationY = -resources.getDimension(R.dimen.space_3)
        searchRow.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(Motion.duration(this, R.integer.motion_medium))
            .setInterpolator(Motion.emphasized())
            .start()
        searchField.requestFocus()
        getSystemService<InputMethodManager>()?.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearch() {
        searchField.setText("")
        searchField.clearFocus()
        getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(searchField.windowToken, 0)
        searchRow.animate()
            .alpha(0f)
            .setDuration(Motion.duration(this, R.integer.motion_medium))
            .setInterpolator(Motion.exit())
            .withEndAction { searchRow.isVisible = false }
            .start()
    }

    // -------------------------------------------------------------- the lift

    private fun wireEditBar() {
        findViewById<TextView>(R.id.editDone).setOnClickListener { putDown() }
        findViewById<TextView>(R.id.editReset).setOnClickListener { resetOrder() }
    }

    /**
     * The board lifts: every tile says what can be done to it, and the page
     * becomes something you can rearrange.
     *
     * This is the app's one piece of personality, and it is also the fix for its
     * oldest problem: five of the six verbs in the old long-press menu had no
     * discoverable way in at all. Holding a tile 400ms now shows all of them.
     */
    private fun lift() {
        if (editing) return
        editing = true
        adapter.setEditing(true)
        Motion.tap(grid, Motion.Kind.HOLD)
        updateChrome()
        // the tiles come up one column at a time, so the board reads as one
        // object being picked up rather than a list re-laying itself out
        grid.post {
            val columns = maxOf(appliedColumns, 1)
            for (i in 0 until grid.childCount) {
                val tile = grid.getChildAt(i) ?: continue
                tile.animate().cancel()
                tile.scaleX = 1f
                tile.scaleY = 1f
                tile.animate()
                    .scaleX(LIFT_SCALE)
                    .scaleY(LIFT_SCALE)
                    .setStartDelay((i % columns) * STAGGER_MILLIS)
                    .setDuration(Motion.duration(this, R.integer.motion_medium))
                    .setInterpolator(Motion.emphasized())
                    .start()
            }
        }
    }

    private fun putDown() {
        if (!editing) return
        editing = false
        adapter.setEditing(false)
        updateChrome()
        grid.post {
            for (i in 0 until grid.childCount) {
                val tile = grid.getChildAt(i) ?: continue
                tile.animate().cancel()
                tile.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(Motion.duration(this, R.integer.motion_fast))
                    .setInterpolator(Motion.standard())
                    .start()
            }
        }
    }

    /** back to the sort, for a page that has been shuffled into a mess */
    private fun resetOrder() {
        if (prefs.pageOrder.isEmpty()) return
        val previous = prefs.pageOrder
        prefs.pageOrder = emptyList()
        render()
        undo(getString(R.string.order_reset)) {
            prefs.pageOrder = previous
            render()
        }
    }

    /**
     * Dragging rearranges the page for real, and the order is written the moment
     * a tile is let go.
     *
     * Only app cells may be carried and only onto one another, so a tile can
     * never be dropped past the panel that spans the board - a position the user
     * never asked for.
     */
    private fun wireReorder() {
        val callback = object : ItemTouchHelper.SimpleCallback(DRAG_DIRECTIONS, 0) {

            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ): Int = if (editing && adapter.isApp(viewHolder.adapterPosition)) {
                makeMovementFlags(DRAG_DIRECTIONS, 0)
            } else {
                // Nothing is draggable until the board is lifted: a long press
                // has to mean one thing, and its first job is the lift.
                makeMovementFlags(0, 0)
            }

            override fun canDropOver(
                recyclerView: RecyclerView,
                current: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean = adapter.isApp(target.adapterPosition)

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                if (!adapter.isApp(from) || !adapter.isApp(to)) return false
                adapter.move(from, to)
                return true
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.elevation = resources.getDimension(R.dimen.elev_dragging)
                }
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) {
                super.clearView(recyclerView, viewHolder)
                // the tile goes back onto the board, and the arrangement becomes
                // the page's own order from here on
                viewHolder.itemView.elevation = resources.getDimension(R.dimen.elev_raised)
                val order = adapter.appPackages()
                if (order.isNotEmpty()) prefs.pageOrder = order
                Motion.tap(recyclerView, Motion.Kind.KEY)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
        }
        ItemTouchHelper(callback).attachToRecyclerView(grid)
    }

    // ------------------------------------------------------------- the banner

    /**
     * The running marks need an opt-in, and the board - not a settings row - is
     * where that is worth saying: an unexplained absence of dots is a bug from
     * the driver's side of the screen.
     */
    private fun wireBanner() {
        val banner = findViewById<View>(R.id.dotsBanner)
        val title = banner.findViewById<TextView>(R.id.bannerTitle)
        val message = banner.findViewById<TextView>(R.id.bannerMessage)
        val action = banner.findViewById<TextView>(R.id.bannerAction)
        val dismiss = banner.findViewById<ImageView>(R.id.bannerDismiss)

        title.setText(R.string.dots_offer_title)
        message.setText(R.string.dots_offer_message)
        action.isVisible = true
        action.setText(R.string.enable)
        dismiss.isVisible = true

        // attention, not fault: amber is the car's "worth knowing", never red
        val wash = ContextCompat.getColor(this, R.color.hub_wash_warn)
        banner.backgroundTintList = android.content.res.ColorStateList.valueOf(wash)
        banner.findViewById<ImageView>(R.id.bannerIcon).imageTintList =
            android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.hub_warn))

        action.setOnClickListener {
            if (!ActivityStats.openAccessSettings(this)) {
                toast(getString(R.string.settings_unavailable))
            }
        }
        dismiss.setOnClickListener {
            prefs.dotsOfferDismissed = true
            updateChrome()
        }
    }

    private fun onPanelAction(kind: PanelKind) {
        when (kind) {
            PanelKind.EMPTY -> startActivity(Intent(this, SettingsActivity::class.java))
            PanelKind.ERROR -> reload()
            PanelKind.NO_MATCH -> closeSearch()
        }
    }

    // -------------------------------------------------------------- actions

    private fun openApp(entry: AppEntry) {
        val exact = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(entry.packageName, entry.activityName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (tryStart(exact)) return

        // fall back to whatever the system considers this app's entry point
        val launchIntent = packageManager.getLaunchIntentForPackage(entry.packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (tryStart(launchIntent)) return
        }
        toast(getString(R.string.cannot_open, entry.label))
    }

    private fun tryStart(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    /**
     * Closing is not reversible, so it reports instead of offering: an "Undo"
     * beside a force-stopped app would be an invitation to re-launch it, which
     * is not the same thing as un-closing it.
     */
    private fun closeApp(entry: AppEntry) {
        // the task id is read here, on the UI thread: it belongs to the same
        // read the board was drawn from
        val taskId = openSnapshot?.taskId(entry.packageName)
        background {
            val method = ForceStop.close(applicationContext, entry.packageName, taskId)
            post { toast(CloseReport.text(this, method, entry.label, false)) }
            pauseThenReload()
        }
    }

    /**
     * The tools asked for it, so it happens - there is no second question.
     * The menu row is already one deliberate tap among five, and everything
     * closed here is one tile away on the board afterwards.
     *
     * What "all" means depends on what can be seen. With an exact view of what
     * is open it is those apps, which is what the row says - and that matters
     * since the platform-signed install turned this from a request the platform
     * ignored into a real force stop: force-stopping every installed app, alarms
     * and services included, because one row was tapped is not a thing to leave
     * lying around. With no view at all, every app in the list is all there is,
     * which is what this always did.
     */
    private fun closeAll() {
        val targets = shown.filter { it.packageName != packageName }
        val open = openSnapshot
        val chosen =
            if (open?.exact == true) targets.filter { open.state(it.packageName) != AppState.IDLE }
            else targets
        if (chosen.isEmpty()) {
            toast(getString(R.string.close_all_none))
            return
        }
        closeAll(chosen)
    }

    private fun closeAll(targets: List<AppEntry>) {
        if (targets.isEmpty()) return
        val ids = targets.associate { it.packageName to openSnapshot?.taskId(it.packageName) }
        background {
            val context = applicationContext
            targets.forEach { ForceStop.close(context, it.packageName, ids[it.packageName]) }
            post { toast(getString(R.string.close_all_done, targets.size)) }
            pauseThenReload()
        }
    }

    /** give the system a moment to reap the processes before re-reading them */
    private fun pauseThenReload() {
        Thread.sleep(800)
        post { reload() }
    }

    /**
     * The app card: what can be done with this one app, grouped the way it is
     * thought about - do it, arrange it, remove it.
     */
    private fun showActions(entry: AppEntry) {
        val rows = ArrayList<SheetRow>(7)

        rows += SheetRow(
            label = getString(R.string.action_open),
            icon = ContextCompat.getDrawable(this, R.drawable.ic_open),
            onClick = { openApp(entry) },
        )
        // An app whose window must not be touched - the unit's own launcher,
        // SystemUI - offers no window margins: the switch behind this row would
        // do nothing, and offering it would promise otherwise.
        if (!windowProfiles.isProtected(entry.packageName)) {
            rows += SheetRow(
                label = getString(R.string.action_window),
                icon = ContextCompat.getDrawable(this, R.drawable.ic_frame),
                onClick = { openWindowMargins(entry) },
            )
        }
        // The hairline above the two rows that take the app away is the whole
        // grouping: do it, arrange it, remove it - read as three things rather
        // than as six verbs in a column.
        rows += SheetRow(
            label = getString(if (entry.pinned) R.string.action_unpin else R.string.action_pin),
            icon = ContextCompat.getDrawable(this, R.drawable.ic_pin),
            groupStart = true,
            onClick = { togglePin(entry) },
        )
        rows += SheetRow(
            label = getString(R.string.action_info),
            icon = ContextCompat.getDrawable(this, R.drawable.ic_info),
            onClick = { openAppInfo(entry.packageName) },
        )

        rows += SheetRow(
            label = getString(R.string.action_close),
            icon = ContextCompat.getDrawable(this, R.drawable.ic_close),
            danger = true,
            groupStart = true,
            onClick = { closeApp(entry) },
        )
        rows += SheetRow(
            label = getString(R.string.action_hide),
            icon = ContextCompat.getDrawable(this, R.drawable.ic_hide),
            danger = true,
            onClick = { hideFromMainPage(entry) },
        )

        Sheet.show(
            context = this,
            title = entry.label,
            subtitle = sheetSubtitle(entry),
            // the card's icon is the tile's icon: clipped into the shape the
            // settings ask for, drawn at the header's own size
            appIcon = IconCache.drawn(
                resources,
                entry,
                prefs.iconShape,
                resources.getDimensionPixelSize(R.dimen.icon_banner),
            ),
            rows = rows,
        )
    }

    /** the package, and what this app is doing, in the card's own header */
    private fun sheetSubtitle(entry: AppEntry): CharSequence = when (entry.state) {
        AppState.RUNNING -> getString(R.string.tile_running, entry.packageName)
        AppState.OPEN -> getString(R.string.tile_open, entry.packageName)
        AppState.RECENT -> getString(R.string.tile_recent, entry.packageName)
        AppState.IDLE -> entry.packageName
    }

    /**
     * Off the board. One way only, because this menu can only be opened on an
     * app that has a tile: the shortcuts screen is where a hidden app is ticked
     * back on - and the undo bar, for the five seconds it is up.
     */
    private fun hideFromMainPage(entry: AppEntry) {
        prefs.setHidden(entry.packageName, true)
        render()
        undo(getString(R.string.hidden_undo, entry.label)) {
            prefs.setHidden(entry.packageName, false)
            render()
        }
    }

    private fun togglePin(entry: AppEntry) {
        val wasPinned = pins.isPinned(entry.packageName)
        if (wasPinned) {
            pins.unpin(entry.packageName)
        } else {
            pins.pin(entry.packageName)
            // Once the page has an order of its own, the pin ranking no longer
            // decides anything, so "pin to top" has to mean the front of that
            // order instead - otherwise the row would promise something it does
            // not do any more.
            val order = prefs.pageOrder
            if (order.isNotEmpty()) {
                prefs.pageOrder =
                    listOf(entry.packageName) + order.filter { it != entry.packageName }
            }
        }
        loaded = loaded.map {
            if (it.packageName == entry.packageName) it.copy(pinned = !entry.pinned) else it
        }
        render()
        undo(
            getString(
                if (!wasPinned) R.string.pinned_undo else R.string.unpinned_undo,
                entry.label,
            )
        ) {
            if (wasPinned) pins.pin(entry.packageName) else pins.unpin(entry.packageName)
            render()
        }
    }

    /** Straight into that app's own rectangle, on the screen that keeps them. */
    private fun openWindowMargins(entry: AppEntry) {
        startActivity(
            Intent(this, WindowMarginsActivity::class.java)
                .putExtra(WindowMarginsActivity.EXTRA_PACKAGE, entry.packageName)
        )
    }

    private fun openAppInfo(pkg: String) {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$pkg"),
                )
            )
        } catch (e: ActivityNotFoundException) {
            // nothing else we can do on this build
        }
    }

    /** show a message with its own way back, for as long as the bar lives */
    private fun undo(message: CharSequence, action: () -> Unit) {
        undoAction = {
            undoAction = null
            action()
        }
        undo.show(message)
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** how many rows of placeholders are drawn while the list is read */
        private const val SKELETON_ROWS = 3

        private const val LIFT_SCALE = 1.02f

        /** the board comes up column by column, not all at once */
        private const val STAGGER_MILLIS = 30L

        private val DRAG_DIRECTIONS =
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or
                ItemTouchHelper.START or ItemTouchHelper.END
    }
}
