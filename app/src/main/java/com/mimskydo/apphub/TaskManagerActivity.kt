package com.mimskydo.apphub

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/**
 * The open apps, each with the one button this screen exists for.
 *
 * Reachable from the tools menu, this is the selective counterpart of
 * "Close all". What it lists comes from [OpenApps], which tries the system's own
 * recents list first and the usage-access view last, and the screen says which
 * one answered: a row reads *Running*, *Open* or *Recently open*, because those
 * are three different claims about an app and only the first two are promises.
 *
 * That is also what the banner is for. Where the install cannot read the task
 * list - a plain APK on a phone, with only the usage opt-in - the rows are real
 * apps but "open" is not what they are, so the screen says so instead of
 * pretending. On the unit, where the platform-signed install reads the task
 * list, the banner never appears.
 *
 * The unit's own launcher, SystemUI and App Hub itself never appear: they are
 * protected on the window-margins screen for the same reason - closing the
 * thing that draws the screen under this one is not a feature.
 *
 * Like everywhere else, closing reports rather than offers an undo: an "Undo"
 * beside a force-stopped app would mean "launch it again", which is not the
 * same thing. And the report is read back from the same source the list came
 * from, so "closed" is something the screen checked rather than something a
 * layer claimed.
 */
class TaskManagerActivity : BaseActivity() {

    private val prefs by lazy { Prefs(this) }
    private val windowProfiles by lazy { WindowProfiles(this) }

    /** reads the list off the main thread, like the grid does */
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var list: RecyclerView
    private lateinit var empty: View
    private lateinit var emptyTitle: TextView
    private lateinit var emptyMessage: TextView
    private lateinit var count: TextView
    private lateinit var banner: View

    private val adapter = TaskAdapter(
        shape = { prefs.iconShape },
        onClose = ::closeApp,
    )

    /** the last read, kept for the task ids a Close needs */
    private var snapshot: OpenSnapshot? = null

    /** true from a Close tap until the list has been read again */
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_task_manager)

        applyMargins()

        list = findViewById(R.id.taskList)
        empty = findViewById(R.id.listEmpty)
        emptyTitle = findViewById(R.id.listEmptyTitle)
        emptyMessage = findViewById(R.id.listEmptyMessage)
        count = findViewById(R.id.headerTrailing)
        banner = findViewById(R.id.tasksBanner)

        findViewById<TextView>(R.id.headerTitle).setText(R.string.task_manager)
        count.isVisible = true
        count.text = getString(R.string.loading)
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        wireBanner()
    }

    override fun onResume() {
        super.onResume()
        // re-read on every resume: an app opened or closed while this screen
        // was behind another one is exactly what it is here to show
        refresh()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    /** read the open apps off the thread, then draw them */
    private fun refresh() {
        try {
            worker.execute {
                val result = read(applicationContext)
                post { draw(result) }
            }
        } catch (t: Throwable) {
            // executor already shut down while finishing
        }
    }

    /** what the list draws, and what could see it */
    private class Result(val open: List<AppEntry>, val snapshot: OpenSnapshot?)

    /**
     * The open apps, strongest source first, and only apps this screen may
     * show: the apps the grid lists, minus the three packages that are never
     * closed from here.
     */
    private fun read(context: Context): Result {
        val apps = try {
            AppRepository(context).loadApps(includeSystem = true)
        } catch (t: Throwable) {
            emptyList()
        }
        val snapshot = try {
            OpenApps.read(context, apps.map { it.packageName })
        } catch (t: Throwable) {
            null
        } ?: return Result(emptyList(), null)

        // the states sort it: a process, then a task, then the usage view - so
        // what is certainly open is at the top of the list
        val open = apps
            .filter { !windowProfiles.isProtected(it.packageName) }
            .mapNotNull { entry ->
                val state = snapshot.state(entry.packageName)
                if (state == AppState.IDLE) null else entry.copy(state = state)
            }
            .sortedWith(compareBy({ it.state.ordinal }, { it.label.lowercase() }))

        return Result(open, snapshot)
    }

    private fun draw(result: Result) {
        snapshot = result.snapshot
        adapter.submit(result.open)
        count.text = resources.getQuantityString(
            R.plurals.apps_count, result.open.size, result.open.size,
        )

        val none = result.open.isEmpty()
        empty.isVisible = none
        if (none) {
            // "nothing is open" and "nothing can be seen" are different things,
            // and only one of them is a settings problem
            if (result.snapshot?.source == null) {
                emptyTitle.setText(R.string.task_blind_title)
                emptyMessage.setText(R.string.task_blind_message)
            } else {
                emptyTitle.setText(R.string.task_empty_title)
                emptyMessage.setText(R.string.task_empty_message)
            }
        }

        // the banner is about a list that is showing something it cannot
        // promise: rows that came from the usage view, not from the task list
        val recentOnly = !none && result.snapshot?.source == OpenSource.USAGE
        banner.isVisible = recentOnly && !prefs.tasksOfferDismissed
    }

    /**
     * The banner's own sentence, set once. There is no action button on it -
     * the remedy is a different install, not a tap - so it carries the dismiss
     * and nothing else.
     */
    private fun wireBanner() {
        banner.findViewById<TextView>(R.id.bannerTitle)
            .setText(R.string.task_recent_banner_title)
        banner.findViewById<TextView>(R.id.bannerMessage)
            .setText(R.string.task_recent_banner_message)
        banner.findViewById<TextView>(R.id.bannerAction).isVisible = false

        // attention, not fault: amber is the car's "worth knowing", never red
        val warn = ContextCompat.getColor(this, R.color.hub_warn)
        banner.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.hub_wash_warn))
        banner.findViewById<ImageView>(R.id.bannerIcon).imageTintList =
            ColorStateList.valueOf(warn)

        val dismiss = banner.findViewById<ImageView>(R.id.bannerDismiss)
        dismiss.isVisible = true
        dismiss.setOnClickListener {
            prefs.tasksOfferDismissed = true
            banner.isVisible = false
        }
    }

    /**
     * Closing is not reversible, so it reports instead of offering. The report
     * is the *verified* one: the list is read again once the system has had a
     * moment to reap the process or drop the task, and an app that is still
     * there says so rather than being called closed.
     */
    private fun closeApp(entry: AppEntry) {
        if (busy) return
        busy = true
        val context = applicationContext
        val taskId = snapshot?.taskId(entry.packageName)
        try {
            worker.execute {
                val method = ForceStop.close(context, entry.packageName, taskId)
                Thread.sleep(800)
                val result = read(context)
                // only an exact source can say whether it worked: the usage view
                // keeps listing an app that was used, closed or not, so reading
                // it back would turn "closed" into "still open" every time
                val stillOpen = result.snapshot?.exact == true &&
                    result.open.any { it.packageName == entry.packageName }
                // the one line worth leaving in a build that ships to a car:
                // 'adb logcat -s AppHub' answers "which layer ran, and did it
                // work" without a debugger and without guessing
                Log.i(
                    TAG,
                    "close ${entry.packageName} task=$taskId layer=$method " +
                        "stillOpen=$stillOpen source=${result.snapshot?.source}",
                )
                post {
                    busy = false
                    draw(result)
                    Toast.makeText(
                        this,
                        CloseReport.text(this, method, entry.label, stillOpen),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        } catch (t: Throwable) {
            busy = false
        }
    }

    private fun post(block: () -> Unit) = runOnUiThread {
        if (!isFinishing && !isDestroyed) block()
    }

    private companion object {
        /** the tag `adb logcat -s AppHub` filters by */
        private const val TAG = "AppHub"
    }
}

/**
 * One row per open app: icon, name, package with its state, and Close.
 *
 * The state rides in the subtitle rather than as a badge beside the icon - a
 * dot a driver has to notice is worse than a word they can read - and the word
 * is the source's own: *Running* for a process, *Open* for a task the system
 * still holds, *Recently open* for the usage view.
 */
private class TaskAdapter(
    private val shape: () -> IconShape,
    private val onClose: (AppEntry) -> Unit,
) : RecyclerView.Adapter<TaskAdapter.Row>() {

    private val items = ArrayList<AppEntry>()

    fun submit(open: List<AppEntry>) {
        items.clear()
        items.addAll(open)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row =
        Row(LayoutInflater.from(parent.context).inflate(R.layout.row_task, parent, false))

    override fun onBindViewHolder(holder: Row, position: Int) {
        holder.bind(items[position])
    }

    inner class Row(view: View) : RecyclerView.ViewHolder(view) {

        private val icon = view.findViewById<ImageView>(R.id.rowIcon)
        private val label = view.findViewById<TextView>(R.id.rowLabel)
        private val subtitle = view.findViewById<TextView>(R.id.rowSubtitle)
        private val close = view.findViewById<TextView>(R.id.rowClose)

        private val rowIconPx = view.resources.getDimensionPixelSize(R.dimen.icon_app_row)

        fun bind(entry: AppEntry) {
            // drawn in the shape the board is using, so the row shows the same
            // icon the tile would
            icon.setImageDrawable(
                IconCache.drawn(itemView.resources, entry, shape(), rowIconPx),
            )
            label.text = entry.label
            val state = itemView.resources.getString(
                when (entry.state) {
                    AppState.RUNNING -> R.string.running_marker
                    AppState.OPEN -> R.string.task_state_open
                    else -> R.string.task_state_recent
                },
            )
            subtitle.text = itemView.resources.getString(
                R.string.task_row_meta, entry.packageName, state,
            )

            itemView.contentDescription = entry.label
            close.setOnClickListener { onClose(entry) }
        }
    }
}
