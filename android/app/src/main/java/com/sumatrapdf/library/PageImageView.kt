package com.sumatrapdf.library

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

class PageImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatImageView(context, attrs) {

    private val transform = Matrix()
    private val values = FloatArray(9)
    private var fitScale = 1f
    private var onSingleTap: (() -> Unit)? = null

    private val scaler = ScaleGestureDetector(context, object :
        ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val wanted = clampStep(detector.scaleFactor)
            transform.postScale(wanted, wanted, detector.focusX, detector.focusY)
            settle()
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(
            down: MotionEvent?, move: MotionEvent, dx: Float, dy: Float,
        ): Boolean {
            if (zoom() <= 1.001f) return false
            transform.postTranslate(-dx, -dy)
            settle()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom() > 1.001f) reset() else {
                transform.postScale(2.5f, 2.5f, e.x, e.y)
                settle()
            }
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSingleTap?.invoke()
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
    }

    fun onTap(action: () -> Unit) {
        onSingleTap = action
    }

    fun reset() {
        val bitmap = drawable ?: return
        val bw = bitmap.intrinsicWidth.toFloat()
        val bh = bitmap.intrinsicHeight.toFloat()
        if (bw <= 0f || bh <= 0f || width == 0 || height == 0) return
        fitScale = minOf(width / bw, height / bh)
        transform.reset()
        transform.postScale(fitScale, fitScale)
        transform.postTranslate((width - bw * fitScale) / 2f, (height - bh * fitScale) / 2f)
        imageMatrix = transform
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reset()
    }

    private fun zoom(): Float {
        transform.getValues(values)
        return if (fitScale > 0f) values[Matrix.MSCALE_X] / fitScale else 1f
    }

    private fun clampStep(factor: Float): Float {
        val now = zoom()
        val wanted = now * factor
        return when {
            wanted < 1f -> 1f / now
            wanted > 6f -> 6f / now
            else -> factor
        }
    }

    private fun settle() {
        val bitmap = drawable ?: return
        val box = RectF(0f, 0f, bitmap.intrinsicWidth.toFloat(), bitmap.intrinsicHeight.toFloat())
        transform.mapRect(box)
        var dx = 0f
        var dy = 0f
        if (box.width() <= width) dx = width / 2f - box.centerX()
        else if (box.left > 0) dx = -box.left
        else if (box.right < width) dx = width - box.right
        if (box.height() <= height) dy = height / 2f - box.centerY()
        else if (box.top > 0) dy = -box.top
        else if (box.bottom < height) dy = height - box.bottom
        transform.postTranslate(dx, dy)
        imageMatrix = transform
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        gestures.onTouchEvent(event)
        parent?.requestDisallowInterceptTouchEvent(zoom() > 1.001f || scaler.isInProgress)
        return true
    }
}
