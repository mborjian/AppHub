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

class FileManagerActivity : BaseActivity() {

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

    private var access = Access.MISSING

    private var volumes: List<Volume> = emptyList()

    private var here: File? = null

    private var rows: List<FileItem> = emptyList()

    private var readable = false

    private var loaded = false

    private var busy = false

    private var carry: File? = null
    private var carryMove = false

    private val askStorage = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
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

        savedInstanceState?.getString(KEY_DIR)
            ?: intent.getStringExtra(EXTRA_START_DIR)
            ?.let { here = File(it) }
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

    private fun render() {
        val dir = here
        val volume = dir?.let { Files.volumeOf(it, volumes) }
        val missing = access == Access.MISSING

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

        val panel = when {
            missing -> null
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
        NONE(R.string.files_none_title, R.string.files_none_message),

        GONE(R.string.files_gone_title, R.string.files_gone_message),

        DENIED(R.string.files_denied_title, R.string.files_denied_message),

        BLANK(R.string.files_empty_title, R.string.files_empty_message),
    }

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

    private fun openRow(item: FileItem) {
        when {
            item.kind == FileKind.UP || item.folder -> goTo(item.file)
            item.kind == FileKind.APK -> showActions(item)
            else -> tapFile(item)
        }
    }

    private fun tapFile(item: FileItem) {
        val op = Handoff.open(this, item.file)
        if (op == HandoffOp.UNCLAIMED) {
            showActions(item, subtitle = getString(R.string.files_unclaimed_message))
            return
        }
        toast(HandoffReport.text(this, op, item.name))
    }

    private fun goTo(dir: File) {
        if (here?.absolutePath == dir.absolutePath) return
        here = dir
        read()
    }

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

    private fun showActions(item: FileItem, subtitle: String? = null) {
        val sheetRows = ArrayList<SheetRow>(6)

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
        if (!item.folder && item.kind != FileKind.APK && item.kind != FileKind.UP) {
            sheetRows += SheetRow(
                label = getString(R.string.files_open_with),
                icon = ContextCompat.getDrawable(this, R.drawable.ic_open),
                onClick = { handoff(item.file) },
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
            subtitle = subtitle,
            rows = sheetRows,
        )
    }

    private fun pick(file: File, move: Boolean) {
        carry = file
        carryMove = move
        render()
    }

    private fun paste() {
        val source = carry ?: return
        val into = here ?: return
        if (busy) return
        val move = carryMove
        busy = true
        carry = null
        render()

        val context = applicationContext
        try {
            worker.execute {
                val op = if (move) Files.move(source, into) else Files.copy(source, into)
                Log.i(TAG, "${if (move) "move" else "copy"} ${source.absolutePath} -> ${into.absolutePath} $op")
                post {
                    busy = false
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

    private fun handoff(file: File) {
        val label = file.name ?: ""
        toast(HandoffReport.text(this, Handoff.open(this, file), label))
    }

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

    companion object {
        private const val TAG = "AppHub"

        const val EXTRA_START_DIR = "files_start_dir"

        private const val KEY_DIR = "files_dir"
        private const val KEY_CARRY = "files_carry"
        private const val KEY_MOVE = "files_carry_move"

        private const val DIMMED = 0.5f
    }
}

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
