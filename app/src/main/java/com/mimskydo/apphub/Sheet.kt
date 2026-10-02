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

class SheetRow(
    val label: CharSequence,
    val subtitle: CharSequence? = null,
    val icon: Drawable? = null,
    val selected: Boolean = false,
    val danger: Boolean = false,
    val destroy: Boolean = false,
    val tintIcon: Boolean = true,
    val groupStart: Boolean = false,
    val onClick: (() -> Unit)? = null,
)

object Sheet {

    private class Frame(
        val dialog: Dialog,
        val card: LinearLayout,
        val rows: LinearLayout,
        val footer: LinearLayout,
        val scroller: ScrollView,
        val gravity: Int,
    )

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
        picker.wrapSelectorWheel = false
        picker.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        picker.setFormatter { "$it $unit" }
        picker.setOnValueChangedListener { _, _, now -> onValue(now) }

        frame.card.addView(wheel, 1)

        for (row in extraRows) {
            frame.rows.addView(rowView(inflater, context, frame.dialog, frame.rows, row))
        }
        addFooterRow(inflater, context, frame, SheetRow(label = done))

        return present(frame)
    }

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
        field.requestFocus()

        fun accept() {
            field.text.toString().trim().toIntOrNull()?.let(onSubmit)
            frame.dialog.dismiss()
        }

        field.setOnEditorActionListener { _, action, _ ->
            val done = action == EditorInfo.IME_ACTION_DONE || action == EditorInfo.IME_NULL
            if (done) accept()
            done
        }
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

    private fun addFooterRow(
        inflater: LayoutInflater,
        context: Context,
        frame: Frame,
        row: SheetRow,
    ) {
        frame.footer.addView(rowView(inflater, context, frame.dialog, frame.footer, row))
        frame.footer.isVisible = true
    }

    private fun divider(inflater: LayoutInflater, container: ViewGroup): View =
        inflater.inflate(R.layout.dialog_sheet_divider, container, false)

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
            val tone = toneColor(context, row)
            imageTintList = if (row.tintIcon) {
                ColorStateList.valueOf(tone ?: context.getColor(R.color.hub_ink_700))
            } else {
                null
            }
            isVisible = row.icon != null
        }
        rowView.findViewById<LinearLayout>(R.id.rowText).let { text ->
            (text.layoutParams as LinearLayout.LayoutParams).marginStart =
                if (row.icon == null) 0 else (context.resources.getDimension(R.dimen.space_4)).toInt()
        }
        rowView.findViewById<ImageView>(R.id.rowCheck).isVisible = row.selected

        toneColor(context, row)?.let { label.setTextColor(it) }

        rowView.isSelected = row.selected
        if (row.selected) {
            rowView.contentDescription = context.getString(R.string.row_selected, row.label)
        }

        rowView.setOnClickListener {
            dialog.dismiss()
            row.onClick?.invoke()
        }
        return rowView
    }

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

    private fun clampRows(frame: Frame) {
        val scroller = frame.scroller
        val metrics = frame.dialog.context.resources.displayMetrics
        val limit = (metrics.heightPixels * MAX_ROWS_HEIGHT).toInt()
        val minRows = (MIN_ROWS_HEIGHT * metrics.density).toInt()

        var chrome = 0
        var natural = 0

        fun apply(room: Int) {
            val cap = min(limit, room)
            val height = if (cap >= natural) {
                ViewGroup.LayoutParams.WRAP_CONTENT
            } else {
                max(cap, minRows)
            }
            if (scroller.layoutParams.height != height) {
                scroller.layoutParams = scroller.layoutParams.apply { this.height = height }
            }
        }

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
                        val free = view.height -
                            insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
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

    private const val MAX_ROWS_HEIGHT = 0.72f

    private const val MIN_ROWS_HEIGHT = 60f

    private const val FRAME_PADDING = 12f

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
