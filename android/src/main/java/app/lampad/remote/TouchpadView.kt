package app.lampad.remote

import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

class TouchpadView(
    context: Context,
    private val network: NetworkController,
    private val onSettingsRequested: () -> Unit
) : View(context) {

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var totalMovement = 0f
    private var maxPointers = 1
    private var currentPointers = 0
    private var dragging = false
    private var scrolling = false
    private var lastScrollY = 0f
    private var settingsArmed = false

    private val dragRunnable = Runnable {
        if (currentPointers == 1 && totalMovement < 25f) {
            dragging = true
            network.send("DOWN|LEFT")
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private val settingsRunnable = Runnable {
        if (currentPointers >= 3 && settingsArmed) {
            settingsArmed = false
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onSettingsRequested()
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        isFocusable = true
        keepScreenOn = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        currentPointers = event.pointerCount

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downAt = SystemClock.uptimeMillis()
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                totalMovement = 0f
                maxPointers = 1
                dragging = false
                scrolling = false
                postDelayed(dragRunnable, 450)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = maxOf(maxPointers, event.pointerCount)
                removeCallbacks(dragRunnable)

                if (event.pointerCount >= 2) {
                    scrolling = true
                    lastScrollY = averageY(event)
                }

                if (event.pointerCount >= 3) {
                    settingsArmed = true
                    postDelayed(settingsRunnable, 900)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                maxPointers = maxOf(maxPointers, event.pointerCount)

                if (event.pointerCount == 1 && !scrolling) {
                    val x = event.x
                    val y = event.y
                    val dx = x - lastX
                    val dy = y - lastY
                    totalMovement += hypot(dx.toDouble(), dy.toDouble()).toFloat()

                    if (abs(dx) > 0.2f || abs(dy) > 0.2f) {
                        val sensitivity = 1.55f
                        network.send(
                            "MOVE|${(dx * sensitivity).roundToInt()}|${(dy * sensitivity).roundToInt()}"
                        )
                    }

                    lastX = x
                    lastY = y
                } else if (event.pointerCount >= 2) {
                    val y = averageY(event)
                    val delta = y - lastScrollY

                    if (abs(delta) >= 1f) {
                        network.send("SCROLL|${(-delta * 3f).roundToInt()}")
                        totalMovement += abs(delta)
                    }

                    lastScrollY = y
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount <= 3) {
                    settingsArmed = false
                    removeCallbacks(settingsRunnable)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(dragRunnable)
                removeCallbacks(settingsRunnable)
                settingsArmed = false

                if (dragging) {
                    network.send("UP|LEFT")
                    dragging = false
                } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                    val elapsed = SystemClock.uptimeMillis() - downAt
                    val travel = hypot(
                        (event.x - downX).toDouble(),
                        (event.y - downY).toDouble()
                    ).toFloat()

                    if (elapsed < 320 && travel < 35f && totalMovement < 45f) {
                        if (maxPointers >= 2) {
                            network.send("CLICK|RIGHT")
                        } else {
                            network.send("CLICK|LEFT")
                        }
                    }
                }

                scrolling = false
                currentPointers = 0
            }
        }

        return true
    }

    private fun averageY(event: MotionEvent): Float {
        var total = 0f
        for (i in 0 until event.pointerCount) {
            total += event.getY(i)
        }
        return total / event.pointerCount.coerceAtLeast(1)
    }
}
