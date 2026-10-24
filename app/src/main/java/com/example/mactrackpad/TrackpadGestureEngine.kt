package com.example.mactrackpad

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Choreographer
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Full-Screen macOS Multi-Touch Trackpad Gesture Engine.
 *
 * Configured for macOS Three-Finger Drag + Four-Finger Mission Control / Spaces:
 * - 1-finger move: Cursor movement with velocity acceleration curve
 * - 1-finger single tap: Left Click
 * - 1-finger double-tap & hold: Tap-and-Drag (also supported alongside 3-finger drag)
 * - 2-finger single tap: Right Click
 * - 2-finger vertical & horizontal scroll with 60fps fling inertia
 * - 2-finger double-tap: Smart Zoom toggle (Cmd + '+' / Cmd + '0')
 * - 2-finger pinch in / out: Smooth stepped Zoom (Cmd + '+' / Cmd + '-')
 * - 3-finger drag: Text Selection & Window Dragging (holds Left Button down while moving centroid,
 *   with a 260ms edge-continuation grace window so you can reposition 3 fingers during long selections)
 * - 4-finger swipe Up / Down / Left / Right: Mission Control (⌃↑), App Exposé (⌃↓), Switch Spaces (⌃→ / ⌃←)
 */
class TrackpadGestureEngine(
    context: Context,
    private val hid: BluetoothHidController,
    private val onGestureFeedback: (label: String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // User-tunable parameters
    var cursorSensitivity: Float = 1.65f
    var scrollSensitivity: Float = 0.35f
    var naturalScrolling: Boolean = true
    var threeFingerDragEnabled: Boolean = true

    // Pointer tracking state
    private var maxPointersInGesture = 0
    private var gestureStartTime = 0L
    private var totalMoveDistance = 0f

    // 1-finger tracking & Tap-to-Drag state
    private var lastOneX = 0f
    private var lastOneY = 0f
    private var residualX = 0f
    private var residualY = 0f
    private var lastSingleTapUpTime = 0L
    private var isTapDragging = false
    private val pendingSingleClickRunnable = Runnable {
        if (!isTapDragging) {
            performLeftClick()
        }
    }

    // 2-finger scroll, right-click, smart-zoom, and pinch state
    private var lastTwoCenterX = 0f
    private var lastTwoCenterY = 0f
    private var scrollResidualX = 0f
    private var scrollResidualY = 0f
    private var initialPinchSpan = 0f
    private var activePinchSpan = 0f
    private var isPinching = false
    private var lastTwoFingerTapUpTime = 0L
    private var smartZoomedIn = false
    private val pendingRightClickRunnable = Runnable {
        performRightClick()
    }

    // Smooth 2-finger scroll inertia
    private var velocityTracker: VelocityTracker? = null
    private var flingVx = 0f
    private var flingVy = 0f
    private var isFlinging = false
    private val flingCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isFlinging) return
            flingVx *= 0.92f
            flingVy *= 0.92f
            val hStep = (flingVx * 0.012f * scrollSensitivity).roundToInt()
            val vStep = (flingVy * 0.012f * scrollSensitivity).roundToInt()
            if (abs(hStep) == 0 && abs(vStep) == 0 && hypot(flingVx, flingVy) < 40f) {
                isFlinging = false
                return
            }
            val dir = if (naturalScrolling) 1 else -1
            if (hStep != 0 || vStep != 0) {
                hid.sendMouseReport(
                    leftButton = false,
                    rightButton = false,
                    dx = 0,
                    dy = 0,
                    vScroll = vStep * dir,
                    hScroll = -hStep * dir
                )
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // 3-finger selection / drag state
    private var lastThreeCenterX = 0f
    private var lastThreeCenterY = 0f
    private var threeDragResidualX = 0f
    private var threeDragResidualY = 0f
    private var isThreeFingerDragging = false
    private val releaseThreeFingerDragRunnable = Runnable {
        if (isThreeFingerDragging) {
            isThreeFingerDragging = false
            hid.sendMouseReport(leftButton = false, rightButton = false, dx = 0, dy = 0)
            onGestureFeedback("3-Finger Selection Released")
        }
    }

    // 4-finger swipe state (Mission Control, App Expose, Switch Spaces)
    private var fourStartX = 0f
    private var fourStartY = 0f
    private var fourSwipeTriggered = false

    fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val pointerCount = event.pointerCount

        // Cancel any ongoing scroll inertia as soon as fingers touch down
        if (action == MotionEvent.ACTION_DOWN) {
            stopFling()
        }

        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        }
        velocityTracker?.addMovement(event)

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                maxPointersInGesture = 1
                gestureStartTime = System.currentTimeMillis()
                totalMoveDistance = 0f
                lastOneX = event.x
                lastOneY = event.y
                residualX = 0f
                residualY = 0f

                // Check if this is the second tap of a 1-finger double-tap -> Tap-and-Drag
                val timeSinceLastTap = gestureStartTime - lastSingleTapUpTime
                if (timeSinceLastTap in 1..230L) {
                    mainHandler.removeCallbacks(pendingSingleClickRunnable)
                    isTapDragging = true
                    hid.sendMouseReport(leftButton = true, rightButton = false, dx = 0, dy = 0)
                    hapticTick()
                    onGestureFeedback("Drag Locked")
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointerCount > maxPointersInGesture) {
                    maxPointersInGesture = pointerCount
                }
                // Cancel any pending 1-finger tap or 1-finger drag if additional fingers land
                mainHandler.removeCallbacks(pendingSingleClickRunnable)
                if (isTapDragging) {
                    isTapDragging = false
                    hid.sendMouseReport(leftButton = false, rightButton = false, dx = 0, dy = 0)
                }

                when (pointerCount) {
                    2 -> {
                        lastTwoCenterX = (event.getX(0) + event.getX(1)) * 0.5f
                        lastTwoCenterY = (event.getY(0) + event.getY(1)) * 0.5f
                        initialPinchSpan = getSpan(event)
                        activePinchSpan = initialPinchSpan
                        isPinching = false
                        scrollResidualX = 0f
                        scrollResidualY = 0f
                    }
                    3 -> {
                        mainHandler.removeCallbacks(pendingRightClickRunnable)
                        mainHandler.removeCallbacks(releaseThreeFingerDragRunnable)
                        val c = getCentroid(event, 3)
                        lastThreeCenterX = c.first
                        lastThreeCenterY = c.second
                        threeDragResidualX = 0f
                        threeDragResidualY = 0f
                        if (threeFingerDragEnabled && !isThreeFingerDragging) {
                            isThreeFingerDragging = true
                            hid.sendMouseReport(leftButton = true, rightButton = false, dx = 0, dy = 0)
                            hapticTick()
                            onGestureFeedback("3-Finger Selection / Drag")
                        }
                    }
                    4 -> {
                        // Upgrading from 3 fingers to 4 fingers: cancel 3-finger selection immediately
                        mainHandler.removeCallbacks(releaseThreeFingerDragRunnable)
                        if (isThreeFingerDragging) {
                            isThreeFingerDragging = false
                            hid.sendMouseReport(leftButton = false, rightButton = false, dx = 0, dy = 0)
                        }
                        val c4 = getCentroid(event, 4)
                        fourStartX = c4.first
                        fourStartY = c4.second
                        fourSwipeTriggered = false
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                when (pointerCount) {
                    1 -> {
                        if (maxPointersInGesture == 1) {
                            val rawDx = event.x - lastOneX
                            val rawDy = event.y - lastOneY
                            lastOneX = event.x
                            lastOneY = event.y

                            val dist = hypot(rawDx, rawDy)
                            totalMoveDistance += dist

                            // Velocity-based pointer acceleration curve (macOS-like feel)
                            val speed = dist.coerceAtLeast(0.5f)
                            val accel = (speed.pow(0.32f) * cursorSensitivity).coerceIn(0.8f, 4.2f)

                            val scaledDx = rawDx * accel + residualX
                            val scaledDy = rawDy * accel + residualY
                            val intDx = scaledDx.roundToInt()
                            val intDy = scaledDy.roundToInt()
                            residualX = scaledDx - intDx
                            residualY = scaledDy - intDy

                            if (intDx != 0 || intDy != 0) {
                                hid.sendMouseReport(
                                    leftButton = isTapDragging,
                                    rightButton = false,
                                    dx = intDx,
                                    dy = intDy
                                )
                            }
                        }
                    }

                    2 -> {
                        if (maxPointersInGesture == 2) {
                            val cx = (event.getX(0) + event.getX(1)) * 0.5f
                            val cy = (event.getY(0) + event.getY(1)) * 0.5f
                            val dx = cx - lastTwoCenterX
                            val dy = cy - lastTwoCenterY
                            lastTwoCenterX = cx
                            lastTwoCenterY = cy

                            val moveStep = hypot(dx, dy)
                            totalMoveDistance += moveStep

                            // Check for Pinch-to-Zoom vs 2-Finger Scroll
                            val currentSpan = getSpan(event)
                            val spanDeltaFromInit = abs(currentSpan - initialPinchSpan)

                            if (!isPinching && spanDeltaFromInit > touchSlop * 2.2f &&
                                spanDeltaFromInit > totalMoveDistance * 0.65f
                            ) {
                                isPinching = true
                                activePinchSpan = currentSpan
                            }

                            if (isPinching && activePinchSpan > 10f) {
                                val ratio = currentSpan / activePinchSpan
                                if (ratio >= 1.13f) {
                                    // Pinch Out -> Zoom In (Cmd + '=')
                                    hid.sendShortcut(HidDescriptor.MOD_LEFT_GUI, HidDescriptor.KEY_EQUAL_PLUS)
                                    activePinchSpan = currentSpan
                                    hapticTick()
                                    onGestureFeedback("Pinch Zoom In (⌘+)")
                                } else if (ratio <= 0.88f) {
                                    // Pinch In -> Zoom Out (Cmd + '-')
                                    hid.sendShortcut(HidDescriptor.MOD_LEFT_GUI, HidDescriptor.KEY_MINUS)
                                    activePinchSpan = currentSpan
                                    hapticTick()
                                    onGestureFeedback("Pinch Zoom Out (⌘-)")
                                }
                            } else if (!isPinching) {
                                // 2-Finger Vertical & Horizontal Scroll
                                val dir = if (naturalScrolling) 1 else -1
                                val scaledScrollX = dx * scrollSensitivity + scrollResidualX
                                val scaledScrollY = dy * scrollSensitivity + scrollResidualY
                                val intScrollX = scaledScrollX.roundToInt()
                                val intScrollY = scaledScrollY.roundToInt()
                                scrollResidualX = scaledScrollX - intScrollX
                                scrollResidualY = scaledScrollY - intScrollY

                                if (intScrollX != 0 || intScrollY != 0) {
                                    hid.sendMouseReport(
                                        leftButton = false,
                                        rightButton = false,
                                        dx = 0,
                                        dy = 0,
                                        vScroll = intScrollY * dir,
                                        hScroll = -intScrollX * dir
                                    )
                                }
                            }
                        }
                    }

                    3 -> {
                        if (maxPointersInGesture == 3 && threeFingerDragEnabled) {
                            // 3-Finger Selection & Drag: holds Left Mouse Button while moving cursor
                            val (cx, cy) = getCentroid(event, 3)
                            val rawDx = cx - lastThreeCenterX
                            val rawDy = cy - lastThreeCenterY
                            lastThreeCenterX = cx
                            lastThreeCenterY = cy

                            val dist = hypot(rawDx, rawDy)
                            totalMoveDistance += dist

                            val speed = dist.coerceAtLeast(0.5f)
                            val accel = (speed.pow(0.32f) * cursorSensitivity).coerceIn(0.8f, 4.2f)

                            val scaledDx = rawDx * accel + threeDragResidualX
                            val scaledDy = rawDy * accel + threeDragResidualY
                            val intDx = scaledDx.roundToInt()
                            val intDy = scaledDy.roundToInt()
                            threeDragResidualX = scaledDx - intDx
                            threeDragResidualY = scaledDy - intDy

                            if (!isThreeFingerDragging) {
                                isThreeFingerDragging = true
                            }
                            if (intDx != 0 || intDy != 0) {
                                hid.sendMouseReport(
                                    leftButton = true,
                                    rightButton = false,
                                    dx = intDx,
                                    dy = intDy
                                )
                            }
                        } else if (maxPointersInGesture == 3 && !threeFingerDragEnabled && !fourSwipeTriggered) {
                            // Optional fallback if user switches 3-finger drag off in settings
                            handleMissionControlSwipe(event, 3, lastThreeCenterX, lastThreeCenterY)
                        }
                    }

                    4 -> {
                        if (!fourSwipeTriggered && maxPointersInGesture >= 4) {
                            handleMissionControlSwipe(event, 4, fourStartX, fourStartY)
                        }
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (pointerCount == 2 && maxPointersInGesture == 2 && !isPinching && totalMoveDistance > touchSlop) {
                    // Launch smooth 2-finger scroll inertia on lift
                    velocityTracker?.computeCurrentVelocity(1000)
                    val vx = velocityTracker?.xVelocity ?: 0f
                    val vy = velocityTracker?.yVelocity ?: 0f
                    if (hypot(vx, vy) > 220f) {
                        startFling(vx, vy)
                    }
                } else if (pointerCount == 3 && isThreeFingerDragging) {
                    // Brief 250ms continuation window so user can reposition 3 fingers at screen edge
                    mainHandler.removeCallbacks(releaseThreeFingerDragRunnable)
                    mainHandler.postDelayed(releaseThreeFingerDragRunnable, 250L)
                }
            }

            MotionEvent.ACTION_UP -> {
                val now = System.currentTimeMillis()
                val duration = now - gestureStartTime

                if (isTapDragging) {
                    isTapDragging = false
                    hid.sendMouseReport(leftButton = false, rightButton = false, dx = 0, dy = 0)
                    onGestureFeedback("Drag Released")
                } else if (isThreeFingerDragging) {
                    mainHandler.removeCallbacks(releaseThreeFingerDragRunnable)
                    mainHandler.postDelayed(releaseThreeFingerDragRunnable, 250L)
                } else if (maxPointersInGesture == 1 && duration < 220L && totalMoveDistance < touchSlop) {
                    // 1-Finger Tap: wait briefly in case user starts a 1-finger Tap-and-Drag
                    lastSingleTapUpTime = now
                    mainHandler.postDelayed(pendingSingleClickRunnable, 175L)
                } else if (maxPointersInGesture == 2 && !isPinching && duration < 250L && totalMoveDistance < touchSlop * 1.4f) {
                    // Distinguish 2-Finger Single Tap (Right Click) vs 2-Finger Double-Tap (Smart Zoom)
                    val sinceLastTwoTap = now - lastTwoFingerTapUpTime
                    if (sinceLastTwoTap in 1..280L) {
                        mainHandler.removeCallbacks(pendingRightClickRunnable)
                        lastTwoFingerTapUpTime = 0L
                        performSmartZoomToggle()
                    } else {
                        lastTwoFingerTapUpTime = now
                        mainHandler.postDelayed(pendingRightClickRunnable, 190L)
                    }
                }

                velocityTracker?.recycle()
                velocityTracker = null
            }

            MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(releaseThreeFingerDragRunnable)
                if (isTapDragging || isThreeFingerDragging) {
                    isTapDragging = false
                    isThreeFingerDragging = false
                    hid.sendMouseReport(leftButton = false, rightButton = false, dx = 0, dy = 0)
                }
                stopFling()
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
        return true
    }

    private fun handleMissionControlSwipe(
        event: MotionEvent,
        fingers: Int,
        startX: Float,
        startY: Float
    ) {
        val (cx, cy) = getCentroid(event, fingers)
        val dx = cx - startX
        val dy = cy - startY
        val swipeThreshold = touchSlop * 3.2f

        if (abs(dx) > swipeThreshold || abs(dy) > swipeThreshold) {
            fourSwipeTriggered = true
            hapticTick()
            if (abs(dy) > abs(dx)) {
                if (dy < 0) {
                    // 4-Finger Swipe Up -> Mission Control (Ctrl + Up)
                    hid.sendShortcut(HidDescriptor.MOD_LEFT_CTRL, HidDescriptor.KEY_UP_ARROW)
                    onGestureFeedback("${fingers}-Finger Mission Control (⌃↑)")
                } else {
                    // 4-Finger Swipe Down -> App Expose (Ctrl + Down)
                    hid.sendShortcut(HidDescriptor.MOD_LEFT_CTRL, HidDescriptor.KEY_DOWN_ARROW)
                    onGestureFeedback("${fingers}-Finger App Exposé (⌃↓)")
                }
            } else {
                if (dx < 0) {
                    // 4-Finger Swipe Left -> Next Space (Ctrl + Right)
                    hid.sendShortcut(HidDescriptor.MOD_LEFT_CTRL, HidDescriptor.KEY_RIGHT_ARROW)
                    onGestureFeedback("${fingers}-Finger Next Space (⌃→)")
                } else {
                    // 4-Finger Swipe Right -> Previous Space (Ctrl + Left)
                    hid.sendShortcut(HidDescriptor.MOD_LEFT_CTRL, HidDescriptor.KEY_LEFT_ARROW)
                    onGestureFeedback("${fingers}-Finger Previous Space (⌃←)")
                }
            }
        }
    }

    private fun performLeftClick() {
        hapticTick()
        hid.sendMouseReport(leftButton = true, rightButton = false, dx = 0, dy = 0)
        mainHandler.postDelayed({
            hid.sendMouseReport(leftButton = false, rightButton = false, dx = 0, dy = 0)
        }, 20L)
        onGestureFeedback("Click")
    }

    private fun performRightClick() {
        hapticTick()
        hid.sendMouseReport(leftButton = false, rightButton = true, dx = 0, dy = 0)
        mainHandler.postDelayed({
            hid.sendMouseReport(leftButton = false, rightButton = false, dx = 0, dy = 0)
        }, 25L)
        onGestureFeedback("Right Click")
    }

    private fun performSmartZoomToggle() {
        hapticTick()
        smartZoomedIn = !smartZoomedIn
        if (smartZoomedIn) {
            // Send 2 rapid Cmd + '+' steps for macOS Smart Zoom In
            hid.sendShortcut(HidDescriptor.MOD_LEFT_GUI, HidDescriptor.KEY_EQUAL_PLUS)
            mainHandler.postDelayed({
                hid.sendShortcut(HidDescriptor.MOD_LEFT_GUI, HidDescriptor.KEY_EQUAL_PLUS)
            }, 55L)
            onGestureFeedback("Smart Zoom In (2-Finger Double-Tap)")
        } else {
            // Reset zoom to 100% (Cmd + '0')
            hid.sendShortcut(HidDescriptor.MOD_LEFT_GUI, HidDescriptor.KEY_0)
            onGestureFeedback("Smart Zoom Reset (⌘0)")
        }
    }

    private fun startFling(vx: Float, vy: Float) {
        flingVx = vx
        flingVy = vy
        isFlinging = true
        Choreographer.getInstance().postFrameCallback(flingCallback)
    }

    private fun stopFling() {
        if (isFlinging) {
            isFlinging = false
            Choreographer.getInstance().removeFrameCallback(flingCallback)
        }
    }

    private fun getSpan(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return hypot(dx, dy)
    }

    private fun getCentroid(event: MotionEvent, count: Int): Pair<Float, Float> {
        var sumX = 0f
        var sumY = 0f
        val n = count.coerceAtMost(event.pointerCount)
        for (i in 0 until n) {
            sumX += event.getX(i)
            sumY += event.getY(i)
        }
        return Pair(sumX / n, sumY / n)
    }

    private fun hapticTick() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(8L)
            }
        } catch (_: Exception) {
        }
    }
}
