package com.mimskydo.apphub

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.view.WindowManager
import kotlin.math.roundToInt

/** The app that is in front right now, with the task it is running in. */
data class ForegroundApp(val packageName: String, val taskId: Int)

/** One task, as `am stack list` describes it. */
data class WindowTask(
    val id: Int,
    val packageName: String,
    val bounds: Rect,
    /** `fullscreen`, `freeform`, `multi-window`, … where the unit reports it */
    val windowingMode: String?,
)

/** What happened the last time a profile was applied - what the screen reports. */
sealed interface WindowOutcome {
    data class Applied(val packageName: String) : WindowOutcome

    /** The unit would not move the task, so nothing was changed. */
    data class Refused(val packageName: String, val windowingMode: String?) : WindowOutcome
}

/**
 * The root side of the feature: everything that talks to the window manager.
 *
 * One mechanism, and deliberately the narrowest one there is:
 *
 *  * the app that is in front is read with `dumpsys activity activities`, and
 *    its rectangle is read back with `am stack list`;
 *  * the rectangle itself is set with `am task resize <TASK_ID> <LEFT> <TOP>
 *    <RIGHT> <BOTTOM>`, which changes **that one task** and nothing else.
 *
 * Nothing global is ever touched - not `wm size`, not `wm density`, not
 * `wm overscan`, not a settings row, not a file on the system partition. The
 * whole feature can be uninstalled and the unit is exactly as it was.
 *
 * The window manager decides whether a task may be resized at all. On Android
 * 10 a task can only be given bounds while it is in a resizing-capable
 * windowing mode (freeform, or multi-window); a fullscreen task is pinned to
 * the display and the resize is quietly dropped. That is why every apply ends
 * with a read-back: the app never claims to have moved a window it did not
 * move, and a refusal is reported instead of being worked around with
 * something global.
 *
 * Every call runs through [RootShell], so with no usable `su` each of them
 * answers null and the caller stops - see [RootShell.isAvailable].
 */
object WindowControl {

    /** The last apply, for the screen to show; in memory, it is a live state. */
    @Volatile
    var lastOutcome: WindowOutcome? = null

    /** True when the unit's window manager says it does multi-window at all. */
    fun multiWindow(): Boolean? = RootShell.command(MULTI_WINDOW_COMMAND)?.let { output ->
        when {
            output.contains("true", ignoreCase = true) -> true
            output.contains("false", ignoreCase = true) -> false
            else -> null
        }
    }

    /**
     * The app the user is looking at.
     *
     * The resumed record is the first answer, and `mFocusedApp` the fallback for
     * builds that print it differently. Both carry the task id (`… t31}`), which
     * is the handle the resize needs; the component before it is the package.
     */
    fun foreground(): ForegroundApp? {
        val output = RootShell.command(FOREGROUND_COMMAND) ?: return null
        for (line in output.lineSequence()) {
            val record = RECORD.find(line)?.value ?: continue
            val fields = FIELDS.find(record) ?: continue
            val taskId = fields.groupValues[2].toIntOrNull() ?: continue
            // t-1 is a record with no task yet; it cannot be resized
            if (taskId <= 0) continue
            val component = fields.groupValues[1]
            return ForegroundApp(component.substringBefore('/'), taskId)
        }
        return null
    }

    /** Every task the unit knows, by id, with the bounds it is running at. */
    fun tasks(): Map<Int, WindowTask> {
        val output = RootShell.command(TASKS_COMMAND) ?: return emptyMap()
        val tasks = HashMap<Int, WindowTask>()
        var mode: String? = null

        for (line in output.lineSequence()) {
            // each task block hangs under a stack whose configuration line
            // carries the windowing mode the tasks in it are running in
            if (STACK_HEADER.containsMatchIn(line)) mode = null
            MODE.find(line)?.let { mode = it.groupValues[1] }

            val fields = TASK.find(line) ?: continue
            val id = fields.groupValues[1].toIntOrNull() ?: continue
            val left = fields.groupValues[4].toIntOrNull() ?: continue
            val top = fields.groupValues[5].toIntOrNull() ?: continue
            val right = fields.groupValues[6].toIntOrNull() ?: continue
            val bottom = fields.groupValues[7].toIntOrNull() ?: continue
            tasks[id] = WindowTask(
                id = id,
                packageName = fields.groupValues[2],
                bounds = Rect(left, top, right, bottom),
                windowingMode = mode,
            )
        }
        return tasks
    }

    /**
     * Moves one task inside [bounds].
     *
     * True means the command ran, not that the window moved: the unit answers
     * only after the window manager has had its say, so the caller reads the
     * task back and compares ([tasks]) before believing anything.
     */
    fun resize(taskId: Int, bounds: Rect): Boolean =
        RootShell.command(resizeCommand(taskId, bounds)) != null

    /** The real display size in pixels: the screen the bounds are measured on. */
    @Suppress("DEPRECATION")
    fun screenSize(context: Context): Point {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val size = Point()
        windowManager.defaultDisplay.getRealSize(size)
        if (size.x > 0 && size.y > 0) return size

        // a unit that will not answer for its display: the metrics agree with it
        // often enough to keep the wheels working
        val metrics = context.resources.displayMetrics
        return Point(metrics.widthPixels, metrics.heightPixels)
    }

    /**
     * How small a window may get before the window manager stops honouring it.
     *
     * Android calls this `mMinSizeOfResizeableTaskDp` and defaults it to 220 dp;
     * asking for less than that is asking for something the unit will not do.
     */
    fun minTaskSide(context: Context): Int =
        (MIN_TASK_DP * context.resources.displayMetrics.density).roundToInt()

    private fun resizeCommand(taskId: Int, bounds: Rect): String =
        "am task resize $taskId ${bounds.left} ${bounds.top} ${bounds.right} ${bounds.bottom}"

    /**
     * The resumed record, and the focused one as a fallback. `-m 6` stops the
     * dump as soon as the answer is in: `dumpsys activity activities` is a long
     * document and the watcher reads it on every poll.
     */
    private const val FOREGROUND_COMMAND =
        "dumpsys activity activities 2>/dev/null | grep -m 6 -iE 'esumedactivity|mfocusedapp'"

    /** `am stack list`: one small line per task, with the bounds it runs at. */
    private const val TASKS_COMMAND = "am stack list 2>/dev/null"

    private const val MULTI_WINDOW_COMMAND = "am supports-multiwindow 2>/dev/null"

    // Every pattern here is deliberately written the way Android's own regex
    // engine (ICU) wants it - a bare `}` or `]` is a syntax error there, not a
    // literal - and each was checked on a device before being trusted.

    /** `ActivityRecord{8f6e0d8 u0 com.foo/.Bar t31}` - the part inside the braces */
    private val RECORD = Regex("""ActivityRecord\{.*?\}""")

    /** the user, the component and the task id of a record */
    private val FIELDS = Regex(""" u\d+ (\S+) t(-?\d+)""")

    /** `Stack id=1 …` / `RootTask id=1 …`, i.e. the start of a task block */
    private val STACK_HEADER = Regex("""^\s*(?:RootTask|Stack) id=""")

    private val MODE = Regex("""mWindowingMode=(\w+)""")

    /** `taskId=11: com.android.settings/.Settings bounds=[0,0][1024,600] userId=0` */
    private val TASK = Regex(
        """taskId=(\d+): (\S+?)/(\S*) bounds=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"""
    )

    /** the same 220 dp the framework uses for its own minimum */
    private const val MIN_TASK_DP = 220f
}
