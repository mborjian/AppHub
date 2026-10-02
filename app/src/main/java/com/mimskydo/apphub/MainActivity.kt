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

/**
 * The board: a tile per app, and nothing that is not an app.
 *
 *   tap            -> launch that app
 *   hold           -> the board lifts; every tile grows a "..." and the page
 *                     can be rearranged
 *   the pill       -> tools: search, settings, edit the page
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

    /**
     * What the last read could see: the task ids a Close can use, and whether
     * this install may close anything at all (see [showActions]).
     */
    private var openSnapshot: OpenSnapshot? = null

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

        reportUpdate()
    }

    /**
     * What became of an update that replaced this app's own process.
     *
     * The run that starts an install can never report it - putting a package in
     * place of a running one kills the process that asked for it - so the version
     * it was installing is left in [Prefs.pendingUpdate] and read back here, once,
     * on the first board after the restart. Which of the two sentences it gets is
     * the version actually installed, not the one that was hoped for.
     */
    private fun reportUpdate() {
        val wanted = prefs.pendingUpdate ?: return
        prefs.pendingUpdate = null
        toast(
            if (Updater.version(this).name == wanted) getString(R.string.update_done, wanted)
            else getString(R.string.update_not_installed, wanted)
        )
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

            // one read decides the dots and the task ids a Close can use, so
            // the two can never disagree about which apps are open
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
     * Hidden apps are dropped here rather than in the repository: they are
     * installed like the rest, and the pinned order still ranks them - they are
     * only off this page.
     */
    private fun cells(): List<GridItem> {
        if (failed) return listOf(GridItem.Panel(PanelKind.ERROR))

        // the page, not just the apps: App Hub's own file-manager tile stands on
        // it too while that screen is switched on (see [MainPage.page])
        val all = MainPage.page(
            context = this,
            apps = loaded.filter { !prefs.isHidden(it.key) },
            order = prefs.pageOrder,
            pinned = pins.all(),
        )

        if (loading && loaded.isEmpty()) {
            // the shape of the answer is known, so the placeholder has it too
            val columns = prefs.columnCount(resources.configuration.screenWidthDp)
            return List(maxOf(columns, 2) * SKELETON_ROWS) { GridItem.Skeleton }
        }

        val shown = if (needle.isEmpty()) all
        else all.filter { Filter.matches(Filter.key(it.label, it.key), needle) }

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
     * Only the card for an install that can really close reaches this (see
     * [showActions]).
     *
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
            post { toast(CloseReport.text(this, method, entry.label)) }
            pauseThenReload()
        }
    }

    /**
     * The one removal that re-opening cannot bring back, so it is the one that
     * asks first. Red is the yes-row inside that question and nowhere else on
     * this card: the card itself only ever says amber.
     */
    private fun confirmUninstall(entry: AppEntry) {
        Sheet.show(
            context = this,
            title = getString(R.string.uninstall_title, entry.label),
            subtitle = getString(R.string.uninstall_message),
            rows = listOf(
                SheetRow(
                    label = getString(R.string.uninstall_confirm),
                    destroy = true,
                    onClick = { uninstallApp(entry) },
                ),
                SheetRow(label = getString(android.R.string.cancel)),
            ),
        )
    }

    /**
     * The layers answer and the report says which: the platform's own screen
     * may be the thing that just opened, in which case nothing has been removed
     * yet and the sentence says so. The board re-reads either way, so the tile -
     * not the toast - is where the outcome ends up visible.
     */
    private fun uninstallApp(entry: AppEntry) {
        background {
            val method = Uninstall.uninstall(applicationContext, entry)
            post { toast(UninstallReport.text(this, method, entry.label)) }
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
        // One of App Hub's own screens is not an app, and the card does not
        // pretend it is: there is nothing to close (it is this app), nothing to
        // uninstall (that is this install), and no window of another app to move.
        // What is left is what is true of a screen.
        if (entry.tool != null) {
            showToolActions(entry)
            return
        }

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

        // Close is offered only where closing can really happen. A real force
        // stop is the first two layers of [ForceStop] - `FORCE_STOP_PACKAGES`
        // (the platform-signed install) or a `su` this app may use - and
        // [OpenAccess] answered exactly that question off the UI thread, in the
        // same read that drew the dots. An install holding neither is shown no
        // Close row rather than one whose every tap would end in "still open".
        val canClose = openSnapshot?.access?.forceStop == true
        if (canClose) {
            rows += SheetRow(
                label = getString(R.string.action_close),
                icon = ContextCompat.getDrawable(this, R.drawable.ic_close),
                danger = true,
                groupStart = true,
                onClick = { closeApp(entry) },
            )
        }
        rows += SheetRow(
            label = getString(R.string.action_hide),
            icon = ContextCompat.getDrawable(this, R.drawable.ic_hide),
            danger = true,
            // with no Close above it, Hide is the row the hairline opens on
            groupStart = !canClose,
            onClick = { hideFromMainPage(entry) },
        )
        // Only an app the user put on the unit gets this row: a factory app is
        // not this install's to remove, and the answer comes from the entry
        // rather than being asked again here - the same read that decided what
        // the board may draw is the one the card acts on (see [AppEntry.system]).
        if (!entry.system) {
            rows += SheetRow(
                label = getString(R.string.action_uninstall),
                icon = ContextCompat.getDrawable(this, R.drawable.ic_uninstall),
                danger = true,
                onClick = { confirmUninstall(entry) },
            )
        }

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

    /**
     * The card for one of this app's own screens: do it, arrange it, take the
     * tile away.
     *
     * Three rows and no more, because on this screen the two rows that would be
     * missing are missing for the same reason - the tile is App Hub itself.
     */
    private fun showToolActions(entry: AppEntry) {
        Sheet.show(
            context = this,
            title = entry.label,
            subtitle = sheetSubtitle(entry),
            appIcon = IconCache.drawn(
                resources,
                entry,
                prefs.iconShape,
                resources.getDimensionPixelSize(R.dimen.icon_banner),
            ),
            rows = listOf(
                SheetRow(
                    label = getString(R.string.action_open),
                    icon = ContextCompat.getDrawable(this, R.drawable.ic_open),
                    onClick = { openApp(entry) },
                ),
                SheetRow(
                    label = getString(if (entry.pinned) R.string.action_unpin else R.string.action_pin),
                    icon = ContextCompat.getDrawable(this, R.drawable.ic_pin),
                    groupStart = true,
                    onClick = { togglePin(entry) },
                ),
                SheetRow(
                    label = getString(R.string.action_hide),
                    icon = ContextCompat.getDrawable(this, R.drawable.ic_hide),
                    danger = true,
                    groupStart = true,
                    onClick = { hideFromMainPage(entry) },
                ),
            ),
        )
    }

    /** the package, and what this app is doing, in the card's own header */
    private fun sheetSubtitle(entry: AppEntry): CharSequence {
        // a tile of this app's own says which screen it is rather than repeating
        // the package name the driver is already looking at
        val tool = entry.tool
        return if (tool != null) getString(tool.subtitle)
        else sheetSubtitle(entry.state, entry.packageName)
    }

    private fun sheetSubtitle(state: AppState, packageName: String): CharSequence = when (state) {
        AppState.RUNNING -> getString(R.string.tile_running, packageName)
        AppState.OPEN -> getString(R.string.tile_open, packageName)
        AppState.RECENT -> getString(R.string.tile_recent, packageName)
        AppState.IDLE -> packageName
    }

    /**
     * Off the board. One way only, because this menu can only be opened on an
     * app that has a tile: the shortcuts screen is where a hidden app is ticked
     * back on - and the undo bar, for the five seconds it is up.
     */
    private fun hideFromMainPage(entry: AppEntry) {
        // One of this app's own screens is taken off the page by the settings
        // switch and not by the hidden-apps set: the switch is the one place the
        // tile can be found again, and a tile whose two records live apart is a
        // tile that comes back on its own. Which is also why the switch writes
        // the tile's own switch, which is the same pref the settings row writes:
        // one decision, one record, whichever screen made it (see [Tool.show])
        val tool = entry.tool
        if (tool != null) {
            tool.show(prefs, false)
            render()
            undo(getString(R.string.hidden_undo, entry.label)) {
                tool.show(prefs, true)
                render()
            }
            return
        }

        prefs.setHidden(entry.packageName, true)
        render()
        undo(getString(R.string.hidden_undo, entry.label)) {
            prefs.setHidden(entry.packageName, false)
            render()
        }
    }

    /**
     * Pin or unpin one cell.
     *
     * By [AppEntry.key] and not by package: the board's own tiles share this app's
     * package name, so pinning on that name would pin both of them at once.
     */
    private fun togglePin(entry: AppEntry) {
        val wasPinned = pins.isPinned(entry.key)
        if (wasPinned) {
            pins.unpin(entry.key)
        } else {
            pins.pin(entry.key)
            // Once the page has an order of its own, the pin ranking no longer
            // decides anything, so "pin to top" has to mean the front of that
            // order instead - otherwise the row would promise something it does
            // not do any more.
            val order = prefs.pageOrder
            if (order.isNotEmpty()) {
                prefs.pageOrder = listOf(entry.key) + order.filter { it != entry.key }
            }
        }
        loaded = loaded.map {
            if (it.key == entry.key) it.copy(pinned = !entry.pinned) else it
        }
        render()
        undo(
            getString(
                if (!wasPinned) R.string.pinned_undo else R.string.unpinned_undo,
                entry.label,
            )
        ) {
            if (wasPinned) pins.pin(entry.key) else pins.unpin(entry.key)
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
