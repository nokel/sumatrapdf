package com.sumatrapdf.library

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.artifex.mupdf.fitz.PDFAnnotation

class ReaderActivity : AppCompatActivity() {

    private val session = DocumentSession()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var drawer: DrawerLayout
    private lateinit var toolbar: Toolbar
    private lateinit var pageHost: FrameLayout
    private lateinit var topBars: LinearLayout
    private lateinit var bottomBars: LinearLayout
    private lateinit var searchBar: LinearLayout
    private lateinit var searchText: EditText
    private lateinit var searchSpinner: ProgressBar
    private lateinit var selectionBar: View
    private lateinit var selectionButtons: LinearLayout
    private lateinit var inkBar: LinearLayout
    private lateinit var slider: SeekBar
    private lateinit var pageLabel: TextView
    private lateinit var busy: ProgressBar
    private lateinit var outlineList: RecyclerView
    private lateinit var outlineEmpty: TextView

    private var pager: ViewPager2? = null
    private var scroller: ZoomBox? = null
    private var strip: RecyclerView? = null

    private val pages = PageAdapter()
    private val contents = OutlineAdapter()

    private var source: DocumentSource? = null
    private var current = 0
    private var rotation = 0
    private var night = false
    private var continuous = false
    private var fitMode = FIT_PAGE
    private var barsShown = true
    private var dirty = false
    private var opened = false

    private var searchNeedle = ""
    private var searchRun = 0
    private var searchHits = HashMap<Int, List<RectF>>()
    private var activeHit: RectF? = null
    private var activeHitPage = -1
    private var activeHitSlot = -1

    private var selectionPage = -1
    private var selectionFrom: PointF? = null
    private var selectionTo: PointF? = null
    private var selectionText = ""
    private var inkPage = -1
    private var inkColour = Color.RED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.artifex.mupdf.fitz.Context.init()
        Reading.attach(this)
        Library.attach(this)
        setContentView(R.layout.activity_reader)

        drawer = findViewById(R.id.readerDrawer)
        toolbar = findViewById(R.id.toolbar)
        pageHost = findViewById(R.id.pageHost)
        topBars = findViewById(R.id.topBars)
        bottomBars = findViewById(R.id.bottomBars)
        searchBar = findViewById(R.id.searchBar)
        searchText = findViewById(R.id.searchText)
        searchSpinner = findViewById(R.id.searchSpinner)
        selectionBar = findViewById(R.id.selectionBar)
        selectionButtons = findViewById(R.id.selectionButtons)
        inkBar = findViewById(R.id.inkBar)
        slider = findViewById(R.id.pageSlider)
        pageLabel = findViewById(R.id.pageLabel)
        busy = findViewById(R.id.busy)
        outlineList = findViewById(R.id.outlineList)
        outlineEmpty = findViewById(R.id.outlineEmpty)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        outlineList.layoutManager = LinearLayoutManager(this)
        outlineList.adapter = contents

        night = Reading.night
        continuous = Reading.continuous
        fitMode = Reading.fitMode

        wireSearchBar()
        wireSlider()
        buildSelectionBar()
        buildInkBar()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                stepBack()
            }
        })

        val wanted = resolveSource()
        if (wanted == null) {
            Toast.makeText(this, R.string.cannot_open_generic, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        source = wanted
        supportActionBar?.title = intent.getStringExtra(EXTRA_TITLE) ?: wanted.name
        rotation = Reading.rotationOf(wanted.key)
        applyAwake()
        openDocument(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        session.close()
    }

    override fun onPause() {
        super.onPause()
        source?.let {
            Reading.rememberPage(it.key, current)
            Reading.remember(it, current, session.pageCount)
        }
    }

    private fun resolveSource(): DocumentSource? {
        val given = intent.getStringExtra(EXTRA_PATH)
        if (given != null) return DocumentSource.ofFile(given)
        val data: Uri? = intent.data ?: intent.getParcelableExtra(Intent.EXTRA_STREAM)
        if (data != null) return DocumentSource.ofUri(this, data)
        return null
    }

    private fun openDocument(password: String?) {
        val wanted = source ?: return
        busy.visibility = View.VISIBLE
        val metrics = resources.displayMetrics
        session.reflowWidth = metrics.widthPixels / metrics.density
        session.reflowHeight = metrics.heightPixels / metrics.density
        session.reflowEm = Reading.textSize
        session.open(
            wanted,
            password,
            onReady = {
                busy.visibility = View.GONE
                opened = true
                afterOpen()
            },
            onPassword = {
                busy.visibility = View.GONE
                askPassword(password != null)
            },
            onFailed = { why ->
                busy.visibility = View.GONE
                AlertDialog.Builder(this)
                    .setMessage(getString(R.string.cannot_open, wanted.name) + "\n\n" + why)
                    .setPositiveButton(R.string.close) { _, _ -> finish() }
                    .setOnDismissListener { finish() }
                    .show()
            },
        )
    }

    private fun askPassword(retry: Boolean) {
        val field = EditText(this)
        field.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        field.hint = getString(R.string.password_hint)
        AlertDialog.Builder(this)
            .setTitle(if (retry) R.string.password_wrong else R.string.password_needed)
            .setView(field)
            .setPositiveButton(R.string.unlock) { _, _ ->
                openDocument(field.text.toString())
            }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun afterOpen() {
        val wanted = source ?: return
        buildPageHost()
        val remembered = intent.getIntExtra(EXTRA_PAGE, -1).let {
            if (it >= 0) it else Reading.pageOf(wanted.key)
        }
        current = remembered.coerceIn(0, maxOf(0, session.pageCount - 1))
        slider.max = maxOf(0, session.pageCount - 1)
        pages.notifyDataSetChanged()
        goTo(current, false)
        showPageNumber()
        invalidateOptionsMenu()
        session.outline { found ->
            contents.submit(found)
            outlineEmpty.visibility = if (found.isEmpty()) View.VISIBLE else View.GONE
            drawer.setDrawerLockMode(
                if (found.isEmpty()) DrawerLayout.LOCK_MODE_LOCKED_CLOSED
                else DrawerLayout.LOCK_MODE_UNLOCKED
            )
            invalidateOptionsMenu()
        }
        Reading.remember(wanted, current, session.pageCount)
    }

    private fun buildPageHost() {
        pageHost.removeAllViews()
        pager = null
        scroller = null
        strip = null
        if (continuous) {
            val box = ZoomBox(this)
            val list = RecyclerView(this)
            list.layoutManager = LinearLayoutManager(this)
            list.adapter = pages
            list.setHasFixedSize(false)
            list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                    val manager = view.layoutManager as? LinearLayoutManager ?: return
                    val first = manager.findFirstVisibleItemPosition()
                    if (first >= 0 && first != current) {
                        current = first
                        showPageNumber()
                        slider.progress = current
                        rememberHere()
                    }
                }
            })
            box.addView(
                list,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            )
            box.onZoom = { zoom ->
                val width = (pageHost.width * zoom).toInt()
                list.layoutParams = FrameLayout.LayoutParams(
                    if (width <= 0) ViewGroup.LayoutParams.MATCH_PARENT else width,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                list.requestLayout()
                pages.notifyDataSetChanged()
            }
            pageHost.addView(
                box,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            )
            scroller = box
            strip = list
        } else {
            val flip = ViewPager2(this)
            flip.adapter = pages
            flip.offscreenPageLimit = 1
            flip.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    current = position
                    showPageNumber()
                    slider.progress = position
                    rememberHere()
                }
            })
            pageHost.addView(
                flip,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            )
            pager = flip
        }
    }

    private fun rememberHere() {
        source?.let { Reading.rememberPage(it.key, current) }
    }

    private fun goTo(index: Int, smooth: Boolean) {
        val settled = index.coerceIn(0, maxOf(0, session.pageCount - 1))
        current = settled
        pager?.setCurrentItem(settled, smooth)
        strip?.let { list ->
            (list.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(settled, 0)
        }
        slider.progress = settled
        showPageNumber()
        rememberHere()
    }

    private fun showPageNumber() {
        pageLabel.text = getString(R.string.page_of, current + 1, session.pageCount)
    }

    private fun wireSlider() {
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) pageLabel.text = getString(R.string.page_of, value + 1, session.pageCount)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) { }

            override fun onStopTrackingTouch(bar: SeekBar?) {
                goTo(bar?.progress ?: 0, false)
            }
        })
    }

    private fun wireSearchBar() {
        findViewById<ImageButton>(R.id.searchNext).setOnClickListener { runOrAdvance(true) }
        findViewById<ImageButton>(R.id.searchPrev).setOnClickListener { runOrAdvance(false) }
        findViewById<ImageButton>(R.id.searchClose).setOnClickListener { closeSearch() }
        searchText.setOnEditorActionListener { _, actionId, event ->
            val fired = actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            if (fired) {
                startSearch(searchText.text.toString())
                true
            } else {
                false
            }
        }
    }

    private fun runOrAdvance(forward: Boolean) {
        val typed = searchText.text.toString().trim()
        if (typed.isEmpty()) return
        if (typed != searchNeedle) startSearch(typed)
        else findFrom(if (forward) current + 1 else current - 1, forward)
    }

    private fun startSearch(needle: String) {
        val trimmed = needle.trim()
        if (trimmed.isEmpty()) return
        searchNeedle = trimmed
        searchHits.clear()
        activeHit = null
        activeHitPage = -1
        activeHitSlot = -1
        hideKeyboard()
        findFrom(current, true)
    }

    private fun findFrom(start: Int, forward: Boolean) {
        if (searchNeedle.isEmpty()) return
        searchRun += 1
        val run = searchRun
        searchSpinner.visibility = View.VISIBLE
        stepSearch(run, start, forward, 0)
    }

    private fun stepSearch(run: Int, at: Int, forward: Boolean, walked: Int) {
        if (run != searchRun) return
        if (walked > session.pageCount) {
            searchSpinner.visibility = View.GONE
            Toast.makeText(this, getString(R.string.find_none, searchNeedle), Toast.LENGTH_SHORT).show()
            return
        }
        val total = session.pageCount
        if (total <= 0) {
            searchSpinner.visibility = View.GONE
            return
        }
        val index = ((at % total) + total) % total
        session.searchPage(index, searchNeedle) { hit ->
            if (run != searchRun) return@searchPage
            if (hit != null && hit.boxes.isNotEmpty()) {
                searchSpinner.visibility = View.GONE
                searchHits[index] = hit.boxes
                activeHitPage = index
                activeHitSlot = if (forward) 0 else hit.boxes.size - 1
                activeHit = hit.boxes[activeHitSlot]
                goTo(index, false)
                pages.notifyItemChanged(index)
            } else {
                stepSearch(run, if (forward) index + 1 else index - 1, forward, walked + 1)
            }
        }
    }

    private fun openSearch() {
        searchBar.visibility = View.VISIBLE
        searchText.requestFocus()
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.showSoftInput(searchText, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearch() {
        searchRun += 1
        searchBar.visibility = View.GONE
        searchSpinner.visibility = View.GONE
        searchNeedle = ""
        val touched = searchHits.keys.toList()
        searchHits.clear()
        activeHit = null
        activeHitPage = -1
        hideKeyboard()
        for (index in touched) pages.notifyItemChanged(index)
    }

    private fun hideKeyboard() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.hideSoftInputFromWindow(searchText.windowToken, 0)
    }

    private fun buildSelectionBar() {
        selectionButtons.removeAllViews()
        fun add(label: Int, action: () -> Unit) {
            val button = Button(this)
            button.text = getString(label)
            button.isAllCaps = false
            button.setTextColor(getColor(R.color.shelf_text))
            button.setBackgroundColor(Color.TRANSPARENT)
            button.setOnClickListener { action() }
            selectionButtons.addView(button)
        }
        add(R.string.copy_text) { copySelection() }
        add(R.string.share_text) { shareSelection() }
        add(R.string.highlight) { markSelection(PDFAnnotation.TYPE_HIGHLIGHT, floatArrayOf(1f, 0.9f, 0.3f)) }
        add(R.string.underline) { markSelection(PDFAnnotation.TYPE_UNDERLINE, floatArrayOf(0.2f, 0.6f, 1f)) }
        add(R.string.strikeout) { markSelection(PDFAnnotation.TYPE_STRIKE_OUT, floatArrayOf(1f, 0.3f, 0.3f)) }
        add(R.string.squiggly) { markSelection(PDFAnnotation.TYPE_SQUIGGLY, floatArrayOf(0.4f, 0.8f, 0.4f)) }
        add(R.string.add_note) { askNoteForSelection() }
    }

    private fun buildInkBar() {
        inkBar.removeAllViews()
        val colours = listOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLACK)
        for (colour in colours) {
            val dot = Button(this)
            dot.text = ""
            dot.setBackgroundColor(colour)
            val params = LinearLayout.LayoutParams(84, 84)
            params.marginEnd = 12
            dot.layoutParams = params
            dot.setOnClickListener {
                inkColour = colour
                viewFor(inkPage)?.beginInk(colour, 3f)
            }
            inkBar.addView(dot)
        }
        val spacer = View(this)
        spacer.layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        inkBar.addView(spacer)
        val keep = Button(this)
        keep.text = getString(R.string.draw_save)
        keep.isAllCaps = false
        keep.setTextColor(getColor(R.color.shelf_text))
        keep.setBackgroundColor(Color.TRANSPARENT)
        keep.setOnClickListener { commitInk() }
        inkBar.addView(keep)
        val drop = Button(this)
        drop.text = getString(R.string.draw_discard)
        drop.isAllCaps = false
        drop.setTextColor(getColor(R.color.shelf_dim))
        drop.setBackgroundColor(Color.TRANSPARENT)
        drop.setOnClickListener { cancelInk() }
        inkBar.addView(drop)
    }

    private fun viewFor(index: Int): PageView? {
        if (index < 0) return null
        val holder = strip?.findViewHolderForAdapterPosition(index) as? PageHolder
        if (holder != null) return holder.page
        val inner = pager?.getChildAt(0) as? RecyclerView ?: return null
        val found = inner.findViewHolderForAdapterPosition(index) as? PageHolder
        return found?.page
    }

    private fun beginSelection(index: Int, spot: PointF) {
        session.wordAt(index, spot.x, spot.y) { boxes, text ->
            if (boxes.isEmpty()) return@wordAt
            selectionPage = index
            selectionText = text
            lastSelectionBoxes = boxes
            val bounds = boxes.first()
            val last = boxes.last()
            selectionFrom = PointF(bounds.left, (bounds.top + bounds.bottom) / 2f)
            selectionTo = PointF(last.right, (last.top + last.bottom) / 2f)
            val view = viewFor(index)
            view?.mode = PAGE_MODE_SELECT
            view?.setSelection(boxes, selectionFrom, selectionTo)
            selectionBar.visibility = View.VISIBLE
        }
    }

    private fun extendSelection(index: Int, from: PointF, to: PointF) {
        session.selectBetween(index, from.x, from.y, to.x, to.y) { boxes, text ->
            selectionText = text
            lastSelectionBoxes = boxes
            selectionFrom = from
            selectionTo = to
            viewFor(index)?.setSelection(boxes, from, to)
        }
    }

    private fun clearSelection() {
        val index = selectionPage
        selectionPage = -1
        selectionText = ""
        selectionFrom = null
        selectionTo = null
        selectionBar.visibility = View.GONE
        viewFor(index)?.clearSelection()
    }

    private fun copySelection() {
        if (!session.canCopy) {
            Toast.makeText(this, R.string.copy_not_allowed, Toast.LENGTH_SHORT).show()
            return
        }
        if (selectionText.isBlank()) return
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clip?.setPrimaryClip(ClipData.newPlainText("text", selectionText))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
        clearSelection()
    }

    private fun shareSelection() {
        if (!session.canCopy) {
            Toast.makeText(this, R.string.copy_not_allowed, Toast.LENGTH_SHORT).show()
            return
        }
        if (selectionText.isBlank()) return
        val send = Intent(Intent.ACTION_SEND)
        send.type = "text/plain"
        send.putExtra(Intent.EXTRA_TEXT, selectionText)
        startActivity(Intent.createChooser(send, getString(R.string.share_text)))
        clearSelection()
    }

    private fun markSelection(type: Int, colour: FloatArray) {
        if (!checkAnnotatable()) return
        val index = selectionPage
        val view = viewFor(index)
        val boxes = currentSelectionBoxes(index)
        if (index < 0 || boxes.isEmpty()) return
        session.markSelection(index, type, boxes, colour, null) { done ->
            if (done) {
                dirty = true
                clearSelection()
                refreshPage(index)
            } else {
                Toast.makeText(this, R.string.save_failed_generic, Toast.LENGTH_SHORT).show()
            }
        }
        view?.clearSelection()
    }

    private var lastSelectionBoxes: List<RectF> = emptyList()

    private fun currentSelectionBoxes(index: Int): List<RectF> = lastSelectionBoxes

    private fun askNoteForSelection() {
        if (!checkAnnotatable()) return
        val spot = selectionFrom ?: return
        val index = selectionPage
        val field = EditText(this)
        field.hint = getString(R.string.note_text)
        AlertDialog.Builder(this)
            .setTitle(R.string.add_note)
            .setView(field)
            .setPositiveButton(R.string.ok) { _, _ ->
                val text = field.text.toString()
                if (text.isBlank()) return@setPositiveButton
                session.addNote(index, spot.x, spot.y, text) { done ->
                    if (done) {
                        dirty = true
                        clearSelection()
                        refreshPage(index)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun checkAnnotatable(): Boolean {
        if (session.canAnnotate) return true
        val why = when {
            !session.isPdf -> getString(R.string.reason_not_pdf)
            source?.writable != true -> getString(R.string.reason_read_only)
            else -> getString(R.string.reason_no_permission)
        }
        Toast.makeText(this, getString(R.string.annotations_read_only, why), Toast.LENGTH_LONG).show()
        return false
    }

    private fun beginInk() {
        if (!checkAnnotatable()) return
        inkPage = current
        viewFor(inkPage)?.beginInk(inkColour, 3f)
        inkBar.visibility = View.VISIBLE
        selectionBar.visibility = View.GONE
    }

    private fun commitInk() {
        val index = inkPage
        val view = viewFor(index)
        val strokes = view?.inkInPageSpace() ?: emptyList()
        inkBar.visibility = View.GONE
        view?.discardInk()
        inkPage = -1
        if (strokes.isEmpty()) return
        val colour = floatArrayOf(
            Color.red(inkColour) / 255f,
            Color.green(inkColour) / 255f,
            Color.blue(inkColour) / 255f,
        )
        session.markInk(index, strokes, colour, 3f) { done ->
            if (done) {
                dirty = true
                refreshPage(index)
            }
        }
    }

    private fun cancelInk() {
        inkBar.visibility = View.GONE
        viewFor(inkPage)?.discardInk()
        inkPage = -1
    }

    private fun refreshPage(index: Int) {
        pages.notifyItemChanged(index)
    }

    private fun followLink(target: LinkTarget) {
        if (!target.external) {
            goTo(target.page, false)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.open_link)
            .setMessage(getString(R.string.leaving_app, target.uri))
            .setPositiveButton(R.string.open_link) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target.uri)))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toggleBars() {
        barsShown = !barsShown
        val show = if (barsShown) View.VISIBLE else View.GONE
        topBars.visibility = show
        bottomBars.visibility = show
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (barsShown) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun applyAwake() {
        if (Reading.keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun stepBack() {
        when {
            drawer.isDrawerOpen(findViewById<View>(R.id.outlinePanel)) ->
                drawer.closeDrawer(findViewById<View>(R.id.outlinePanel))
            inkBar.visibility == View.VISIBLE -> cancelInk()
            selectionBar.visibility == View.VISIBLE -> clearSelection()
            searchBar.visibility == View.VISIBLE -> closeSearch()
            !barsShown -> toggleBars()
            dirty -> askToSave()
            else -> finish()
        }
    }

    private fun askToSave() {
        AlertDialog.Builder(this)
            .setTitle(R.string.unsaved_title)
            .setMessage(R.string.unsaved_body)
            .setPositiveButton(R.string.save_changes) { _, _ -> saveNow { finish() } }
            .setNegativeButton(R.string.discard) { _, _ -> dirty = false; finish() }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    private fun saveNow(after: (() -> Unit)?) {
        busy.visibility = View.VISIBLE
        session.save { problem ->
            busy.visibility = View.GONE
            if (problem == null) {
                dirty = false
                Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
                after?.invoke()
            } else {
                Toast.makeText(this, getString(R.string.save_failed, problem), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, ITEM_FIND, 0, R.string.find).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menu.add(0, ITEM_CONTENTS, 0, R.string.contents)
        menu.add(0, ITEM_GOTO, 0, R.string.go_to_page)
        menu.add(0, ITEM_VIEW, 0, R.string.view_options)
        if (session.isPdf) {
            menu.add(0, ITEM_DRAW, 0, R.string.draw)
            menu.add(0, ITEM_ANNOTATIONS, 0, R.string.annotations)
            menu.add(0, ITEM_SAVE, 0, R.string.save_changes)
        }
        menu.add(0, ITEM_PROPERTIES, 0, R.string.properties)
        menu.add(0, ITEM_PRINT, 0, R.string.print_document)
        menu.add(0, ITEM_SHARE, 0, R.string.share_document)
        menu.add(0, ITEM_READ_ALOUD, 0, R.string.read_aloud)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(ITEM_CONTENTS)?.isEnabled = contents.itemCount > 0
        menu.findItem(ITEM_SAVE)?.isVisible = session.isPdf && dirty
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            ITEM_FIND -> openSearch()
            ITEM_CONTENTS -> drawer.openDrawer(findViewById<View>(R.id.outlinePanel))
            ITEM_GOTO -> askPageNumber()
            ITEM_VIEW -> showViewOptions()
            ITEM_DRAW -> beginInk()
            ITEM_ANNOTATIONS -> showAnnotations()
            ITEM_SAVE -> saveNow(null)
            ITEM_PROPERTIES -> showProperties()
            ITEM_PRINT -> printDocument()
            ITEM_SHARE -> shareDocument()
            ITEM_READ_ALOUD -> AlertDialog.Builder(this)
                .setTitle(R.string.read_aloud)
                .setMessage(R.string.read_aloud_note)
                .setPositiveButton(R.string.close, null)
                .show()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun askPageNumber() {
        val field = EditText(this)
        field.inputType = InputType.TYPE_CLASS_NUMBER
        field.hint = getString(R.string.page_number, session.pageCount)
        AlertDialog.Builder(this)
            .setTitle(R.string.go_to_page)
            .setView(field)
            .setPositiveButton(R.string.ok) { _, _ ->
                val wanted = field.text.toString().toIntOrNull() ?: return@setPositiveButton
                goTo(wanted - 1, false)
            }
            .setNeutralButton(R.string.first_page) { _, _ -> goTo(0, false) }
            .setNegativeButton(R.string.last_page) { _, _ -> goTo(session.pageCount - 1, false) }
            .show()
    }

    private fun showViewOptions() {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()

        fun add(label: String, action: () -> Unit) {
            labels.add(label)
            actions.add(action)
        }

        add(getString(R.string.continuous_scroll) + tick(continuous)) {
            continuous = !continuous
            Reading.continuous = continuous
            buildPageHost()
            pages.notifyDataSetChanged()
            goTo(current, false)
        }
        add(getString(R.string.night_mode) + tick(night)) {
            night = !night
            Reading.night = night
            pages.notifyDataSetChanged()
        }
        add(getString(if (fitMode == FIT_PAGE) R.string.fit_width else R.string.fit_page)) {
            fitMode = if (fitMode == FIT_PAGE) FIT_WIDTH else FIT_PAGE
            Reading.fitMode = fitMode
            pages.notifyDataSetChanged()
        }
        add(getString(R.string.rotate_left)) { turn(-90) }
        add(getString(R.string.rotate_right)) { turn(90) }
        add(getString(R.string.show_links) + tick(Reading.showLinks)) {
            Reading.showLinks = !Reading.showLinks
            pages.notifyDataSetChanged()
        }
        add(getString(R.string.keep_awake) + tick(Reading.keepAwake)) {
            Reading.keepAwake = !Reading.keepAwake
            applyAwake()
        }
        add(getString(R.string.full_screen)) { toggleBars() }
        if (session.reflowable) {
            add(getString(R.string.text_bigger)) { changeTextSize(1f) }
            add(getString(R.string.text_smaller)) { changeTextSize(-1f) }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.view_options)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun tick(on: Boolean) = if (on) "  ✓" else ""

    private fun turn(by: Int) {
        rotation = ((rotation + by) % 360 + 360) % 360
        source?.let { Reading.rememberRotation(it.key, rotation) }
        pages.notifyDataSetChanged()
    }

    private fun changeTextSize(by: Float) {
        val wanted = (Reading.textSize + by).coerceIn(6f, 30f)
        Reading.textSize = wanted
        busy.visibility = View.VISIBLE
        val metrics = resources.displayMetrics
        session.relayout(
            metrics.widthPixels / metrics.density,
            metrics.heightPixels / metrics.density,
            wanted,
            current,
        ) { landing ->
            busy.visibility = View.GONE
            slider.max = maxOf(0, session.pageCount - 1)
            pages.notifyDataSetChanged()
            goTo(landing, false)
        }
    }

    private fun showAnnotations() {
        session.annotations(current) { found ->
            if (found.isEmpty()) {
                Toast.makeText(this, R.string.no_annotations, Toast.LENGTH_SHORT).show()
                return@annotations
            }
            val labels = found.map {
                if (it.contents.isBlank()) it.label else it.label + " — " + it.contents.take(40)
            }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle(R.string.annotations)
                .setItems(labels) { _, which ->
                    val chosen = found[which]
                    AlertDialog.Builder(this)
                        .setTitle(chosen.label)
                        .setMessage(chosen.contents.ifBlank { chosen.label })
                        .setPositiveButton(R.string.delete_annotation) { _, _ ->
                            session.removeAnnotation(current, chosen.slot) { done ->
                                if (done) {
                                    dirty = true
                                    refreshPage(current)
                                }
                            }
                        }
                        .setNegativeButton(R.string.close, null)
                        .show()
                }
                .show()
        }
    }

    private fun showProperties() {
        session.facts { rows ->
            val body = rows.joinToString("\n") { it.first + ": " + it.second }
            AlertDialog.Builder(this)
                .setTitle(R.string.properties)
                .setMessage(body.ifBlank { getString(R.string.no_contents) })
                .setPositiveButton(R.string.close, null)
                .show()
        }
    }

    private fun printDocument() {
        if (!session.canPrint) {
            Toast.makeText(this, R.string.print_not_allowed, Toast.LENGTH_LONG).show()
            return
        }
        DocumentPrinter.start(this, session, source?.name ?: "document")
    }

    private fun shareDocument() {
        val where = source ?: return
        val send = Intent(Intent.ACTION_SEND)
        send.type = "application/octet-stream"
        if (where.origin.startsWith("content://")) {
            send.putExtra(Intent.EXTRA_STREAM, Uri.parse(where.origin))
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, getString(R.string.share_document)))
        } else {
            session.pageText(current) { text ->
                val note = Intent(Intent.ACTION_SEND)
                note.type = "text/plain"
                note.putExtra(Intent.EXTRA_SUBJECT, where.name)
                note.putExtra(Intent.EXTRA_TEXT, text.take(SHARE_LIMIT))
                startActivity(Intent.createChooser(note, getString(R.string.share_document)))
            }
        }
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val view = PageView(parent.context)
            val box = FrameLayout(parent.context)
            box.addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            )
            box.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            return PageHolder(box, view)
        }

        override fun getItemCount() = session.pageCount

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            holder.bind(position)
        }
    }

    private inner class PageHolder(private val box: FrameLayout, val page: PageView) :
        RecyclerView.ViewHolder(box) {

        private var wanted = -1

        init {
            page.onTap = { toggleBars() }
            page.onLink = { followLink(it) }
            page.onLongPressAt = { index, spot -> beginSelection(index, spot) }
            page.onSelectionChanged = { index, from, to -> extendSelection(index, from, to) }
            page.onSelectionFinished = { }
            page.onZoomChanged = { zoom -> maybeSharpen(page, zoom) }
        }

        fun bind(position: Int) {
            wanted = position
            page.pageIndex = position
            page.mode = PAGE_MODE_READ
            page.showLinks = Reading.showLinks
            page.zoomLocked = continuous
            page.setSelection(emptyList(), null, null)

            if (continuous) {
                val shape = session.knownShape(position)
                val across = strip?.width ?: pageHost.width
                val ratio = shape?.ratio ?: 1.4f
                val tall = if (across > 0) (across * ratio).toInt() else ViewGroup.LayoutParams.WRAP_CONTENT
                box.layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    if (tall > 0) tall else 1200,
                )
                if (shape == null) {
                    session.measure(position) {
                        if (wanted == position) pages.notifyItemChanged(position)
                    }
                }
            } else {
                box.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }

            page.show(null)
            page.post {
                val across = page.width
                if (across <= 0) return@post
                val target = (across * BASE_QUALITY).toInt()
                session.render(position, target, rotation, night) { made ->
                    if (wanted != position || made == null) return@render
                    page.show(made)
                    if (fitMode == FIT_WIDTH && !continuous) page.fitWidth()
                    page.setSearch(
                        searchHits[position] ?: emptyList(),
                        if (activeHitPage == position) activeHit else null,
                    )
                    if (selectionPage == position) {
                        val from = selectionFrom
                        val to = selectionTo
                        if (from != null && to != null) {
                            page.mode = PAGE_MODE_SELECT
                            session.selectBetween(position, from.x, from.y, to.x, to.y) { boxes, text ->
                                lastSelectionBoxes = boxes
                                selectionText = text
                                page.setSelection(boxes, from, to)
                            }
                        }
                    }
                }
                session.links(position) { found ->
                    if (wanted == position) page.setLinks(found)
                }
            }
        }
    }

    private fun maybeSharpen(view: PageView, zoom: Float) {
        if (zoom <= BASE_QUALITY) return
        val index = view.pageIndex
        main.removeCallbacks(sharpen)
        sharpenTarget = view
        sharpenZoom = zoom
        sharpenIndex = index
        main.postDelayed(sharpen, 220)
    }

    private var sharpenTarget: PageView? = null
    private var sharpenZoom = 1f
    private var sharpenIndex = -1

    private val sharpen = Runnable {
        val view = sharpenTarget ?: return@Runnable
        val index = sharpenIndex
        if (index < 0 || view.pageIndex != index) return@Runnable
        val across = view.width
        if (across <= 0) return@Runnable
        val target = (across * sharpenZoom).toInt()
        session.render(index, target, rotation, night) { made ->
            if (made != null && view.pageIndex == index) view.show(made)
        }
    }

    private inner class OutlineAdapter : RecyclerView.Adapter<OutlineHolder>() {
        private val rows = ArrayList<OutlineEntry>()

        fun submit(found: List<OutlineEntry>) {
            rows.clear()
            rows.addAll(found)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OutlineHolder {
            val view = layoutInflater.inflate(R.layout.item_outline, parent, false)
            return OutlineHolder(view)
        }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: OutlineHolder, position: Int) {
            holder.bind(rows[position])
        }
    }

    private inner class OutlineHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val label: TextView = view.findViewById(R.id.outlineLabel)
        private val number: TextView = view.findViewById(R.id.outlinePage)

        fun bind(entry: OutlineEntry) {
            label.text = entry.title
            number.text = if (entry.page >= 0) (entry.page + 1).toString() else ""
            itemView.setPadding(16 + entry.depth * 28, itemView.paddingTop, 16, itemView.paddingBottom)
            itemView.setOnClickListener {
                if (entry.page >= 0) {
                    goTo(entry.page, false)
                    drawer.closeDrawer(findViewById<View>(R.id.outlinePanel))
                }
            }
        }
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_ID = "id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_PAGE = "page"

        private const val BASE_QUALITY = 2f
        private const val SHARE_LIMIT = 100000

        private const val ITEM_FIND = 1
        private const val ITEM_CONTENTS = 2
        private const val ITEM_GOTO = 3
        private const val ITEM_VIEW = 4
        private const val ITEM_DRAW = 5
        private const val ITEM_ANNOTATIONS = 6
        private const val ITEM_SAVE = 7
        private const val ITEM_PROPERTIES = 8
        private const val ITEM_PRINT = 9
        private const val ITEM_SHARE = 10
        private const val ITEM_READ_ALOUD = 11
    }
}
