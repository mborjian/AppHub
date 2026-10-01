package com.mimskydo.apphub

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Which apps the main page shows, and in which order: the list on the left, the
 * page it makes on the right.
 *
 * Both halves are views of one stored state - the hidden set, which decides what
 * has a cell, and the page order, which decides where the cells sit - so every
 * tick, every All/None tap and every drag redraws the preview from the stored
 * values rather than patching it. That is also why the page is arranged by
 * [MainPage] here: this preview is the grid, drawn smaller, and the two can
 * never disagree about what the user is about to get.
 *
 * The filter is the one remembered thing: it is still there the next time the
 * screen is opened, so a session spent working through the apps that start with
 * "ca" does not have to be re-typed. The count in the header always says how
 * many are showing, so a narrowed list never looks like a short one.
 */
class ShortcutsActivity : BaseActivity() {

    private val prefs by lazy { Prefs(this) }
    private val pins by lazy { PinnedApps(this) }

    /** reads the app list off the main thread, like the grid does */
    private val worker = Executors.newSingleThreadExecutor()

    /** every installed app the page could show, or null while they are read */
    private var apps: List<AppEntry>? = null

    private lateinit var list: RecyclerView

    /** the two-line panel that answers a filter with nothing in it */
    private lateinit var listEmpty: View
    private lateinit var count: TextView
    private lateinit var preview: RecyclerView

    private val listAdapter = ShortcutAdapter(
        checked = { entry -> !prefs.isHidden(entry.packageName) },
        onToggle = ::toggle,
        shape = { prefs.iconShape },
    )

    private val previewAdapter = AppAdapter(
        onOpen = null,          // the preview is not a launcher
        onMore = null,          // nothing on a preview tile is a button
        onHold = null,          // the hold belongs to the drag
        onPanelAction = null,   // and its empty panel offers nothing
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shortcuts)

        applyMargins()

        list = findViewById(R.id.appList)
        listEmpty = findViewById(R.id.listEmpty)
        count = findViewById(R.id.headerTrailing)
        preview = findViewById(R.id.previewGrid)

        findViewById<TextView>(R.id.headerTitle).setText(R.string.set_shortcuts_title)
        count.isVisible = true
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = listAdapter
        preview.adapter = previewAdapter

        // The pane's width is what decides the preview's column count and the
        // size the icons fit in, and it is only known after the first layout.
        preview.doOnLayout { sizePreview() }

        wireFilter()
        wireBulk()
        wireReorder()

        loadApps()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------ data flow

    private fun loadApps() {
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
                    listAdapter.submit(read)
                    applyFilter(prefs.shortcutsFilter)
                    refresh()
                }
            }
        } catch (t: Throwable) {
            // the executor was already shut down while finishing
        }
    }

    /**
     * The stored state, drawn again: the ticks that changed, the count, and the
     * page the two of them add up to.
     */
    private fun refresh() {
        listAdapter.notifyDataSetChanged()
        count.text = countText()
        submitPreview()
    }

    private fun countText(): CharSequence {
        val all = apps ?: return getString(R.string.loading)
        if (all.isEmpty()) return getString(R.string.none)
        val shown = all.count { !prefs.isHidden(it.packageName) }
        return if (shown == all.size) {
            getString(R.string.shortcuts_all, all.size)
        } else {
            getString(R.string.shortcuts_count, shown, all.size)
        }
    }

    /** the main page as it will be drawn, in the pane that draws it */
    private fun submitPreview() {
        val all = apps ?: return
        // the whole page, this app's own file-manager tile included while that
        // screen is switched on: the preview is a picture of the board, and a
        // picture that is missing a tile is a picture of another board
        val ordered = MainPage.page(
            context = this,
            apps = all.filter { !prefs.isHidden(it.packageName) },
            order = prefs.pageOrder,
            pinned = pins.all(),
        )
        previewAdapter.submit(MainPage.cells(ordered))
    }

    // -------------------------------------------------------------- filter

    /**
     * The field, filled in with what it was left holding.
     *
     * Typing writes through to [Prefs] as it happens: the filter is a view of a
     * stored value like every tick on this screen, so there is no separate
     * moment at which it is "saved".
     */
    private fun wireFilter() {
        val field = findViewById<EditText>(R.id.filterField)
        val clear = findViewById<ImageView>(R.id.filterClear)

        field.hint = getString(R.string.filter_hint)
        field.doAfterTextChanged { text ->
            val typed = text?.toString().orEmpty()
            prefs.shortcutsFilter = typed
            clear.isVisible = typed.isNotEmpty()
            applyFilter(typed)
        }
        // attaching the listener first means the remembered text filters the
        // list as it arrives rather than sitting in the field doing nothing
        field.setText(prefs.shortcutsFilter)
        field.setSelection(field.text.length)
        clear.isVisible = prefs.shortcutsFilter.isNotEmpty()
        clear.setOnClickListener { field.setText("") }
    }

    private fun applyFilter(typed: String) {
        listAdapter.filter(Filter.key(typed))
        val none = listAdapter.itemCount == 0
        listEmpty.isVisible = none
        if (none) {
            // an empty list means one of two very different things
            val title = findViewById<TextView>(R.id.listEmptyTitle)
            title.setText(if (apps?.isEmpty() != false) R.string.empty_title else R.string.filter_none)
        }
    }

    // --------------------------------------------------------------- bulk

    /** The whole page in one tap: every app ticked, or none of them. */
    private fun wireBulk() {
        val all = findViewById<TextView>(R.id.selectAll)
        val none = findViewById<TextView>(R.id.selectNone)

        all.setOnClickListener {
            val packages = apps?.map { it.packageName } ?: return@setOnClickListener
            prefs.setHiddenAll(packages, hidden = false)
            refresh()
        }
        none.setOnClickListener {
            val packages = apps?.map { it.packageName } ?: return@setOnClickListener
            prefs.setHiddenAll(packages, hidden = true)
            refresh()
        }
    }

    private fun toggle(entry: AppEntry) {
        prefs.setHidden(entry.packageName, !prefs.isHidden(entry.packageName))
        refresh()
    }

    // ------------------------------------------------------------ the preview

    /**
     * The pane's own measurements, not the screen's: the icons here are capped
     * to what the cell can hold, and the column count comes from the same rule
     * the grid uses at the width this pane has.
     */
    private fun sizePreview() {
        val density = resources.displayMetrics.density
        val width = preview.width
        if (width <= 0) return

        val columns = prefs.columnCount((width / density).toInt())
        // an explicit column count combined with the largest icons can ask for
        // more than the pane holds; the preview shrinks the tiles rather than
        // overflowing, and says nothing, because the page is what it shows
        val wanted = (prefs.iconSize.dp * density).roundToInt()
        val room = width / columns - (CELL_CHROME_DP * density).toInt()
        val iconPx = min(wanted, max(room, (MIN_ICON_DP * density).roundToInt()))

        previewAdapter.configure(iconPx, prefs.iconShape, prefs.showNames)
        preview.layoutManager = gridLayoutManager(columns)
    }

    /**
     * The grid's own layout manager, empty-state cell and all: the preview has
     * to break its rows exactly the way the page does, or "the third tile"
     * would mean two different things.
     */
    private fun gridLayoutManager(columns: Int) = GridLayoutManager(this, columns).apply {
        spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (previewAdapter.spansFullWidth(position)) columns else 1
        }
    }

    /**
     * Dragging a tile rearranges the page for real.
     *
     * The gear and Close are not the user's to place, so they are not draggable
     * and nothing may be dropped on them; the order is written when the tile is
     * let go, which is also the moment the preview is already showing the result.
     */
    private fun wireReorder() {
        val callback = object : ItemTouchHelper.SimpleCallback(DRAG_DIRECTIONS, 0) {

            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ): Int = if (previewAdapter.isApp(viewHolder.adapterPosition)) {
                makeMovementFlags(DRAG_DIRECTIONS, 0)
            } else {
                makeMovementFlags(0, 0)
            }

            override fun canDropOver(
                recyclerView: RecyclerView,
                current: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean = previewAdapter.isApp(target.adapterPosition)

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                // Only app cells may be carried and only onto one another, so
                // a tile can never be dropped past the gear or onto it - a
                // position or a page that the user never asked for.
                if (!previewAdapter.isApp(from) || !previewAdapter.isApp(to)) return false
                previewAdapter.move(from, to)
                return true
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) {
                super.clearView(recyclerView, viewHolder)
                saveOrder()
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
        }
        ItemTouchHelper(callback).attachToRecyclerView(preview)
    }

    /**
     * The arrangement the pane is holding becomes the page's own order. From
     * here on the page follows it instead of the name sort, and an app with no
     * place in it - one installed or ticked on later - follows at the end.
     */
    private fun saveOrder() {
        val order = previewAdapter.appPackages()
        if (order.isEmpty()) return
        prefs.pageOrder = order
    }

    private companion object {
        val DRAG_DIRECTIONS =
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or
                ItemTouchHelper.START or ItemTouchHelper.END

        /** the cell's own padding and margins, which an icon cannot use */
        const val CELL_CHROME_DP = 24

        /** below this a preview tile stops being recognisable as one */
        const val MIN_ICON_DP = 28
    }
}

/**
 * The list on the left: one row per app, with the tick that decides whether the
 * main page draws it.
 *
 * Filtering hides rows and never touches a tick, so narrowing the list is never
 * a way to lose a choice; a hidden row is still there, it is just not matched.
 * Each row keeps the squashed text it is matched on, so typing does not read the
 * views again on every keystroke.
 */
private class ShortcutAdapter(
    private val checked: (AppEntry) -> Boolean,
    private val onToggle: (AppEntry) -> Unit,
    /** the shape the board is clipping its icons into */
    private val shape: () -> IconShape,
) : RecyclerView.Adapter<ShortcutAdapter.Row>() {

    private val all = ArrayList<AppEntry>()
    private val showing = ArrayList<AppEntry>()
    private val keys = HashMap<String, String>()

    fun submit(apps: List<AppEntry>) {
        all.clear()
        all.addAll(apps)
        keys.clear()
        for (entry in apps) keys[entry.packageName] = Filter.key(entry.label, entry.packageName)
        showing.clear()
        showing.addAll(apps)
        notifyDataSetChanged()
    }

    /** [needle] already squashed; an empty one shows the whole list again */
    fun filter(needle: String) {
        showing.clear()
        for (entry in all) {
            if (needle.isEmpty() || keys[entry.packageName]?.contains(needle) == true) {
                showing += entry
            }
        }
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = showing.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row =
        Row(LayoutInflater.from(parent.context).inflate(R.layout.row_shortcut, parent, false))

    override fun onBindViewHolder(holder: Row, position: Int) {
        holder.bind(showing[position])
    }

    inner class Row(view: View) : RecyclerView.ViewHolder(view) {

        private val icon = view.findViewById<ImageView>(R.id.rowIcon)
        private val label = view.findViewById<TextView>(R.id.rowLabel)
        private val subtitle = view.findViewById<TextView>(R.id.rowSubtitle)
        private val check = view.findViewById<ImageView>(R.id.rowCheck)

        private val rowIconPx = view.resources.getDimensionPixelSize(R.dimen.icon_app_row)

        fun bind(entry: AppEntry) {
            // drawn at the row's own size, and in the shape the board is using:
            // the tick beside an app is a choice about that app, and the icon
            // next to it should be the icon the choice is about
            icon.setImageDrawable(IconCache.drawn(itemView.resources, entry, shape(), rowIconPx))
            label.text = entry.label
            subtitle.text = entry.packageName
            check.isVisible = checked(entry)

            // the tick is the state, so the row describes itself rather than
            // reading its two lines out
            itemView.contentDescription = entry.label
            itemView.setOnClickListener { onToggle(entry) }
        }
    }
}

