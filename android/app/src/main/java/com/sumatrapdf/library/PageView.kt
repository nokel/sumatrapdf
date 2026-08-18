package com.sumatrapdf.library

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.artifex.mupdf.fitz.Rect

const val PAGE_MODE_READ = 0
const val PAGE_MODE_SELECT = 1
const val PAGE_MODE_INK = 2

class PageView(context: Context) : View(context) {

    private val display = Matrix()
    private val inverse = Matrix()
    private val values = FloatArray(9)
    private var fitScale = 1f

    private val pagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val searchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFD54F }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FF9800.toInt() }
    private val selectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x558AB4F8 }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8AB4F8.toInt() }
    private val linkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x338AB4F8
        style = Paint.Style.FILL
    }
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    var render: PageRender? = null
        private set

    var pageIndex: Int = -1
    var zoomLocked = false
    var mode = PAGE_MODE_READ
    var showLinks = false

    private var links: List<LinkTarget> = emptyList()
    private var searchBoxes: List<RectF> = emptyList()
    private var activeBox: RectF? = null
    private var selectionBoxes: List<RectF> = emptyList()
    private var selectFrom: PointF? = null
    private var selectTo: PointF? = null
    private var draggingHandle = 0

    private val inkStrokes = ArrayList<ArrayList<PointF>>()
    private var inkColour = Color.RED
    private var inkWidth = 3f

    var onTap: (() -> Unit)? = null
    var onLink: ((LinkTarget) -> Unit)? = null
    var onSelectionChanged: ((Int, PointF, PointF) -> Unit)? = null
    var onSelectionFinished: (() -> Unit)? = null
    var onLongPressAt: ((Int, PointF) -> Unit)? = null
    var onZoomChanged: ((Float) -> Unit)? = null

    private val scaler = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (zoomLocked) return false
                val step = clampStep(detector.scaleFactor)
                display.postScale(step, step, detector.focusX, detector.focusY)
                settle()
                onZoomChanged?.invoke(zoom())
                return true
            }
        })

    private val gestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                down: MotionEvent?, move: MotionEvent, dx: Float, dy: Float,
            ): Boolean {
                if (mode != PAGE_MODE_READ) return false
                if (zoomLocked || zoom() <= 1.001f) return false
                display.postTranslate(-dx, -dy)
                settle()
                return true
            }

            override fun onDoubleTap(event: MotionEvent): Boolean {
                if (zoomLocked) return false
                if (zoom() > 1.001f) fitPage() else {
                    display.postScale(2.5f, 2.5f, event.x, event.y)
                    settle()
                }
                onZoomChanged?.invoke(zoom())
                return true
            }

            override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
                if (mode == PAGE_MODE_SELECT) {
                    clearSelection()
                    onSelectionFinished?.invoke()
                    return true
                }
                val spot = viewToPage(event.x, event.y)
                if (spot != null) {
                    val found = links.firstOrNull { it.bounds.contains(spot.x, spot.y) }
                    if (found != null) {
                        onLink?.invoke(found)
                        return true
                    }
                }
                onTap?.invoke()
                return true
            }

            override fun onLongPress(event: MotionEvent) {
                if (mode == PAGE_MODE_INK) return
                val spot = viewToPage(event.x, event.y) ?: return
                onLongPressAt?.invoke(pageIndex, spot)
            }
        })

    init {
        isClickable = true
        setBackgroundColor(Color.TRANSPARENT)
    }

    fun show(made: PageRender?) {
        render = made
        fitPage()
        invalidate()
    }

    fun setLinks(found: List<LinkTarget>) {
        links = found
        invalidate()
    }

    fun setSearch(boxes: List<RectF>, active: RectF?) {
        searchBoxes = boxes
        activeBox = active
        invalidate()
    }

    fun setSelection(boxes: List<RectF>, from: PointF?, to: PointF?) {
        selectionBoxes = boxes
        selectFrom = from
        selectTo = to
        invalidate()
    }

    fun clearSelection() {
        selectionBoxes = emptyList()
        selectFrom = null
        selectTo = null
        mode = PAGE_MODE_READ
        invalidate()
    }

    fun beginInk(colour: Int, width: Float) {
        mode = PAGE_MODE_INK
        inkColour = colour
        inkWidth = width
        inkStrokes.clear()
        invalidate()
    }

    fun inkInPageSpace(): List<List<PointF>> {
        val out = ArrayList<List<PointF>>()
        for (stroke in inkStrokes) {
            val mapped = ArrayList<PointF>(stroke.size)
            for (dot in stroke) {
                val spot = viewToPage(dot.x, dot.y) ?: continue
                mapped.add(spot)
            }
            if (mapped.size > 1) out.add(mapped)
        }
        return out
    }

    fun discardInk() {
        inkStrokes.clear()
        mode = PAGE_MODE_READ
        invalidate()
    }

    fun hasInk(): Boolean = inkStrokes.any { it.size > 1 }

    fun fitPage() {
        val made = render ?: return
        val bw = made.bitmap.width.toFloat()
        val bh = made.bitmap.height.toFloat()
        if (bw <= 0f || bh <= 0f || width == 0 || height == 0) return
        fitScale = minOf(width / bw, height / bh)
        display.reset()
        display.postScale(fitScale, fitScale)
        display.postTranslate((width - bw * fitScale) / 2f, (height - bh * fitScale) / 2f)
        invalidate()
    }

    fun fitWidth() {
        val made = render ?: return
        val bw = made.bitmap.width.toFloat()
        val bh = made.bitmap.height.toFloat()
        if (bw <= 0f || bh <= 0f || width == 0) return
        val scale = width / bw
        display.reset()
        display.postScale(scale, scale)
        display.postTranslate(0f, minOf(0f, (height - bh * scale) / 2f))
        invalidate()
        onZoomChanged?.invoke(zoom())
    }

    fun fillView() {
        val made = render ?: return
        val bw = made.bitmap.width.toFloat()
        val bh = made.bitmap.height.toFloat()
        if (bw <= 0f || bh <= 0f || width == 0 || height == 0) return
        fitScale = minOf(width / bw, height / bh)
        display.reset()
        display.postScale(fitScale, fitScale)
        display.postTranslate((width - bw * fitScale) / 2f, (height - bh * fitScale) / 2f)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitPage()
    }

    fun zoom(): Float {
        display.getValues(values)
        return if (fitScale > 0f) values[Matrix.MSCALE_X] / fitScale else 1f
    }

    private fun clampStep(factor: Float): Float {
        val now = zoom()
        val wanted = now * factor
        return when {
            wanted < 1f -> 1f / now
            wanted > 8f -> 8f / now
            else -> factor
        }
    }

    private fun settle() {
        val made = render ?: return
        val box = RectF(0f, 0f, made.bitmap.width.toFloat(), made.bitmap.height.toFloat())
        display.mapRect(box)
        var dx = 0f
        var dy = 0f
        if (box.width() <= width) dx = width / 2f - box.centerX()
        else if (box.left > 0) dx = -box.left
        else if (box.right < width) dx = width - box.right
        if (box.height() <= height) dy = height / 2f - box.centerY()
        else if (box.top > 0) dy = -box.top
        else if (box.bottom < height) dy = height - box.bottom
        display.postTranslate(dx, dy)
        invalidate()
    }

    private fun viewToPage(x: Float, y: Float): PointF? {
        val made = render ?: return null
        if (!display.invert(inverse)) return null
        val spot = floatArrayOf(x, y)
        inverse.mapPoints(spot)
        val onPage = made.bitmapToPage(spot[0], spot[1])
        return PointF(onPage.x, onPage.y)
    }

    private fun pageToView(box: RectF): RectF {
        val made = render ?: return RectF()
        val onBitmap = made.rectToBitmap(Rect(box.left, box.top, box.right, box.bottom))
        val out = RectF(onBitmap)
        display.mapRect(out)
        return out
    }

    private fun pageToView(spot: PointF): PointF {
        val here = pageToView(RectF(spot.x, spot.y, spot.x, spot.y))
        return PointF(here.left, here.top)
    }

    override fun onDraw(canvas: Canvas) {
        val made = render ?: return
        canvas.drawBitmap(made.bitmap, display, pagePaint)

        if (showLinks) {
            for (one in links) canvas.drawRect(pageToView(one.bounds), linkPaint)
        }
        for (box in searchBoxes) canvas.drawRect(pageToView(box), searchPaint)
        activeBox?.let { canvas.drawRect(pageToView(it), activePaint) }
        for (box in selectionBoxes) canvas.drawRect(pageToView(box), selectPaint)

        val start = selectFrom
        val end = selectTo
        if (start != null && end != null && selectionBoxes.isNotEmpty()) {
            val a = pageToView(start)
            val b = pageToView(end)
            canvas.drawCircle(a.x, a.y, HANDLE_RADIUS, handlePaint)
            canvas.drawCircle(b.x, b.y, HANDLE_RADIUS, handlePaint)
        }

        if (inkStrokes.isNotEmpty()) {
            inkPaint.color = inkColour
            inkPaint.strokeWidth = inkWidth * zoom() * fitScale.coerceAtLeast(0.01f)
            for (stroke in inkStrokes) {
                if (stroke.size < 2) continue
                val path = Path()
                path.moveTo(stroke[0].x, stroke[0].y)
                for (i in 1 until stroke.size) path.lineTo(stroke[i].x, stroke[i].y)
                canvas.drawPath(path, inkPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (mode == PAGE_MODE_INK) return handleInk(event)
        if (mode == PAGE_MODE_SELECT && handleSelectDrag(event)) return true
        scaler.onTouchEvent(event)
        gestures.onTouchEvent(event)
        parent?.requestDisallowInterceptTouchEvent(
            mode != PAGE_MODE_READ || zoom() > 1.001f || scaler.isInProgress
        )
        return true
    }

    private fun handleInk(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                inkStrokes.add(arrayListOf(PointF(event.x, event.y)))
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                inkStrokes.lastOrNull()?.add(PointF(event.x, event.y))
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> invalidate()
        }
        return true
    }

    private fun handleSelectDrag(event: MotionEvent): Boolean {
        val start = selectFrom ?: return false
        val end = selectTo ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val a = pageToView(start)
                val b = pageToView(end)
                val nearA = distance(event.x, event.y, a.x, a.y)
                val nearB = distance(event.x, event.y, b.x, b.y)
                draggingHandle = when {
                    nearA <= HANDLE_TOUCH && nearA <= nearB -> 1
                    nearB <= HANDLE_TOUCH -> 2
                    else -> 0
                }
                if (draggingHandle == 0) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingHandle == 0) return false
                val spot = viewToPage(event.x, event.y) ?: return true
                if (draggingHandle == 1) selectFrom = spot else selectTo = spot
                val from = selectFrom ?: return true
                val to = selectTo ?: return true
                onSelectionChanged?.invoke(pageIndex, from, to)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (draggingHandle == 0) return false
                draggingHandle = 0
                onSelectionFinished?.invoke()
                return true
            }
        }
        return false
    }

    private fun distance(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    companion object {
        private const val HANDLE_RADIUS = 16f
        private const val HANDLE_TOUCH = 60f
    }
}
