package com.example.testing

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

class PageZoomView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    AppCompatImageView(context, attrs) {

    var onPageTap: ((Float?) -> Unit)? = null
    private val transform = Matrix()
    private val bounds = RectF()
    private var zoom = 1f
    private var pinched = false
    private var tapX: Float? = null

    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleAt(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    }).apply { isQuickScaleEnabled = false }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true

        override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
            if (pinched) return false
            tapX = event.x.takeIf { zoom == 1f }
            return performClick()
        }

        override fun onDoubleTap(event: MotionEvent): Boolean {
            if (drawable == null) return false
            if (zoom > 1f) resetZoom() else scaleAt(2f, event.x, event.y)
            return true
        }

        override fun onScroll(start: MotionEvent?, end: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (zoom == 1f || pinch.isInProgress || end.pointerCount != 1) return false
            transform.postTranslate(-distanceX, -distanceY)
            applyBounds()
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    fun resetZoom() {
        zoom = 1f
        transform.reset()
        val page = drawable
        if (page != null && page.intrinsicWidth > 0 && page.intrinsicHeight > 0 && width > 0 && height > 0) {
            val fit = min(width.toFloat() / page.intrinsicWidth, height.toFloat() / page.intrinsicHeight)
            transform.setScale(fit, fit)
            applyBounds()
        } else {
            imageMatrix = transform
        }
    }

    fun zoomBy(factor: Float) = scaleAt(zoom * factor, width / 2f, height / 2f)

    private fun scaleAt(target: Float, x: Float, y: Float) {
        if (drawable == null || !target.isFinite()) return
        val next = target.coerceIn(1f, 4f)
        if (next == 1f) {
            resetZoom()
        } else {
            transform.postScale(next / zoom, next / zoom, x, y)
            zoom = next
            applyBounds()
        }
    }

    private fun applyBounds() {
        val page = drawable ?: return
        bounds.set(0f, 0f, page.intrinsicWidth.toFloat(), page.intrinsicHeight.toFloat())
        transform.mapRect(bounds)
        transform.postTranslate(
            correction(bounds.left, bounds.right, width.toFloat()),
            correction(bounds.top, bounds.bottom, height.toFloat())
        )
        imageMatrix = transform
    }

    private fun correction(start: Float, end: Float, viewport: Float): Float = when {
        end - start <= viewport -> (viewport - end - start) / 2f
        start > 0f -> -start
        end < viewport -> viewport - end
        else -> 0f
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        resetZoom()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pinched = false
                if (zoom > 1f) parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                pinched = true
                parent?.requestDisallowInterceptTouchEvent(true)
            }
        }
        pinch.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun performClick(): Boolean {
        val handled = super.performClick()
        val action = onPageTap
        action?.invoke(tapX)
        tapX = null
        return handled || action != null
    }
}
