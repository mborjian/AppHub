package com.mimskydo.apphub

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import kotlin.math.max
import kotlin.math.min

/**
 * One tappable line inside a [Sheet].
 *
 *  * `selected` is the value the sheet is currently holding (a circle-check, and
 *    announced as such);
 *  * `danger` is something that takes an app away - amber, because it can be
 *    undone or re-opened;
 *  * `destroy` is the yes-button of a question that has already been asked - the
 *    only red in the app.
 */
class SheetRow(
    val label: CharSequence,
    val subtitle: CharSequence? = null,
    val icon: Drawable? = null,
    val selected: Boolean = false,
    val danger: Boolean = false,
    val destroy: Boolean = false,
    /** false for an app icon, which is drawn in its own colours */
    val tintIcon: Boolean = true,
    /** draw a hairline above this row: it starts a new group of verbs */
    val groupStart: Boolean = false,
    val onClick: (() -> Unit)? = null,
)

/**
 * A rounded, floating card - used as the app card, as the tools menu, as the
 * picker behind a list-valued setting, and as the wheel behind a numeric one.
 *
 * Deliberately not an AlertDialog: this way the corner radius, the icon column
 * and the check mark are ours, and every use looks identical. Two shapes come
 * out of it: [show] floats in the middle for a question, [showBottom] sits at
 * the bottom edge for a menu, where a thumb already is.
 */
object Sheet {

    /** the dialog plus the places a caller fills: its card, its rows and its footer */
    private class Frame(
        val dialog: Dialog,
        val card: LinearLayout,
        val rows: LinearLayout,
        val footer: LinearLayout,
        val scroller: ScrollView,
        /** where the card rests when nothing is in the way */
        val gravity: Int,
    )

    /**
     * A floating list of choices, centred: a question with a few answers.
     * Tapping a row runs it and closes the sheet.
     */
    fun show(
        context: Context,
        title: CharSequence?,
        subtitle: CharSequence? = null,
        message: CharSequence? = null,
        appIcon: Drawable? = null,
        rows: List<SheetRow>,
    ): Dialog {
        val frame = frame(context, title, subtitle, message, appIcon, Gravity.CENTER)
        val inflater = LayoutInflater.from(context)
        for (row in rows) {
            if (row.groupStart) frame.rows.addView(divider(inflater, frame.rows))
            frame.rows.addView(rowView(inflater, context, frame.dialog, frame.rows, row))
        }
        return present(frame)
    }

    /**
     * The same card at the bottom edge: where a menu of tools lives.
     *
     * A menu is not a question. It belongs where the thumb is, it leaves the
     * board it is about visible behind it, and it is dismissed by the same tap
     * that opened it, which is what "bottom sheet" has come to mean.
     */
    fun showBottom(
        context: Context,
        rows: List<SheetRow>,
        title: CharSequence? = null,
        subtitle: CharSequence? = null,
    ): Dialog {
        val frame = frame(context, title, subtitle, null, null, Gravity.BOTTOM)
        val inflater = LayoutInflater.from(context)
        for (row in rows) {
            if (row.groupStart) frame.rows.addView(divider(inflater, frame.rows))
            frame.rows.addView(rowView(inflater, context, frame.dialog, frame.rows, row))
        }
        return present(frame)
    }

    /**
     * A floating wheel over a numeric value, with no list of its own.
     *
     * Every whole number in `minValue..maxValue` is reachable by dragging the
     * column or by the two buttons - which is the point: a value in dp has no
     * useful set of round steps to offer. [onValue] fires on every change, so a
     * caller can show the result while the value is still being picked.
     */
    fun showNumber(
        context: Context,
        title: CharSequence,
        subtitle: CharSequence? = null,
        minValue: Int,
        maxValue: Int,
        value: Int,
        unit: CharSequence,
        done: CharSequence,
        extraRows: List<SheetRow> = emptyList(),
        onValue: (Int) -> Unit,
    ): Dialog {
        val frame = frame(context, title, subtitle, null, null, Gravity.CENTER)

        val inflater = LayoutInflater.from(context)
        val wheel = inflater.inflate(R.layout.dialog_number, frame.card, false)
        val picker = wheel.findViewById<NumberPicker>(R.id.numberPicker)
        picker.minValue = minValue
        picker.maxValue = max(maxValue, minValue)
        picker.value = value.coerceIn(picker.minValue, picker.maxValue)
        // wrapping would let "left: 512" become "left: 0" with one flick
        picker.wrapSelectorWheel = false
        // a stray tap should not turn the wheel into a text field
        picker.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        picker.setFormatter { "$it $unit" }
        picker.setOnValueChangedListener { _, _, now -> onValue(now) }

        // between the header and the rows
        frame.card.addView(wheel, 1)

        // Whatever else the caller wants next to the wheel (the typed entry,
        // for one), then the row that closes it. Closing is all that row does:
        // every turn of the wheel was applied already.
        for (row in extraRows) {
            frame.rows.addView(rowView(inflater, context, frame.dialog, frame.rows, row))
        }
        addFooterRow(inflater, context, frame, SheetRow(label = done))

        return present(frame)
    }

    /**
     * The same value, typed instead of turned.
     *
     * A wheel is quick for nudging; a keyboard is quicker when the number is
     * already known ("the rail is 96 dp wide"). The field is numeric, opens
     * focused with its content selected and the keyboard up, and both the IME's
     * Done key and the OK row submit. Anything that does not parse is not a
     * value: the sheet closes and the stored one stays as it was.
     */
    fun showInput(
        context: Context,
        title: CharSequence,
        subtitle: CharSequence? = null,
        value: Int,
        unit: CharSequence,
        submit: CharSequence,
        cancel: CharSequence,
        onSubmit: (Int) -> Unit,
    ): Dialog {
        val frame = frame(context, title, subtitle, null, null, Gravity.CENTER)
        val inflater = LayoutInflater.from(context)
        val fieldView = inflater.inflate(R.layout.dialog_input, frame.card, false)
        val field = fieldView.findViewById<EditText>(R.id.inputValue)
        fieldView.findViewById<TextView>(R.id.inputUnit).text = unit

        field.setText(value.toString())
        field.selectAll()
        // the field is what the sheet is for, so it takes focus (and the given
        // keyboard) without anyone having to aim at it first
        field.requestFocus()

        // The one place the typed text becomes a value, and the sheet closes
        // with it: junk simply is not a value, so the stored one stays as it
        // was. Enter reaches this by three routes, because keyboards differ:
        // the IME's Done action, a bare Enter on a single-line field (which
        // arrives as IME_NULL), and the row below.
        fun accept() {
            field.text.toString().trim().toIntOrNull()?.let(onSubmit)
            frame.dialog.dismiss()
        }

        field.setOnEditorActionListener { _, action, _ ->
            val done = action == EditorInfo.IME_ACTION_DONE || action == EditorInfo.IME_NULL
            if (done) accept()
            done
        }
        // a keyboard whose Enter arrives as a key event instead (USB ones and
        // the emulator do)
        field.setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                accept()
                true
            } else {
                false
            }
        }

        frame.card.addView(fieldView, 1)
        addFooterRow(inflater, context, frame, SheetRow(
            label = submit,
            onClick = { accept() },
        ))
        addFooterRow(inflater, context, frame, SheetRow(label = cancel))

        frame.dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        return present(frame)
    }

    // ------------------------------------------------------------- internals

    /** a row below the scrolling list: always visible, whatever the list does */
    private fun addFooterRow(
        inflater: LayoutInflater,
        context: Context,
        frame: Frame,
        row: SheetRow,
    ) {
        frame.footer.addView(rowView(inflater, context, frame.dialog, frame.footer, row))
        frame.footer.isVisible = true
    }

    /** the hairline that separates a group of verbs from the one before it */
    private fun divider(inflater: LayoutInflater, container: ViewGroup): View =
        inflater.inflate(R.layout.dialog_sheet_divider, container, false)

    /**
     * The empty card: header filled in, no rows. The sheet is shown with the
     * rows already in place, so every style rule stays in the XML.
     */
    private fun frame(
        context: Context,
        title: CharSequence?,
        subtitle: CharSequence?,
        message: CharSequence?,
        appIcon: Drawable?,
        gravity: Int,
    ): Frame {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_sheet, null)
        val header = view.findViewById<View>(R.id.sheetHeader)
        val iconView = view.findViewById<ImageView>(R.id.sheetIcon)
        val titleView = view.findViewById<TextView>(R.id.sheetTitle)
        val subtitleView = view.findViewById<TextView>(R.id.sheetSubtitle)
        val messageView = view.findViewById<TextView>(R.id.sheetMessage)

        if (appIcon != null) {
            iconView.setImageDrawable(appIcon)
        } else {
            iconView.isVisible = false
        }
        titleView.text = title
        titleView.isVisible = !title.isNullOrEmpty()
        subtitleView.text = subtitle
        subtitleView.isVisible = !subtitle.isNullOrEmpty()
        messageView.text = message
        messageView.isVisible = !message.isNullOrEmpty()

        // The header goes away with its contents. It is a row with a minimum
        // height of one target, so with every view inside it gone it still held
        // 56dp + padding of nothing above the first row - which is what a menu
        // with no title was wearing: a strip of empty card as tall as a thumb.
        header.isVisible = appIcon != null || !title.isNullOrEmpty() || !subtitle.isNullOrEmpty()

        val dialog = Dialog(context, R.style.SheetDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(view)

        return Frame(
            dialog = dialog,
            card = view.findViewById(R.id.sheetCard),
            rows = view.findViewById(R.id.sheetRows),
            footer = view.findViewById(R.id.sheetFooter),
            scroller = view.findViewById(R.id.sheetScroll),
            gravity = gravity,
        )
    }

    /**
     * One row, inflated against its container so it keeps the width the row
     * layout asks for - `inflate(..., null, false)` would hand the parent no
     * layout params and every row would shrink to wrap_content.
     */
    private fun rowView(
        inflater: LayoutInflater,
        context: Context,
        dialog: Dialog,
        container: ViewGroup,
        row: SheetRow,
    ): View {
        val rowView = inflater.inflate(R.layout.dialog_sheet_row, container, false)
        val label = rowView.findViewById<TextView>(R.id.rowLabel)
        label.text = row.label
        rowView.findViewById<TextView>(R.id.rowSubtitle).apply {
            text = row.subtitle
            isVisible = !row.subtitle.isNullOrEmpty()
        }
        rowView.findViewById<ImageView>(R.id.rowIcon).apply {
            setImageDrawable(row.icon)
            // set every time: a recycled row would otherwise keep the last
            // icon's tint
            val tone = toneColor(context, row)
            imageTintList = if (row.tintIcon) {
                ColorStateList.valueOf(tone ?: context.getColor(R.color.hub_ink_700))
            } else {
                null
            }
            isVisible = row.icon != null
        }
        // without an icon the text must not stay indented for one
        rowView.findViewById<LinearLayout>(R.id.rowText).let { text ->
            (text.layoutParams as LinearLayout.LayoutParams).marginStart =
                if (row.icon == null) 0 else (context.resources.getDimension(R.dimen.space_4)).toInt()
        }
        rowView.findViewById<ImageView>(R.id.rowCheck).isVisible = row.selected

        toneColor(context, row)?.let { label.setTextColor(it) }

        // Selection is a shape (the circle-check) and a state, not just a tick
        // an eye has to notice: a screen reader announces the current value.
        rowView.isSelected = row.selected
        if (row.selected) {
            rowView.contentDescription = context.getString(R.string.row_selected, row.label)
        }

        // tapping a row runs it and closes the sheet, check mark or not
        rowView.setOnClickListener {
            dialog.dismiss()
            row.onClick?.invoke()
        }
        return rowView
    }

    /**
     * What a row's `danger`/`destroy` mean in colour.
     *
     * Amber for a verb that takes something away and can be undone or re-opened
     * (closing an app, hiding a tile, quitting App Hub), red only for the button
     * that answers a question already asked. In a car, red belongs to the
     * vehicle's own faults.
     */
    private fun toneColor(context: Context, row: SheetRow): Int? = when {
        row.destroy -> context.getColor(R.color.hub_danger)
        row.danger -> context.getColor(R.color.hub_warn)
        else -> null
    }

    private fun present(frame: Frame): Dialog {
        frame.dialog.setOnShowListener {
            placeOnScreen(frame)
            clampRows(frame)
        }
        frame.dialog.show()
        return frame.dialog
    }

    /**
     * A list longer than the screen scrolls instead of growing the card past the
     * bottom of the window. Measured a moment after the sheet is shown, because
     * the rows only have their height then.
     *
     * The keyboard takes the space back: [WindowInsetsCompat] says how much of
     * the window the IME is covering, and the rows give up exactly that much, so
     * the card stays above the keypad instead of being drawn under it - which is
     * what the sheet that types a margin in needs.
     */
    private fun clampRows(frame: Frame) {
        val scroller = frame.scroller
        val metrics = frame.dialog.context.resources.displayMetrics
        val limit = (metrics.heightPixels * MAX_ROWS_HEIGHT).toInt()
        val minRows = (MIN_ROWS_HEIGHT * metrics.density).toInt()

        // what the card is made of around the rows - header, message, footer,
        // padding. Read once: shrinking the rows shrinks the card with them, so
        // it cannot be measured again later.
        var chrome = 0
        // the height the list wants, before anything caps it
        var natural = 0

        fun apply(room: Int) {
            val cap = min(limit, room)
            val height = if (cap >= natural) {
                // the list fits as it is; a short sheet stays wrap_content
                ViewGroup.LayoutParams.WRAP_CONTENT
            } else {
                max(cap, minRows)
            }
            if (scroller.layoutParams.height != height) {
                scroller.layoutParams = scroller.layoutParams.apply { this.height = height }
            }
        }

        // the frame's own padding: room the card has, but the rows cannot use
        val frameMargin = (FRAME_PADDING * metrics.density).toInt()
        var atTop = false

        scroller.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    scroller.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    natural = scroller.height
                    chrome = frame.card.height - scroller.height
                    apply(limit)

                    val decor = frame.dialog.window?.decorView ?: return
                    ViewCompat.setOnApplyWindowInsetsListener(decor) { view, insets ->
                        val keyboard = insets.isVisible(WindowInsetsCompat.Type.ime())
                        // how much of the window the keypad is not covering
                        val free = view.height -
                            insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                        // While the keyboard is up the card goes to the top of the
                        // screen: left centred it would be pushed under the keypad
                        // and the keys would be under the card in turn. Down
                        // again, it rests where its own shape says it should.
                        if (keyboard != atTop) {
                            atTop = keyboard
                            frame.dialog.window?.setGravity(
                                if (keyboard) Gravity.TOP else frame.gravity
                            )
                        }
                        apply(if (keyboard) free - chrome - frameMargin else limit)
                        insets
                    }
                }
            }
        )
    }

    /**
     * How much of the screen the scrolling rows may take. The card also has a
     * header and the pinned footer around them. The number is set by what the
     * app actually asks for: the longest menu is six rows, and six car-sized
     * rows have to fit on the unit's own 1024x600 panel without scrolling -
     * a verb the driver cannot see is a verb that is not there.
     */
    private const val MAX_ROWS_HEIGHT = 0.72f

    /**
     * The least the rows shrink to for the keyboard. A couple of lines: less
     * than that and there is nothing left to filter, so the card would overlap
     * the keypad a little rather than become useless.
     */
    private const val MIN_ROWS_HEIGHT = 60f

    /** the frame's own padding, which the card has but the rows cannot use */
    private const val FRAME_PADDING = 12f

    /**
     * The window wants to match the screen; a 10" head unit would otherwise
     * stretch a four-line menu across the whole display. The bottom variant gets
     * the whole width up to the same cap and sits on the bottom edge.
     */
    private fun placeOnScreen(frame: Frame) {
        val window = frame.dialog.window ?: return
        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        val context = frame.dialog.context
        val density = context.resources.displayMetrics.density
        val screen = context.resources.displayMetrics.widthPixels

        val inset = if (frame.gravity == Gravity.BOTTOM) {
            context.resources.getDimension(R.dimen.sheet_edge_inset).toInt()
        } else {
            (64 * density).toInt()
        }
        val cap = context.resources.getDimension(R.dimen.sheet_max_width).toInt()
        val floor = context.resources.getDimension(R.dimen.sheet_min_width).toInt()
        val width = min(screen - inset, cap)

        window.setGravity(frame.gravity)
        window.setLayout(max(width, floor), ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
