package com.sumatrapdf.library

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class LibraryActivity : AppCompatActivity() {

    private lateinit var drawer: DrawerLayout
    private lateinit var toolbar: Toolbar
    private lateinit var countLine: TextView
    private lateinit var grid: RecyclerView
    private lateinit var empty: TextView
    private lateinit var seriesList: RecyclerView
    private lateinit var allBooks: TextView
    private lateinit var sortRow: LinearLayout
    private lateinit var rescan: TextView

    private val covers = CoverAdapter()
    private val shelves = ShelfAdapter()
    private var shown: List<Book> = emptyList()
    private var railRows: List<Row> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.artifex.mupdf.fitz.Context.init()
        Library.attach(this)
        setContentView(R.layout.activity_library)

        drawer = findViewById(R.id.drawer)
        toolbar = findViewById(R.id.toolbar)
        countLine = findViewById(R.id.count)
        grid = findViewById(R.id.grid)
        empty = findViewById(R.id.empty)
        seriesList = findViewById(R.id.series)
        allBooks = findViewById(R.id.allBooks)
        sortRow = findViewById(R.id.sort)
        rescan = findViewById(R.id.rescan)

        setSupportActionBar(toolbar)
        val toggle = ActionBarDrawerToggle(this, drawer, toolbar, R.string.rail_title, R.string.close)
        drawer.addDrawerListener(toggle)
        toggle.syncState()

        grid.layoutManager = GridLayoutManager(this, spanCount())
        grid.adapter = covers
        seriesList.layoutManager = LinearLayoutManager(this)
        seriesList.adapter = shelves

        allBooks.setOnClickListener { showFilter(null, null) }
        rescan.setOnClickListener {
            if (Library.scanning) Library.stopScan() else Library.rescan(this)
        }
        findViewById<TextView>(R.id.readAloudNote).setOnClickListener { readAloudNote() }
        buildSortRow()

        Library.onChanged = { refresh() }
        if (needsStorage()) askForStorage() else Library.start(this)
        refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) Library.onChanged = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        (grid.layoutManager as GridLayoutManager).spanCount = spanCount()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.library, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_read_aloud -> {
            readAloudNote()
            true
        }

        R.id.action_rescan -> {
            Library.rescan(this)
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START)
            return
        }
        if (Library.filter != null) {
            showFilter(null, null)
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (!needsStorage()) Library.start(this)
    }

    override fun onResume() {
        super.onResume()
        if (!needsStorage() && !Library.hasBooks() && !Library.scanning) Library.start(this)
        refresh()
    }

    private fun spanCount(): Int {
        val width = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        return maxOf(2, (width / 116f).toInt())
    }

    private fun needsStorage(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return !Environment.isExternalStorageManager()
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
    }

    private fun askForStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AlertDialog.Builder(this)
                .setMessage(R.string.need_storage)
                .setPositiveButton(R.string.grant_access) { _, _ ->
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    try {
                        startActivity(intent)
                    } catch (e: Exception) {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1)
    }

    private fun buildSortRow() {
        sortRow.removeAllViews()
        val label = TextView(this)
        label.text = getString(R.string.sort_label)
        label.setTextColor(ContextCompat.getColor(this, R.color.shelf_dim))
        label.textSize = 14f
        label.setPadding(dp(4), dp(6), dp(8), dp(6))
        sortRow.addView(label)
        for (order in SortOrder.entries) {
            val chip = TextView(this)
            chip.text = order.label
            chip.textSize = 14f
            chip.setPadding(dp(10), dp(6), dp(10), dp(6))
            chip.setOnClickListener {
                Library.setSortOrder(order)
                buildSortRow()
            }
            sortRow.addView(chip)
        }
        paintSortRow()
    }

    private fun paintSortRow() {
        var at = 0
        for (i in 1 until sortRow.childCount) {
            val chip = sortRow.getChildAt(i) as TextView
            val order = SortOrder.entries[at++]
            val active = order == Library.sortOrder
            chip.setTextColor(
                ContextCompat.getColor(this, if (active) R.color.shelf_text else R.color.shelf_link)
            )
            chip.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            chip.setBackgroundColor(
                if (active) ContextCompat.getColor(this, R.color.shelf_selected) else 0
            )
        }
    }

    private fun showFilter(key: String?, name: String?) {
        Library.filter = key
        Library.filterName = name
        drawer.closeDrawer(GravityCompat.START)
        refresh()
    }

    private fun refresh() {
        shown = Library.visibleBooks()
        railRows = Library.rows
        covers.notifyDataSetChanged()
        shelves.notifyDataSetChanged()
        paintSortRow()

        toolbar.title = Library.filterName ?: getString(R.string.everything)
        allBooks.text = getString(R.string.all_books, Library.books.size)
        allBooks.setBackgroundColor(
            if (Library.filter == null) ContextCompat.getColor(this, R.color.shelf_selected) else 0
        )
        rescan.text = when {
            Library.scanning && Library.scanTotal > 0 ->
                getString(R.string.scanning_progress, Library.scanDone, Library.scanTotal)

            Library.scanning -> getString(R.string.scanning)
            else -> getString(R.string.rescan_library)
        }
        countLine.text = when {
            Library.scanning && Library.scanTotal > 0 ->
                getString(R.string.scanning_progress, Library.scanDone, Library.scanTotal)

            shown.size == 1 -> getString(R.string.one_book)
            else -> getString(R.string.book_count, shown.size)
        }
        val blank = shown.isEmpty() && !Library.scanning
        empty.visibility = if (blank) View.VISIBLE else View.GONE
        empty.text = if (needsStorage()) getString(R.string.need_storage) else getString(R.string.no_books)
        grid.visibility = if (blank) View.GONE else View.VISIBLE
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun readAloudNote() {
        AlertDialog.Builder(this)
            .setTitle(R.string.read_aloud)
            .setMessage(R.string.read_aloud_note)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun subtitleOf(book: Book): String {
        val parts = ArrayList<String>()
        if (book.volumes.isNotEmpty()) parts.add("#" + book.volumes.joinToString(", "))
        book.author?.let { parts.add(it) }
        if (parts.isEmpty() && book.pages > 0) parts.add(getString(R.string.pages_count, book.pages))
        return parts.joinToString(" · ")
    }

    private fun openBook(book: Book) {
        if (!book.readable) {
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.cannot_open, book.file))
                .setPositiveButton(R.string.close, null)
                .show()
            return
        }
        val intent = Intent(this, ReaderActivity::class.java)
        intent.putExtra(ReaderActivity.EXTRA_PATH, book.path)
        intent.putExtra(ReaderActivity.EXTRA_ID, book.id)
        intent.putExtra(ReaderActivity.EXTRA_TITLE, book.title)
        startActivity(intent)
    }

    private fun aboutBook(book: Book) {
        val lines = ArrayList<String>()
        book.author?.let { lines.add("Author: $it") }
        book.series?.let { lines.add("Series: $it") }
        if (book.volumes.isNotEmpty()) lines.add("Volume: " + book.volumes.joinToString(", "))
        book.year?.let { lines.add("Year: $it") }
        book.genre?.let { genre ->
            val sub = book.subgenre
            lines.add("Genre: " + if (sub != null) "$genre › $sub" else genre)
        }
        if (book.pages > 0) lines.add(getString(R.string.pages_count, book.pages))
        lines.add("Format: " + book.ext.removePrefix("."))
        if (book.editions.size > 1) lines.add("Editions: " + book.editions.joinToString(", ") {
            it.ext.removePrefix(".")
        })
        lines.add("")
        lines.add(book.path)
        lines.add("")
        lines.add(getString(R.string.read_aloud_pending))
        AlertDialog.Builder(this)
            .setTitle(book.title)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(R.string.open_book) { _, _ -> openBook(book) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun partitionMenu(anchor: View, row: Row) {
        val store = Partitions.load()
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, R.string.new_partition)
        if (store.partitions.isNotEmpty()) popup.menu.add(0, 2, 0, R.string.move_to)
        val parent = row.parent
        val inside = store.find(parent)
        if (inside != null) {
            popup.menu.add(0, 3, 0, getString(R.string.take_out, inside.name))
        }
        if (row.kind == ROW_PARTITION) {
            popup.menu.add(0, 4, 0, R.string.rename_partition)
            popup.menu.add(0, 5, 0, R.string.delete_partition)
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> askName(null) { name ->
                    val made = Partitions.create(name)
                    if (made != null) {
                        Partitions.assign(made.key, row.key)
                        Library.recatalogue()
                    }
                }

                2 -> pickPartition(store) { key ->
                    if (row.kind == ROW_PARTITION) Partitions.reparent(row.key, key)
                    else if (key == null) Partitions.clear(row.key, outOf = row.parent)
                    else Partitions.assign(key, row.key)
                    Library.recatalogue()
                }

                3 -> {
                    Partitions.clear(row.key, outOf = parent)
                    Library.recatalogue()
                }

                4 -> askName(row.name) { name ->
                    Partitions.rename(row.key, name)
                    Library.recatalogue()
                }

                5 -> {
                    Partitions.remove(row.key)
                    Library.recatalogue()
                }
            }
            true
        }
        popup.show()
    }

    private fun askName(current: String?, onDone: (String) -> Unit) {
        val input = EditText(this)
        input.setSingleLine()
        input.setText(current ?: "")
        val box = LinearLayout(this)
        box.setPadding(dp(20), dp(8), dp(20), dp(0))
        box.addView(
            input,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.partition_name)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) onDone(name)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickPartition(store: PartitionStore, onDone: (String?) -> Unit) {
        val names = ArrayList<String>()
        val keys = ArrayList<String?>()
        names.add(getString(R.string.top_level))
        keys.add(null)
        for (p in store.partitions) {
            names.add(p.name)
            keys.add(p.key)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.move_to)
            .setItems(names.toTypedArray()) { _, which -> onDone(keys[which]) }
            .show()
    }

    private inner class CoverAdapter : RecyclerView.Adapter<CoverHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CoverHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_cover, parent, false)
            return CoverHolder(view)
        }

        override fun getItemCount() = shown.size

        override fun onBindViewHolder(holder: CoverHolder, position: Int) {
            holder.bind(shown[position])
        }
    }

    private inner class CoverHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val cover: ImageView = view.findViewById(R.id.cover)
        private val placeholder: TextView = view.findViewById(R.id.placeholder)
        private val title: TextView = view.findViewById(R.id.title)
        private val subtitle: TextView = view.findViewById(R.id.subtitle)
        private var wanted: String? = null

        fun bind(book: Book) {
            wanted = book.id
            title.text = book.title
            subtitle.text = subtitleOf(book)
            itemView.findViewById<View>(R.id.coverBox).setOnClickListener { openBook(book) }
            title.setOnClickListener { aboutBook(book) }
            subtitle.setOnClickListener { aboutBook(book) }
            itemView.setOnLongClickListener {
                aboutBook(book)
                true
            }
            val hit = Covers.cached(book.id)
            if (hit != null) {
                cover.setImageBitmap(hit)
                placeholder.text = ""
                return
            }
            cover.setImageBitmap(null)
            placeholder.text = book.title
            if (Covers.known(book.id)) return
            Covers.request(book) { made ->
                if (made != null && wanted == book.id) {
                    cover.setImageBitmap(made)
                    placeholder.text = ""
                }
            }
        }
    }

    private inner class ShelfAdapter : RecyclerView.Adapter<ShelfHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShelfHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_series, parent, false)
            return ShelfHolder(view)
        }

        override fun getItemCount() = railRows.size

        override fun onBindViewHolder(holder: ShelfHolder, position: Int) {
            holder.bind(railRows[position])
        }
    }

    private inner class ShelfHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val head: TextView = view.findViewById(R.id.head)
        private val subhead: TextView = view.findViewById(R.id.subhead)
        private val row: LinearLayout = view.findViewById(R.id.row)
        private val name: TextView = view.findViewById(R.id.name)
        private val count: TextView = view.findViewById(R.id.count)

        fun bind(shelf: Row) {
            head.visibility = if (shelf.head != null) View.VISIBLE else View.GONE
            head.text = shelf.head ?: ""
            subhead.visibility = if (shelf.subhead != null) View.VISIBLE else View.GONE
            subhead.text = shelf.subhead ?: ""
            name.text = shelf.name
            count.text = shelf.books.toString()
            name.setPadding(dp(12 + shelf.depth * 14), 0, dp(8), 0)
            row.setBackgroundColor(
                if (Library.filter == shelf.key) {
                    ContextCompat.getColor(this@LibraryActivity, R.color.shelf_selected)
                } else {
                    0
                }
            )
            row.setOnClickListener { showFilter(shelf.key, shelf.name) }
            row.setOnLongClickListener {
                partitionMenu(row, shelf)
                true
            }
        }
    }
}
