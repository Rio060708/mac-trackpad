package com.example.mactrackpad

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/**
 * Edge-to-Edge Full-Screen Trackpad Canvas View.
 * Renders subtle visual touch ripples for active fingers while passing 100% of
 * screen area events directly into [TrackpadGestureEngine].
 */
class TrackpadSurfaceView(
    context: Context,
    private val gestureEngine: TrackpadGestureEngine
) : View(context) {

    private data class TouchPoint(val x: Float, val y: Float)

    private val activePoints = mutableListOf<TouchPoint>()

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#0A0A0E")
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E2230")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val touchFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#335E9EFF")
        style = Paint.Style.FILL
    }

    private val touchRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#997AB8FF")
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        val inset = 12f
        canvas.drawRoundRect(
            inset,
            inset,
            width - inset,
            height - inset,
            36f,
            36f,
            borderPaint
        )

        for (pt in activePoints) {
            canvas.drawCircle(pt.x, pt.y, 58f, touchFillPaint)
            canvas.drawCircle(pt.x, pt.y, 58f, touchRingPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        activePoints.clear()
        val action = event.actionMasked
        if (action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) {
            for (i in 0 until event.pointerCount) {
                if (action == MotionEvent.ACTION_POINTER_UP && i == event.actionIndex) continue
                activePoints.add(TouchPoint(event.getX(i), event.getY(i)))
            }
        }
        invalidate()
        return gestureEngine.onTouchEvent(event)
    }
}
