package app.lampad.remote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
    private val onSettingsRequested: () -> Unit,
    private val onVoiceToggle: (Boolean) -> Unit
) : View(context) {

    private data class KeyButton(
        val rect: RectF,
        val label: String,
        val code: String,
        val modifier: Boolean = false
    )

    private val density = resources.displayMetrics.density
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(23, 23, 25) }
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(48, 48, 52) }
    private val keyActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(93, 93, 105) }
    private val keyTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 14f * density
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(150, 150, 155)
        textSize = 12f * density
    }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(210, 210, 215)
        textSize = 13f * density
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(58, 58, 65) }

    private val keyboardButtons = mutableListOf<KeyButton>()
    private val stickyModifiers = linkedSetOf<String>()

    private var keyboardVisible = false
    private var voiceEnabled = true
    private var editableFocus = false
    private var statusText = "در حال اتصال…"

    private var keyboardLeft = Float.MAX_VALUE
    private var toolbarLeft = Float.MAX_VALUE
    private var pressedKey: KeyButton? = null
    private var pressedToolbar: String? = null

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
        if (currentPointers == 1 && totalMovement < dp(18f) && pressedKey == null && pressedToolbar == null) {
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
        isFocusable = true
        keepScreenOn = true
        setBackgroundColor(Color.BLACK)
    }

    fun setStatus(message: String) {
        statusText = message
        invalidate()
    }

    fun setEditableFocus(editable: Boolean) {
        editableFocus = editable
        invalidate()
    }

    fun setVoiceEnabled(enabled: Boolean) {
        voiceEnabled = enabled
        invalidate()
    }

    fun setModifierState(key: String, down: Boolean) {
        val normalized = key.uppercase()
        if (normalized == "ALL") {
            stickyModifiers.clear()
        } else if (down) {
            stickyModifiers.add(normalized)
        } else {
            stickyModifiers.remove(normalized)
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildKeyboard(w.toFloat(), h.toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        val mode = if (editableFocus) "دیکته" else "فرمان"
        val mic = if (voiceEnabled) "روشن" else "خاموش"
        canvas.drawText("🎙 " + mic + "  •  " + mode, dp(18f), dp(28f), statusPaint)
        canvas.drawText(statusText, dp(18f), dp(50f), hintPaint)

        if (!keyboardVisible) {
            canvas.drawText(
                "سطح لمسی ماوس  •  دو انگشت: اسکرول/راست‌کلیک  •  نگه‌داشتن: Drag",
                dp(18f),
                height - dp(18f),
                hintPaint
            )
        }

        if (keyboardVisible) {
            canvas.drawRect(keyboardLeft, 0f, width.toFloat(), height.toFloat(), panelPaint)
            keyboardButtons.forEach { button ->
                val active = button.modifier && stickyModifiers.contains(button.code)
                canvas.drawRoundRect(
                    button.rect,
                    dp(7f),
                    dp(7f),
                    if (active) keyActivePaint else keyPaint
                )

                val baseline = button.rect.centerY() -
                    (keyTextPaint.ascent() + keyTextPaint.descent()) / 2f
                canvas.drawText(button.label, button.rect.centerX(), baseline, keyTextPaint)
            }
        }

        drawToolbar(canvas)
    }

    private fun drawToolbar(canvas: Canvas) {
        val margin = dp(8f)
        val size = dp(48f)
        toolbarLeft = width - size - margin

        val labels = listOf(
            if (keyboardVisible) "×" else "⌨",
            if (voiceEnabled) "🎙" else "🔇",
            "⚙"
        )

        labels.forEachIndexed { index, label ->
            val top = margin + index * (size + margin)
            val rect = RectF(toolbarLeft, top, toolbarLeft + size, top + size)
            canvas.drawRoundRect(rect, dp(10f), dp(10f), accentPaint)
            val baseline = rect.centerY() -
                (keyTextPaint.ascent() + keyTextPaint.descent()) / 2f
            canvas.drawText(label, rect.centerX(), baseline, keyTextPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        currentPointers = event.pointerCount

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            pressedToolbar = toolbarHit(event.x, event.y)
            if (pressedToolbar != null) {
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }

            if (keyboardVisible && event.x >= keyboardLeft) {
                pressedKey = keyboardButtons.firstOrNull { it.rect.contains(event.x, event.y) }
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
        }

        if (pressedToolbar != null) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val action = toolbarHit(event.x, event.y)
                if (action == pressedToolbar) triggerToolbar(action)
                pressedToolbar = null
            } else if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                pressedToolbar = null
            }
            return true
        }

        if (pressedKey != null) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val key = keyboardButtons.firstOrNull { it.rect.contains(event.x, event.y) }
                if (key == pressedKey && key != null) triggerKey(key)
                pressedKey = null
            } else if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                pressedKey = null
            }
            return true
        }

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

                    if (abs(dx) > 0.15f || abs(dy) > 0.15f) {
                        val speed = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                        val acceleration = when {
                            speed > dp(18f) -> 2.15f
                            speed > dp(8f) -> 1.75f
                            else -> 1.35f
                        }

                        network.send(
                            "MOVE|" + (dx * acceleration).roundToInt() + "|" +
                                (dy * acceleration).roundToInt()
                        )
                    }

                    lastX = x
                    lastY = y
                } else if (event.pointerCount >= 2) {
                    val y = averageY(event)
                    val delta = y - lastScrollY

                    if (abs(delta) >= 0.8f) {
                        network.send("SCROLL|" + (-delta * 3.2f).roundToInt())
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

                    if (elapsed < 340 && travel < dp(24f) && totalMovement < dp(32f)) {
                        network.send(if (maxPointers >= 2) "CLICK|RIGHT" else "CLICK|LEFT")
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                }

                scrolling = false
                currentPointers = 0
            }
        }

        return true
    }

    private fun triggerToolbar(action: String?) {
        when (action) {
            "KEYBOARD" -> {
                keyboardVisible = !keyboardVisible
                invalidate()
            }

            "MIC" -> {
                voiceEnabled = !voiceEnabled
                onVoiceToggle(voiceEnabled)
                invalidate()
            }

            "SETTINGS" -> onSettingsRequested()
        }
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun triggerKey(key: KeyButton) {
        if (key.modifier) {
            val isDown = stickyModifiers.contains(key.code)
            if (isDown) {
                stickyModifiers.remove(key.code)
                network.send("KEY_UP|" + key.code)
            } else {
                stickyModifiers.add(key.code)
                network.send("KEY_DOWN|" + key.code)
            }
        } else {
            network.send("KEY|" + key.code)
        }

        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        invalidate()
    }

    private fun toolbarHit(x: Float, y: Float): String? {
        val margin = dp(8f)
        val size = dp(48f)
        val left = width - size - margin
        if (x < left || x > left + size) return null

        val index = ((y - margin) / (size + margin)).toInt()
        if (index !in 0..2) return null

        val top = margin + index * (size + margin)
        if (y !in top..(top + size)) return null

        return when (index) {
            0 -> "KEYBOARD"
            1 -> "MIC"
            else -> "SETTINGS"
        }
    }

    private fun rebuildKeyboard(w: Float, h: Float) {
        keyboardButtons.clear()

        val panelWidth = (w * 0.48f).coerceAtMost(dp(620f))
        keyboardLeft = w - panelWidth
        val margin = dp(7f)
        val top = dp(10f)
        val availableWidth = panelWidth - margin * 2
        val rowHeight = ((h - dp(20f) - margin * 5) / 5f).coerceAtLeast(dp(42f))

        addRow(
            labels = listOf("1","2","3","4","5","6","7","8","9","0","⌫"),
            codes = listOf("1","2","3","4","5","6","7","8","9","0","BACKSPACE"),
            y = top,
            rowHeight = rowHeight,
            left = keyboardLeft + margin,
            width = availableWidth
        )
        addRow(
            labels = listOf("Q","W","E","R","T","Y","U","I","O","P"),
            codes = listOf("Q","W","E","R","T","Y","U","I","O","P"),
            y = top + (rowHeight + margin),
            rowHeight = rowHeight,
            left = keyboardLeft + margin,
            width = availableWidth
        )
        addRow(
            labels = listOf("A","S","D","F","G","H","J","K","L","Enter"),
            codes = listOf("A","S","D","F","G","H","J","K","L","ENTER"),
            y = top + (rowHeight + margin) * 2,
            rowHeight = rowHeight,
            left = keyboardLeft + margin,
            width = availableWidth
        )
        addRow(
            labels = listOf("Shift","Z","X","C","V","B","N","M","↑","Esc"),
            codes = listOf("SHIFT","Z","X","C","V","B","N","M","UP","ESC"),
            y = top + (rowHeight + margin) * 3,
            rowHeight = rowHeight,
            left = keyboardLeft + margin,
            width = availableWidth,
            modifiers = setOf(0)
        )
        addRow(
            labels = listOf("Ctrl","Win","Alt","←","Space","→","↓","Tab"),
            codes = listOf("CTRL","WIN","ALT","LEFT","SPACE","RIGHT","DOWN","TAB"),
            y = top + (rowHeight + margin) * 4,
            rowHeight = rowHeight,
            left = keyboardLeft + margin,
            width = availableWidth,
            modifiers = setOf(0, 1, 2),
            weights = listOf(1f,1f,1f,1f,2.8f,1f,1f,1f)
        )
    }

    private fun addRow(
        labels: List<String>,
        codes: List<String>,
        y: Float,
        rowHeight: Float,
        left: Float,
        width: Float,
        modifiers: Set<Int> = emptySet(),
        weights: List<Float> = List(labels.size) { 1f }
    ) {
        val gap = dp(4f)
        val totalWeight = weights.sum()
        val usable = width - gap * (labels.size - 1)
        var x = left

        labels.indices.forEach { i ->
            val keyWidth = usable * (weights[i] / totalWeight)
            keyboardButtons += KeyButton(
                RectF(x, y, x + keyWidth, y + rowHeight),
                labels[i],
                codes[i],
                i in modifiers
            )
            x += keyWidth + gap
        }
    }

    private fun averageY(event: MotionEvent): Float {
        var total = 0f
        for (i in 0 until event.pointerCount) {
            total += event.getY(i)
        }
        return total / event.pointerCount.coerceAtLeast(1)
    }

    private fun dp(value: Float): Float = value * density
}
