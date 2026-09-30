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

/** A cell on the board: an app, a placeholder, or the one message that spans it. */
sealed interface GridItem {

    data class App(val entry: AppEntry) : GridItem

    /** A tile-shaped placeholder, while the app list is still being read. */
    object Skeleton : GridItem

    /** The whole-width message: nothing installed, nothing matched, or a failure. */
    data class Panel(val kind: PanelKind, val query: String? = null) : GridItem
}

enum class PanelKind { EMPTY, ERROR, NO_MATCH }

/**
 * The board: one tile per app, and three cells that are not apps at all - a
 * skeleton while the list is read, a span-wide panel when there is something to
 * say about why it is empty, and nothing else.
 *
 * The two shortcuts that used to end the grid - a gear and a Close - are gone.
 * A red "quit App Hub" sitting among the app icons is one mis-tap away from
 * opening the wrong thing, and reaching the settings by scrolling past every
 * installed app is a long walk on a car screen. Both live behind the pill that
 * floats over the board instead (see [MainActivity]).
 *
 * Every callback is optional because the same adapter draws the shortcuts
 * screen's preview of this board: there a tap should not launch anything, the
 * hold belongs to the drag, and a cell with no callback behind it is drawn inert
 * rather than left able to ripple - so the preview never promises an action it
 * does not have.
 */
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

    /** true while the board is lifted: the affordance shows, the drag works */
    var editing = false
        private set

    fun configure(iconPx: Int, shape: IconShape, showNames: Boolean) {
        if (iconPx != this.iconPx || shape != this.shape) IconCache.clear()
        this.iconPx = iconPx
        this.shape = shape
        this.showNames = showNames
        notifyDataSetChanged()
    }

    /**
     * Lift the board, or put it down.
     *
     * Not part of [configure]: that one evicts the icon cache when the artwork
     * would change, and lifting only ever changes what is drawn *on* the tiles.
     */
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

    /** true where a cell holds an app, i.e. where the page may be rearranged */
    fun isApp(position: Int): Boolean = items.getOrNull(position) is GridItem.App

    /**
     * Move one cell, which is what a drag does to the page.
     *
     * Only ever called with app positions, so no cell can be dragged past the
     * panel that spans the board.
     */
    fun move(from: Int, to: Int) {
        if (from == to) return
        if (from !in items.indices || to !in items.indices) return
        items.add(to, items.removeAt(from))
        notifyItemMoved(from, to)
    }

    /** the app cells' packages, in the order the board is holding them */
    fun appPackages(): List<String> =
        items.filterIsInstance<GridItem.App>().map { it.entry.packageName }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is GridItem.App -> TYPE_APP
        GridItem.Skeleton -> TYPE_SKELETON
        is GridItem.Panel -> TYPE_PANEL
    }

    /** true for the one cell that must not be squeezed into a single column */
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

    /**
     * Resize a view's box to the icon size the settings ask for.
     *
     * The params are mutated in place rather than replaced: a fresh
     * FrameLayout.LayoutParams would crash inside the skeleton's LinearLayout,
     * and it would drop the tile icon's own layout_gravity, which is what keeps
     * an icon centred over its ring at every size.
     */
    private fun sized(view: View) {
        if (iconPx <= 0) return
        view.layoutParams?.let { params ->
            params.width = iconPx
            params.height = iconPx
            view.layoutParams = params
        }
    }

    /** a square the size the tile's own scale asks for, in pixels */
    private fun sized(view: View, side: Int) {
        if (side <= 0) return
        view.layoutParams?.let { params ->
            params.width = side
            params.height = side
            view.layoutParams = params
        }
    }

    /**
     * The badge, drawn at the icon's own scale but never heavier than the one
     * the design settled on.
     *
     * The icon size is a setting and the badge used to be one size, so on a
     * 48dp icon the same 24dp circle covered half the artwork while on a 96dp
     * one it was a quarter of it - the same badge heavy at one setting and lost
     * at another. It is therefore a fraction of the icon, with the reference
     * size as a *ceiling* rather than a multiplier: an affordance may get
     * smaller with the artwork it sits on, but it must not get bigger than the
     * badge a driver already recognises at 70cm. What carries the accessibility
     * is the target, which is one size whatever the drawing does.
     *
     * The XML still describes the drawing, at the reference icon: mutating the
     * insets of its own layers is what scales that one description, rather than
     * a second description here in code.
     */
    private fun badge(more: ImageView) {
        if (iconPx <= 0) return
        val res = more.resources
        val target = res.getDimensionPixelSize(R.dimen.touch_min)
        // the circle the XML draws at the reference size: target less its inset
        val reference = target - 2 * res.getDimensionPixelSize(R.dimen.badge_inset)
        val circle = min((iconPx * BADGE_FRACTION).roundToInt(), reference)

        val ripple = more.background?.mutate() as? RippleDrawable
        val layer = ripple?.getDrawable(0)?.mutate() as? LayerDrawable
        layer?.setLayerInset(0, (target - circle) / 2, (target - circle) / 2,
            (target - circle) / 2, (target - circle) / 2)

        // the glyph is the second inset, so it is measured against the circle
        // rather than against the target: three dots that fill their badge read
        // as a button rather than as a mark
        val glyph = (circle * BADGE_GLYPH_FRACTION).roundToInt()
        val pad = (target - glyph) / 2
        more.setPadding(pad, pad, pad, pad)
    }

    /**
     * The running mark, the other way up: a floor rather than a ceiling.
     *
     * A dot is not a control to be recognised, it is a state to be read from the
     * driver's seat, so it never goes below the 12dp of the design however small
     * the icon is - and it is allowed to grow with the artwork, because a 12dp
     * dot on a 96dp icon is a mark that has stopped being about that icon.
     */
    private fun dotPx(res: Resources): Int {
        if (iconPx <= 0) return 0
        val floor = res.getDimensionPixelSize(R.dimen.dot_state)
        return max((iconPx * DOT_FRACTION).roundToInt(), floor)
    }

    private fun displayIcon(entry: AppEntry, box: View): Drawable =
        IconCache.drawn(box.resources, entry, shape, iconPx)

    /** the tile: icon, running mark, name, and the lift affordance */
    inner class AppCell(view: View) : RecyclerView.ViewHolder(view) {

        private val iconBox: View = view.findViewById(R.id.iconBox)
        private val icon: ImageView = view.findViewById(R.id.appIcon)
        private val ring: View = view.findViewById(R.id.appRing)
        private val dot: View = view.findViewById(R.id.appDot)
        private val name: TextView = view.findViewById(R.id.appName)
        private val more: ImageView = view.findViewById(R.id.moreButton)

        fun bind(entry: AppEntry) {
            val context = itemView.context

            // The box is the icon, and the icon is the box. Everything the tile
            // hangs off it - the ring that paints outside it, the badge carried
            // out to its corner - is placed against that one number, so nothing
            // in the tile can grow it and none of it drifts when the icon size
            // setting changes.
            sized(iconBox)
            sized(icon)
            sized(ring)
            sized(dot, dotPx(itemView.resources))
            badge(more)

            icon.setImageDrawable(displayIcon(entry, itemView))
            ring.isVisible = entry.state == AppState.RUNNING

            // What is open is solid, what was merely used is hollow: the two
            // exact sources - a process, and a task the system still holds -
            // fill the dot, and the usage view only outlines it.
            dot.isVisible = entry.state != AppState.IDLE && !editing
            dot.setBackgroundResource(
                if (entry.state == AppState.RECENT) R.drawable.bg_dot_recent else R.drawable.bg_dot
            )

            name.isVisible = showNames
            name.text = entry.label

            // The state belongs to the tile's own description. Announcing a
            // 12dp dot separately told a screen reader that an image called
            // "Running" existed somewhere near an app name.
            itemView.contentDescription = describe(entry)

            more.isVisible = editing
            if (editing) {
                more.contentDescription = context.getString(R.string.tile_more, entry.label)
                more.setOnClickListener { onMore?.invoke(entry) }
            }

            // The preview's cells are for looking at: a ripple with nothing
            // behind it would promise an action, and a long-press listener would
            // swallow the hold that starts the drag.
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

    /**
     * The tile-shaped placeholder: sized like a real one, so nothing jumps when
     * the icons land.
     *
     * It breathes - opacity only, in and out over a second. A shimmer sweep is a
     * bright moving streak on a windscreen, and a board that does not move at all
     * while it is loading reads as a board that is broken.
     */
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
        // the animation must not outlive the cell: a placeholder that is gone
        // should not still be animating
        (holder as? SkeletonCell)?.stopPulse()
        super.onViewDetachedFromWindow(holder)
    }

    /**
     * The one message the board has to give, and the one step out of it.
     *
     * Three different situations used to share a single sentence. They are not
     * the same: nothing installed is a welcome, an unreadable app list is a
     * failure, and "nothing matched" is an answer to what was typed.
     */
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

            // the empty board states the number out loud rather than implying it
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

            // one node, one sentence: a panel is not a list of four texts
            itemView.contentDescription = listOf(title.text, message.text)
                .filter { it.isNotEmpty() }
                .joinToString(". ")
        }
    }

    private companion object {
        const val TYPE_APP = 0
        const val TYPE_SKELETON = 1
        const val TYPE_PANEL = 2

        /** the placeholder's resting and breathing opacity */
        const val RESTING = 0.55f
        const val DIMMED = 0.3f
        const val PULSE_MILLIS = 1000L

        /**
         * The tile's marks as fractions of the icon, measured off the design at
         * the reference size: a 24dp badge and a 12dp dot on a 64dp icon, and a
         * 20dp glyph inside the badge.
         */
        const val BADGE_FRACTION = 24f / 64f
        const val BADGE_GLYPH_FRACTION = 20f / 24f
        const val DOT_FRACTION = 12f / 64f
    }
}
