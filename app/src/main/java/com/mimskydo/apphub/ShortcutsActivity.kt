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

class ShortcutsActivity : BaseActivity() {

    private val prefs by lazy { Prefs(this) }
    private val pins by lazy { PinnedApps(this) }

    private val worker = Executors.newSingleThreadExecutor()

    private var apps: List<AppEntry>? = null

    private lateinit var list: RecyclerView

    private lateinit var listEmpty: View
    private lateinit var count: TextView
    private lateinit var preview: RecyclerView

    private val listAdapter = ShortcutAdapter(
        checked = { entry -> !prefs.isHidden(entry.key) },
        onToggle = ::toggle,
        shape = { prefs.iconShape },
    )

    private val previewAdapter = AppAdapter(
        onOpen = null,
        onMore = null,
        onHold = null,
        onPanelAction = null,
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
        }
    }

    private fun refresh() {
        listAdapter.notifyDataSetChanged()
        count.text = countText()
        submitPreview()
    }

    private fun countText(): CharSequence {
        val all = apps ?: return getString(R.string.loading)
        if (all.isEmpty()) return getString(R.string.none)
        val shown = all.count { !prefs.isHidden(it.key) }
        return if (shown == all.size) {
            getString(R.string.shortcuts_all, all.size)
        } else {
            getString(R.string.shortcuts_count, shown, all.size)
        }
    }

    private fun submitPreview() {
        val all = apps ?: return
        val ordered = MainPage.page(
            context = this,
            apps = all.filter { !prefs.isHidden(it.key) },
            order = prefs.pageOrder,
            pinned = pins.all(),
        )
        previewAdapter.submit(MainPage.cells(ordered))
    }

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
            val title = findViewById<TextView>(R.id.listEmptyTitle)
            title.setText(if (apps?.isEmpty() != false) R.string.empty_title else R.string.filter_none)
        }
    }

    private fun wireBulk() {
        val all = findViewById<TextView>(R.id.selectAll)
        val none = findViewById<TextView>(R.id.selectNone)

        all.setOnClickListener {
            val packages = apps?.map { it.key } ?: return@setOnClickListener
            prefs.setHiddenAll(packages, hidden = false)
            refresh()
        }
        none.setOnClickListener {
            val packages = apps?.map { it.key } ?: return@setOnClickListener
            prefs.setHiddenAll(packages, hidden = true)
            refresh()
        }
    }

    private fun toggle(entry: AppEntry) {
        prefs.setHidden(entry.key, !prefs.isHidden(entry.key))
        refresh()
    }

    private fun sizePreview() {
        val density = resources.displayMetrics.density
        val width = preview.width
        if (width <= 0) return

        val columns = prefs.columnCount((width / density).toInt())
        val wanted = (prefs.iconSize.dp * density).roundToInt()
        val room = width / columns - (CELL_CHROME_DP * density).toInt()
        val iconPx = min(wanted, max(room, (MIN_ICON_DP * density).roundToInt()))

        previewAdapter.configure(iconPx, prefs.iconShape, prefs.showNames)
        preview.layoutManager = gridLayoutManager(columns)
    }

    private fun gridLayoutManager(columns: Int) = GridLayoutManager(this, columns).apply {
        spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (previewAdapter.spansFullWidth(position)) columns else 1
        }
    }

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

    private fun saveOrder() {
        val order = previewAdapter.appPackages()
        if (order.isEmpty()) return
        prefs.pageOrder = order
    }

    private companion object {
        val DRAG_DIRECTIONS =
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or
                ItemTouchHelper.START or ItemTouchHelper.END

        const val CELL_CHROME_DP = 24

        const val MIN_ICON_DP = 28
    }
}

private class ShortcutAdapter(
    private val checked: (AppEntry) -> Boolean,
    private val onToggle: (AppEntry) -> Unit,
    private val shape: () -> IconShape,
) : RecyclerView.Adapter<ShortcutAdapter.Row>() {

    private val all = ArrayList<AppEntry>()
    private val showing = ArrayList<AppEntry>()
    private val keys = HashMap<String, String>()

    fun submit(apps: List<AppEntry>) {
        all.clear()
        all.addAll(apps)
        keys.clear()
        for (entry in apps) keys[entry.key] = Filter.key(entry.label, entry.key)
        showing.clear()
        showing.addAll(apps)
        notifyDataSetChanged()
    }

    fun filter(needle: String) {
        showing.clear()
        for (entry in all) {
            if (needle.isEmpty() || keys[entry.key]?.contains(needle) == true) {
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
            icon.setImageDrawable(IconCache.drawn(itemView.resources, entry, shape(), rowIconPx))
            label.text = entry.label
            subtitle.text = entry.packageName
            check.isVisible = checked(entry)

            itemView.contentDescription = entry.label
            itemView.setOnClickListener { onToggle(entry) }
        }
    }
}
