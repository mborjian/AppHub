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

    private var loaded: List<AppEntry> = emptyList()

    private var openSnapshot: OpenSnapshot? = null

    private var appliedColumns = 0

    private var loading = true

    private var failed = false

    private var needle = ""
    private var query = ""

    private var editing = false

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
        applyMargins()
        reload()
    }

    override fun onPause() {
        super.onPause()
        putDown()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        when {
            editing -> putDown()
            searchRow.isVisible -> closeSearch()
            else -> super.onBackPressed()
        }
    }

    private fun background(block: () -> Unit) {
        try {
            worker.execute(block)
        } catch (t: Throwable) {
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
                post {
                    loading = false
                    failed = true
                    render()
                }
                return@background
            }
            val packages = apps.map { it.packageName }
            val pinned = pins.all()

            post {
                loading = false
                loaded = apps.map { it.copy(pinned = it.packageName in pinned) }
                render()
            }

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

    private fun cells(): List<GridItem> {
        if (failed) return listOf(GridItem.Panel(PanelKind.ERROR))

        val all = MainPage.page(
            context = this,
            apps = loaded.filter { !prefs.isHidden(it.key) },
            order = prefs.pageOrder,
            pinned = pins.all(),
        )

        if (loading && loaded.isEmpty()) {
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

        val columns = prefs.columnCount(resources.configuration.screenWidthDp)
        // a fresh LayoutManager resets the scroll: build one only when the column count really changed
        if (appliedColumns != columns || grid.layoutManager == null) {
            grid.layoutManager = gridLayoutManager(columns)
            appliedColumns = columns
        }

        adapter.submit(cells())
        updateChrome()
    }

    private fun iconPx(): Int = (prefs.iconSize.dp * resources.displayMetrics.density).roundToInt()

    private fun gridLayoutManager(columns: Int) = GridLayoutManager(this, columns).apply {
        spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (adapter.spansFullWidth(position)) columns else 1
        }
    }

    private fun updateChrome() {
        pill.isVisible = !editing
        editBar.isVisible = editing

        val showBanner = !loading && !failed && loaded.isNotEmpty() &&
            openSnapshot?.exact != true &&
            !ActivityStats.hasAccess(this) && !prefs.dotsOfferDismissed
        findViewById<View>(R.id.dotsBanner).isVisible = showBanner
    }

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

    private fun wireEditBar() {
        findViewById<TextView>(R.id.editDone).setOnClickListener { putDown() }
        findViewById<TextView>(R.id.editReset).setOnClickListener { resetOrder() }
    }

    private fun lift() {
        if (editing) return
        editing = true
        adapter.setEditing(true)
        Motion.tap(grid, Motion.Kind.HOLD)
        updateChrome()
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

    private fun wireReorder() {
        val callback = object : ItemTouchHelper.SimpleCallback(DRAG_DIRECTIONS, 0) {

            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ): Int = if (editing && adapter.isApp(viewHolder.adapterPosition)) {
                makeMovementFlags(DRAG_DIRECTIONS, 0)
            } else {
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
                viewHolder.itemView.elevation = resources.getDimension(R.dimen.elev_raised)
                val order = adapter.appPackages()
                if (order.isNotEmpty()) prefs.pageOrder = order
                Motion.tap(recyclerView, Motion.Kind.KEY)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
        }
        ItemTouchHelper(callback).attachToRecyclerView(grid)
    }

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

    private fun openApp(entry: AppEntry) {
        val exact = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(entry.packageName, entry.activityName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (tryStart(exact)) return

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

    private fun closeApp(entry: AppEntry) {
        val taskId = openSnapshot?.taskId(entry.packageName)
        background {
            val method = ForceStop.close(applicationContext, entry.packageName, taskId)
            post { toast(CloseReport.text(this, method, entry.label)) }
            pauseThenReload()
        }
    }

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

    private fun uninstallApp(entry: AppEntry) {
        background {
            val method = Uninstall.uninstall(applicationContext, entry)
            post { toast(UninstallReport.text(this, method, entry.label)) }
            pauseThenReload()
        }
    }

    private fun pauseThenReload() {
        Thread.sleep(800)
        post { reload() }
    }

    private fun showActions(entry: AppEntry) {
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
            groupStart = !canClose,
            onClick = { hideFromMainPage(entry) },
        )
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
            appIcon = IconCache.drawn(
                resources,
                entry,
                prefs.iconShape,
                resources.getDimensionPixelSize(R.dimen.icon_banner),
            ),
            rows = rows,
        )
    }

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

    private fun sheetSubtitle(entry: AppEntry): CharSequence {
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

    private fun hideFromMainPage(entry: AppEntry) {
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

    private fun togglePin(entry: AppEntry) {
        val wasPinned = pins.isPinned(entry.key)
        if (wasPinned) {
            pins.unpin(entry.key)
        } else {
            pins.pin(entry.key)
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

    private fun openAppInfo(pkg: String) {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$pkg"),
                )
            )
        } catch (e: ActivityNotFoundException) {
        }
    }

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
        private const val SKELETON_ROWS = 3

        private const val LIFT_SCALE = 1.02f

        private const val STAGGER_MILLIS = 30L

        private val DRAG_DIRECTIONS =
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or
                ItemTouchHelper.START or ItemTouchHelper.END
    }
}
