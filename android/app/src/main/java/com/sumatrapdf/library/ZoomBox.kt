package com.sumatrapdf.library

import android.content.Context
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.HorizontalScrollView

class ZoomBox(context: Context) : HorizontalScrollView(context) {

    var onZoom: ((Float) -> Unit)? = null
    var zoom = 1f
        private set

    private var pinching = false

    private val scaler = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinching = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val wanted = (zoom * detector.scaleFactor).coerceIn(1f, 8f)
                if (kotlin.math.abs(wanted - zoom) < 0.001f) return true
                zoom = wanted
                onZoom?.invoke(zoom)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                pinching = false
            }
        })

    init {
        isFillViewport = true
        isHorizontalScrollBarEnabled = false
    }

    fun resetZoom() {
        zoom = 1f
        onZoom?.invoke(zoom)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        if (pinching || event.pointerCount > 1) return true
        return super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        if (pinching || event.pointerCount > 1) return true
        return super.onTouchEvent(event)
    }
}
