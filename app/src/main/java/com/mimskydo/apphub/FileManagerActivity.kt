package com.mimskydo.apphub

import android.content.res.ColorStateList
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.Executors

/**
 * The files on the unit, in the unit's own storage and on whatever card or stick
 * is plugged into it.
 *
 * A folder browser and not a picker: one row per folder or file, tap a folder to
 * go in, tap the "..." on a row for what can be done to it - copy it, move it,
 * take it off the unit, or install it, if it is a package. What is being carried
 * from one folder to another rides in a bar at the bottom, because a copy is two
 * taps in two places and the driver has to be able to see that the first one
 * happened.
 *
 * Everything it reads is a real path (see [Files]) and every operation runs on
 * this screen's own worker: a folder of two thousand files, or a card that is
 * being pulled out, must not be able to freeze the UI thread a driver is
 * steering with.
 *
 * Nothing here opens a file. There is no viewer, no editor and no "open with":
 * the one verb an installed app has for a file is installing a package, and the
 * rest are the three the driver asked for - copy, move, delete.
 */
class FileManagerActivity : BaseActivity() {

    /** reads and writes files off the main thread, like every other screen here */
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var headerTitle: TextView
    private lateinit var headerTrailing: TextView
    private lateinit var locationRow: View
    private lateinit var locationTitle: TextView
    private lateinit var locationSubtitle: TextView
    private lateinit var banner: View
    private lateinit var list: RecyclerView
    private lateinit var empty: View
    private lateinit var emptyTitle: TextView
    private lateinit var emptyMessage: TextView
    private lateinit var carryBar: View
    private lateinit var carryText: TextView

    private val adapter = FileAdapter(::openRow, ::showActions)

    /** what this install is allowed to do at all, re-read on every resume */
    private var access = Access.MISSING

    /** the volumes as they were at the last read, kept for the places sheet */
    private var volumes: List<Volume> = emptyList()

    /** the folder on screen, or null before the first read has chosen one */
    private var here: File? = null

    /** the rows the list is drawing: the way up, then the folder's own entries */
    private var rows: List<FileItem> = emptyList()

    /** whether [here] could be read - as opposed to being empty, which is different */
    private var readable = false

    /** true once a read has answered, either way: before that there is nothing to say */
    private var loaded = false

    /** true from a tap on a verb until the unit has been given its chance to finish */
    private var busy = false

    /** the file on its way to a folder, and whether it is being taken or copied */
    private var carry: File? = null
    private var carryMove = false

    private val askStorage = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // the grant is a state, not a result to act on: whatever the answer was,
        // the screen reads it back and says the truth
        read()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_manager)

        applyMargins()

        headerTitle = findViewById(R.id.headerTitle)
        headerTrailing = findViewById(R.id.headerTrailing)
        locationRow = findViewById(R.id.locationRow)
        locationTitle = findViewById(R.id.locationTitle)
        locationSubtitle = findViewById(R.id.locationSubtitle)
        banner = findViewById(R.id.filesBanner)
        list = findViewById(R.id.fileList)
        empty = findViewById(R.id.listEmpty)
        emptyTitle = findViewById(R.id.listEmptyTitle)
        emptyMessage = findViewById(R.id.listEmptyMessage)
        carryBar = findViewById(R.id.carryBar)
        carryText = findViewById(R.id.carryText)

        // a screen that was put down in a folder comes back to it, and to
        // whatever was still in its hands
        savedInstanceState?.getString(KEY_DIR)?.let { here = File(it) }
        savedInstanceState?.getString(KEY_CARRY)?.let { carry = File(it) }
        carryMove = savedInstanceState?.getBoolean(KEY_MOVE) == true

        headerTitle.setText(R.string.files_title)
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        locationRow.setOnClickListener { openPlaces() }

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        wireBanner()
        wireCarry()
    }

    override fun onResume() {
        super.onResume()
        // the grant is given on a screen of Android's own, so the state can only
        // be read once this screen is back in front
        read()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_DIR, here?.absolutePath)
        outState.putString(KEY_CARRY, carry?.absolutePath)
        outState.putBoolean(KEY_MOVE, carryMove)
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------- the read

    /**
     * What this install may see, then what is in the folder on screen.
     *
     * Without the grant nothing is read at all. That is not laziness: from
     * Android 11 on, a folder this app may not read lists as *empty* rather than
     * as refused, and a file manager that says a full folder is empty is worse
     * than one that says it has not been allowed in yet.
     */
    private fun read() {
        access = Files.access(this)
        if (access == Access.MISSING) {
            volumes = emptyList()
            rows = emptyList()
            readable = false
            render()
            return
        }

        val context = applicationContext
        val wanted = here
        try {
            worker.execute {
                val volumes = Files.volumes(context)
                val dir = wanted
                    ?: volumes.firstOrNull { it.primary }?.root
                    ?: volumes.firstOrNull()?.root
                val items = dir?.let { Files.list(it) }
                post { draw(volumes, dir, items) }
            }
        } catch (t: Throwable) {
            // the executor was already shut down while finishing
        }
    }

    private fun draw(volumes: List<Volume>, dir: File?, items: List<FileItem>?) {
        this.volumes = volumes
        here = dir
        readable = items != null
        val volume = dir?.let { Files.volumeOf(it, volumes) }
        rows = if (dir == null) {
            emptyList()
        } else {
            listOfNotNull(Files.up(dir, volume)) + items.orEmpty()
        }
        loaded = true
        render()
    }

    // ------------------------------------------------------------ the screen

    private fun render() {
        val dir = here
        val volume = dir?.let { Files.volumeOf(it, volumes) }
        val missing = access == Access.MISSING

        // the header names the folder, the way a browser does, and the volume's
        // own free space goes beside it - the number a driver checks before
        // copying something onto a card
        headerTitle.text = when {
            dir == null -> getString(R.string.files_title)
            volume != null && dir.absolutePath == volume.root.absolutePath -> volume.name
            else -> dir.name ?: getString(R.string.files_title)
        }
        headerTrailing.isVisible = !missing && volume != null
        headerTrailing.text = when {
            busy -> getString(R.string.files_working)
            volume != null -> getString(R.string.files_free, Files.sizeText(volume.root.usableSpace))
            else -> ""
        }

        locationTitle.text = volume?.name ?: getString(R.string.files_title)
        locationSubtitle.text = if (missing) "" else dir?.let { Files.pathOf(it, volume) }.orEmpty()
        locationRow.isEnabled = !missing
        locationRow.alpha = if (missing) DIMMED else 1f

        banner.isVisible = missing
        adapter.submit(if (missing) emptyList() else rows)

        // The one line the screen has to say when there is nothing to draw. Which
        // line it is, is the whole point: a folder that refuses to be read, a
        // folder that is empty, a folder that is gone and a unit with no storage
        // at all are four different answers.
        val panel = when {
            missing -> null
            // nothing has answered yet: a panel would be this screen telling the
            // driver about a unit it has not looked at
            !loaded -> null
            dir == null || volumes.isEmpty() -> Panel.NONE
            !dir.exists() -> Panel.GONE
            !readable -> Panel.DENIED
            rows.isEmpty() -> Panel.BLANK
            else -> null
        }
        empty.isVisible = panel != null
        if (panel != null) {
            emptyTitle.setText(panel.title)
            emptyMessage.setText(panel.message)
        }
        list.isVisible = panel == null && rows.isNotEmpty()

        carryBar.isVisible = carry != null
        carry?.let { held ->
            carryText.text = getString(
                if (carryMove) R.string.files_pick_move else R.string.files_pick_copy,
                held.name ?: "",
            )
        }
    }

    private enum class Panel(val title: Int, val message: Int) {
        /** no volume at all: a unit with no storage this app can see */
        NONE(R.string.files_none_title, R.string.files_none_message),

        /** the folder the driver was in is not there any more - a card, pulled out */
        GONE(R.string.files_gone_title, R.string.files_gone_message),

        /** the platform keeps this one shut, all files access or not */
        DENIED(R.string.files_denied_title, R.string.files_denied_message),

        /** a folder with nothing in it */
        BLANK(R.string.files_empty_title, R.string.files_empty_message),
    }

    /** the grant, in the same banner the board uses for the missing dots */
    private fun wireBanner() {
        banner.findViewById<TextView>(R.id.bannerTitle).setText(R.string.files_access_title)
        banner.findViewById<TextView>(R.id.bannerMessage).setText(R.string.files_access_message)
        banner.findViewById<ImageView>(R.id.bannerIcon).imageTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.hub_warn))
        banner.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.hub_wash_warn))
        banner.findViewById<ImageView>(R.id.bannerDismiss).isVisible = false

        val action = banner.findViewById<TextView>(R.id.bannerAction)
        action.isVisible = true
        action.setText(R.string.enable)
        action.setOnClickListener { askForStorage() }
    }

    /**
     * Android 11 and up has one screen for it and it is a system setting; below
     * that it is an ordinary runtime permission, asked for in place.
     */
    private fun askForStorage() {
        if (Files.openAccessSettings(this)) return
        try {
            askStorage.launch(Files.STORAGE_PERMISSIONS)
        } catch (t: Throwable) {
            toast(getString(R.string.settings_unavailable))
        }
    }

    private fun wireCarry() {
        findViewById<TextView>(R.id.carryAction).setOnClickListener { paste() }
        findViewById<View>(R.id.carryClose).setOnClickListener {
            carry = null
            render()
        }
    }

    // ----------------------------------------------------------- the places

    /** a folder, entered; or the way back up, followed */
    private fun openRow(item: FileItem) {
        if (item.kind == FileKind.UP || item.folder) goTo(item.file)
        else showActions(item)
    }

    private fun goTo(dir: File) {
        if (here?.absolutePath == dir.absolutePath) return
        here = dir
        read()
    }

    /**
     * Where this could be: every volume, and every folder between this one and
     * the root of the volume it is on.
     *
     * One sheet rather than two, because the two questions are the same question
     * - "somewhere else" - and a driver who has just plugged a card in is
     * looking for the same row as one who wants to climb back up.
     */
    private fun openPlaces() {
        val dir = here
        val volume = dir?.let { Files.volumeOf(it, volumes) }
        val sheetRows = ArrayList<SheetRow>(volumes.size + 8)

        volumes.forEach { candidate ->
            sheetRows += SheetRow(
                label = candidate.name,
                subtitle = getString(R.string.files_free, Files.sizeText(candidate.root.usableSpace)),
                icon = ContextCompat.getDrawable(this, R.drawable.ic_folder),
                selected = candidate.root.absolutePath == volume?.root?.absolutePath,
                onClick = { goTo(candidate.root) },
            )
        }

        val chain = dir?.let { Files.chain(it, volume) }.orEmpty()
        chain.drop(1).forEachIndexed { index, folder ->
            sheetRows += SheetRow(
                label = folder.name ?: "/",
                subtitle = Files.pathOf(folder, volume),
                selected = folder.absolutePath == dir?.absolutePath,
                groupStart = index == 0,
                onClick = { goTo(folder) },
            )
        }

        sheetRows += SheetRow(
            label = getString(android.R.string.cancel),
            groupStart = chain.size <= 1,
        )
        Sheet.showBottom(
            context = this,
            title = getString(R.string.files_where_title),
            rows = sheetRows,
        )
    }

    // ------------------------------------------------------------- the verbs

    /** what can be done with one row, in the menu the board uses for an app */
    private fun showActions(item: FileItem) {
        val sheetRows = ArrayList<SheetRow>(6)

        // only a package gets this row, because it is the only kind of file the
        // platform will install by itself
        if (item.kind == FileKind.APK) {
            sheetRows += SheetRow(
                label = getString(R.string.files_install),
                icon = ContextCompat.getDrawable(this, R.drawable.ic_apk),
                onClick = { install(item.file) },
            )
        }
        if (item.folder) {
            sheetRows += SheetRow(
                label = getString(R.string.action_open),
                icon = ContextCompat.getDrawable(this, R.drawable.ic_folder),
                onClick = { goTo(item.file) },
            )
        }
        sheetRows += SheetRow(
            label = getString(R.string.files_copy),
            icon = ContextCompat.getDrawable(this, R.drawable.ic_copy),
            onClick = { pick(item.file, move = false) },
        )
        sheetRows += SheetRow(
            label = getString(R.string.files_move),
            icon = ContextCompat.getDrawable(this, R.drawable.ic_move),
            onClick = { pick(item.file, move = true) },
        )
        sheetRows += SheetRow(
            label = getString(R.string.files_delete),
            icon = ContextCompat.getDrawable(this, R.drawable.ic_uninstall),
            danger = true,
            groupStart = true,
            onClick = { confirmDelete(item) },
        )
        sheetRows += SheetRow(
            label = getString(android.R.string.cancel),
            groupStart = true,
        )

        Sheet.showBottom(
            context = this,
            title = item.name,
            rows = sheetRows,
        )
    }

    /**
     * Pick a file up and carry it. The second half of the copy is a tap in
     * another folder, so what is in the driver's hands is drawn at the bottom of
     * the screen until they put it down somewhere - or dismiss it.
     */
    private fun pick(file: File, move: Boolean) {
        carry = file
        carryMove = move
        render()
    }

    /** Put it down here. */
    private fun paste() {
        val source = carry ?: return
        val into = here ?: return
        if (busy) return
        val move = carryMove
        busy = true
        // the bar goes while the unit works; whether it comes back is decided by
        // what happened, below
        carry = null
        render()

        val context = applicationContext
        try {
            worker.execute {
                val op = if (move) Files.move(source, into) else Files.copy(source, into)
                Log.i(TAG, "${if (move) "move" else "copy"} ${source.absolutePath} -> ${into.absolutePath} $op")
                post {
                    busy = false
                    // a copied file stays in hand: the same picture usually goes
                    // to more than one folder, and picking it up again is four taps
                    if (move && op == FileOp.DONE) carry = null else carry = source
                    val verb = if (move) FileVerb.MOVE else FileVerb.COPY
                    toast(FileReport.text(this, verb, op, source.name ?: ""))
                    read()
                }
            }
        } catch (t: Throwable) {
            busy = false
            carry = source
            render()
        }
    }

    /**
     * The one removal that re-opening cannot bring back, so it is the one that
     * asks first - and the question says what goes with it, because a folder on
     * the unit means everything inside it too.
     */
    private fun confirmDelete(item: FileItem) {
        val message = if (item.folder) {
            R.string.files_delete_folder_message
        } else {
            R.string.files_delete_file_message
        }
        Sheet.show(
            context = this,
            title = getString(R.string.files_delete_title, item.name),
            subtitle = getString(message),
            rows = listOf(
                SheetRow(
                    label = getString(R.string.files_delete_confirm),
                    destroy = true,
                    onClick = { delete(item) },
                ),
                SheetRow(label = getString(android.R.string.cancel)),
            ),
        )
    }

    private fun delete(item: FileItem) {
        if (busy) return
        busy = true
        // whatever was being carried may be the thing going away
        if (carry?.absolutePath == item.file.absolutePath) carry = null
        render()

        val context = applicationContext
        try {
            worker.execute {
                val op = Files.delete(item.file)
                Log.i(TAG, "delete ${item.file.absolutePath} $op")
                post {
                    busy = false
                    toast(FileReport.text(this, FileVerb.DELETE, op, item.name))
                    read()
                }
            }
        } catch (t: Throwable) {
            busy = false
            render()
        }
    }

    /**
     * Install the package. What comes back is which layer ran: the platform
     * installing it with no screen (the unit's own platform-signed build), or
     * Android's installer screen asking the driver about it.
     */
    private fun install(file: File) {
        if (busy) return
        busy = true
        render()

        val context = applicationContext
        val label = file.name ?: ""
        try {
            worker.execute {
                val method = Install.install(context, file)
                post {
                    busy = false
                    toast(InstallReport.text(this, method, label))
                    // no read: the install is the platform's and it is not in this
                    // folder. The work is done here, so the header stops saying so
                    render()
                }
            }
        } catch (t: Throwable) {
            busy = false
            render()
        }
    }

    private fun post(block: () -> Unit) = runOnUiThread {
        if (!isFinishing && !isDestroyed) block()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /** the tag `adb logcat -s AppHub` filters by */
        private const val TAG = "AppHub"

        private const val KEY_DIR = "files_dir"
        private const val KEY_CARRY = "files_carry"
        private const val KEY_MOVE = "files_carry_move"

        /** what a row the driver may not use is drawn at */
        private const val DIMMED = 0.5f
    }
}

/**
 * One row of a folder: what it is, what it is called, and - for a file - its size
 * and the day it was last written.
 *
 * The state rides in the row rather than in a badge: there is nothing to mark
 * here, and the only row that is not a file is the way back up, which says so
 * with its own glyph and has no menu behind it - there is nothing to be done to
 * a place except go there.
 */
private class FileAdapter(
    private val onOpen: (FileItem) -> Unit,
    private val onMore: (FileItem) -> Unit,
) : RecyclerView.Adapter<FileAdapter.Row>() {

    private val items = ArrayList<FileItem>()

    fun submit(next: List<FileItem>) {
        items.clear()
        items.addAll(next)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row =
        Row(LayoutInflater.from(parent.context).inflate(R.layout.row_file, parent, false))

    override fun onBindViewHolder(holder: Row, position: Int) {
        holder.bind(items[position])
    }

    inner class Row(view: View) : RecyclerView.ViewHolder(view) {

        private val icon = view.findViewById<ImageView>(R.id.rowIcon)
        private val label = view.findViewById<TextView>(R.id.rowLabel)
        private val meta = view.findViewById<TextView>(R.id.rowMeta)
        private val more = view.findViewById<ImageView>(R.id.rowMore)

        fun bind(item: FileItem) {
            val context = itemView.context

            icon.setImageResource(item.kind.glyph)
            // Folders are the way around this screen, so they carry the accent;
            // everything else is structural ink. One tone per row: two colours
            // in one glyph is the drawing the design set out to avoid.
            icon.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(
                    context,
                    if (item.folder || item.kind == FileKind.UP) R.color.primary_text
                    else R.color.hub_ink_600,
                )
            )

            label.text = item.name

            val isFile = !item.folder && item.kind != FileKind.UP
            meta.isVisible = isFile
            if (isFile) {
                meta.text = context.getString(
                    R.string.files_row_meta,
                    Files.sizeText(item.size),
                    Files.dateText(context, item.file),
                )
            }

            more.isVisible = item.kind != FileKind.UP
            more.setOnClickListener { onMore(item) }

            // one node, one sentence: a row is read as a file and its numbers,
            // not as three texts in a column
            itemView.contentDescription = if (isFile) {
                listOf(item.name, meta.text).joinToString(", ")
            } else {
                item.name
            }
            itemView.setOnClickListener { onOpen(item) }
            itemView.setOnLongClickListener {
                if (item.kind == FileKind.UP) {
                    false
                } else {
                    onMore(item)
                    true
                }
            }
        }
    }
}
