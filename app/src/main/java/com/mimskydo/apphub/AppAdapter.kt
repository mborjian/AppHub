package com.mimskydo.apphub

import android.animation.ObjectAnimator
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

sealed interface GridItem {

    data class App(val entry: AppEntry) : GridItem

    object Skeleton : GridItem

    data class Panel(val kind: PanelKind, val query: String? = null) : GridItem
}

enum class PanelKind { EMPTY, ERROR, NO_MATCH }

class AppAdapter(
    private val onOpen: ((AppEntry) -> Unit)?,
    private val onMore: ((AppEntry) -> Unit)?,
    private val onHold: ((AppEntry) -> Unit)?,
    private val onPanelAction: ((PanelKind) -> Unit)?,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = ArrayList<GridItem>()

    private var iconPx = 0
    private var shape = IconShape.ROUNDED
    private var showNames = true

    var editing = false
        private set

    fun configure(iconPx: Int, shape: IconShape, showNames: Boolean) {
        if (iconPx != this.iconPx || shape != this.shape) IconCache.clear()
        this.iconPx = iconPx
        this.shape = shape
        this.showNames = showNames
        notifyDataSetChanged()
    }

    fun setEditing(value: Boolean) {
        if (editing == value) return
        editing = value
        notifyDataSetChanged()
    }

    fun submit(next: List<GridItem>) {
        items.clear()
        items.addAll(next)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    fun isApp(position: Int): Boolean = items.getOrNull(position) is GridItem.App

    fun move(from: Int, to: Int) {
        if (from == to) return
        if (from !in items.indices || to !in items.indices) return
        items.add(to, items.removeAt(from))
        notifyItemMoved(from, to)
    }

    fun appPackages(): List<String> =
        items.filterIsInstance<GridItem.App>().map { it.entry.key }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is GridItem.App -> TYPE_APP
        GridItem.Skeleton -> TYPE_SKELETON
        is GridItem.Panel -> TYPE_PANEL
    }

    fun spansFullWidth(position: Int): Boolean =
        items.getOrNull(position) is GridItem.Panel

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_PANEL -> PanelCell(inflater.inflate(R.layout.item_panel, parent, false))
            TYPE_SKELETON -> SkeletonCell(inflater.inflate(R.layout.item_skeleton, parent, false))
            else -> AppCell(inflater.inflate(R.layout.item_app, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is GridItem.App -> (holder as AppCell).bind(item.entry)
            is GridItem.Panel -> (holder as PanelCell).bind(item)
            GridItem.Skeleton -> (holder as SkeletonCell).resize()
        }
    }

    private fun sized(view: View) {
        if (iconPx <= 0) return
        view.layoutParams?.let { params ->
            params.width = iconPx
            params.height = iconPx
            view.layoutParams = params
        }
    }

    private fun sized(view: View, side: Int) {
        if (side <= 0) return
        view.layoutParams?.let { params ->
            params.width = side
            params.height = side
            view.layoutParams = params
        }
    }

    private fun badge(more: ImageView) {
        if (iconPx <= 0) return
        val res = more.resources
        val target = res.getDimensionPixelSize(R.dimen.touch_min)
        val reference = target - 2 * res.getDimensionPixelSize(R.dimen.badge_inset)
        val circle = min((iconPx * BADGE_FRACTION).roundToInt(), reference)

        val ripple = more.background?.mutate() as? RippleDrawable
        val layer = ripple?.getDrawable(0)?.mutate() as? LayerDrawable
        layer?.setLayerInset(0, (target - circle) / 2, (target - circle) / 2,
            (target - circle) / 2, (target - circle) / 2)

        val glyph = (circle * BADGE_GLYPH_FRACTION).roundToInt()
        val pad = (target - glyph) / 2
        more.setPadding(pad, pad, pad, pad)
    }

    private fun dotPx(res: Resources): Int {
        if (iconPx <= 0) return 0
        val floor = res.getDimensionPixelSize(R.dimen.dot_state)
        return max((iconPx * DOT_FRACTION).roundToInt(), floor)
    }

    private fun displayIcon(entry: AppEntry, box: View): Drawable =
        IconCache.drawn(box.resources, entry, shape, iconPx)

    inner class AppCell(view: View) : RecyclerView.ViewHolder(view) {

        private val iconBox: View = view.findViewById(R.id.iconBox)
        private val icon: ImageView = view.findViewById(R.id.appIcon)
        private val ring: View = view.findViewById(R.id.appRing)
        private val dot: View = view.findViewById(R.id.appDot)
        private val name: TextView = view.findViewById(R.id.appName)
        private val more: ImageView = view.findViewById(R.id.moreButton)

        fun bind(entry: AppEntry) {
            val context = itemView.context

            sized(iconBox)
            sized(icon)
            sized(ring)
            sized(dot, dotPx(itemView.resources))
            badge(more)

            icon.setImageDrawable(displayIcon(entry, itemView))
            ring.isVisible = entry.state == AppState.RUNNING

            dot.isVisible = entry.state != AppState.IDLE && !editing
            dot.setBackgroundResource(
                if (entry.state == AppState.RECENT) R.drawable.bg_dot_recent else R.drawable.bg_dot
            )

            name.isVisible = showNames
            name.text = entry.label

            itemView.contentDescription = describe(entry)

            more.isVisible = editing
            if (editing) {
                more.contentDescription = context.getString(R.string.tile_more, entry.label)
                more.setOnClickListener { onMore?.invoke(entry) }
            }

            if (onOpen != null) {
                itemView.isClickable = true
                itemView.setOnClickListener { onOpen.invoke(entry) }
            } else {
                itemView.isClickable = false
                itemView.isFocusable = false
            }
            itemView.setOnLongClickListener(
                onHold?.let { hold ->
                    View.OnLongClickListener {
                        hold(entry)
                        true
                    }
                }
            )
        }

        private fun describe(entry: AppEntry): CharSequence {
            val context = itemView.context
            return when (entry.state) {
                AppState.RUNNING -> context.getString(R.string.tile_running, entry.label)
                AppState.OPEN -> context.getString(R.string.tile_open, entry.label)
                AppState.RECENT -> context.getString(R.string.tile_recent, entry.label)
                AppState.IDLE -> entry.label
            }
        }
    }

    inner class SkeletonCell(view: View) : RecyclerView.ViewHolder(view) {

        private var pulse: ObjectAnimator? = null

        fun resize() {
            sized(itemView.findViewById(R.id.skeletonIcon))
        }

        fun startPulse() {
            val running = pulse ?: ObjectAnimator.ofFloat(itemView, "alpha", RESTING, DIMMED)
                .apply {
                    duration = PULSE_MILLIS
                    interpolator = Motion.standard()
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ObjectAnimator.REVERSE
                }
                .also { pulse = it }
            if (!running.isStarted) running.start()
        }

        fun stopPulse() {
            pulse?.cancel()
            pulse = null
            itemView.alpha = RESTING
        }
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        (holder as? SkeletonCell)?.startPulse()
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        (holder as? SkeletonCell)?.stopPulse()
        super.onViewDetachedFromWindow(holder)
    }

    inner class PanelCell(view: View) : RecyclerView.ViewHolder(view) {

        private val icon: ImageView = view.findViewById(R.id.panelIcon)
        private val numeral: TextView = view.findViewById(R.id.panelNumeral)
        private val title: TextView = view.findViewById(R.id.panelTitle)
        private val message: TextView = view.findViewById(R.id.panelMessage)
        private val action: TextView = view.findViewById(R.id.panelAction)

        fun bind(item: GridItem.Panel) {
            val context = itemView.context

            @DrawableRes val iconRes: Int? = when (item.kind) {
                PanelKind.EMPTY -> null
                PanelKind.ERROR -> R.drawable.ic_warning
                PanelKind.NO_MATCH -> R.drawable.ic_search
            }
            icon.isVisible = iconRes != null
            iconRes?.let { icon.setImageResource(it) }

            numeral.isVisible = item.kind == PanelKind.EMPTY
            if (item.kind == PanelKind.EMPTY) {
                numeral.text = context.resources.getQuantityString(R.plurals.apps_count, 0, 0)
            }

            title.text = when (item.kind) {
                PanelKind.EMPTY -> context.getString(R.string.empty_title)
                PanelKind.ERROR -> context.getString(R.string.load_failed_title)
                PanelKind.NO_MATCH ->
                    context.getString(R.string.no_match_title, item.query.orEmpty())
            }
            message.setText(
                when (item.kind) {
                    PanelKind.EMPTY -> R.string.empty_message
                    PanelKind.ERROR -> R.string.load_failed_message
                    PanelKind.NO_MATCH -> R.string.no_match_message
                }
            )

            val hasAction = onPanelAction != null && item.kind != PanelKind.NO_MATCH
            action.isVisible = hasAction
            if (hasAction) {
                action.setText(
                    when (item.kind) {
                        PanelKind.EMPTY -> R.string.open_settings
                        else -> R.string.try_again
                    }
                )
                action.setOnClickListener { onPanelAction?.invoke(item.kind) }
            }

            itemView.contentDescription = listOf(title.text, message.text)
                .filter { it.isNotEmpty() }
                .joinToString(". ")
        }
    }

    private companion object {
        const val TYPE_APP = 0
        const val TYPE_SKELETON = 1
        const val TYPE_PANEL = 2

        const val RESTING = 0.55f
        const val DIMMED = 0.3f
        const val PULSE_MILLIS = 1000L

        const val BADGE_FRACTION = 24f / 64f
        const val BADGE_GLYPH_FRACTION = 20f / 24f
        const val DOT_FRACTION = 12f / 64f
    }
}
