package dev.abode.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/**
 * The whole key grid as ONE view, drawn in a single Canvas pass and hit-tested
 * against precomputed rectangles.
 *
 * Why not one view (or one composable) per key:
 *  - N children means N measure/layout passes and N state reads per frame.
 *  - Pressing a key only needs the pressed key's rectangle invalidated, which
 *    is what [invalidateKey] does. Repainting 40 keys on every touch is the
 *    single most common reason a custom keyboard feels heavy.
 *
 * Long press: hold a key with alternates and a popup strip opens above it; slide
 * to pick, lift to commit. This is why the number row is a convenience rather
 * than a requirement.
 *
 * Allocation rule: nothing in onDraw allocates. Paints and Rects are created
 * once; the geometry list is rebuilt only on size or layout change.
 */
class KeyGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        /** Fires on touch-down. Only keys that act immediately (delete) use it. */
        fun onKeyDown(key: Key)

        /** Fires on touch-up, or when a long-press alternate is chosen. */
        fun onKeyUp(key: Key)
    }

    var listener: Listener? = null

    var keyboardLayout: KeyboardLayout = KeyboardLayout.qwerty()
        set(value) {
            if (field === value) return
            field = value
            dismissPopup()
            rebuildGeometry()
            requestLayout()
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val rowHeight = 50f * density
    private val gap = 4f * density
    private val corner = 7f * density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val rects = ArrayList<RectF>(64)
    private val keys = ArrayList<Key>(64)
    private var pressedIndex = -1

    // Long-press popup state
    private val handler = Handler(Looper.getMainLooper())
    private val popupRects = ArrayList<RectF>(6)
    private var popupKeys: List<Key> = emptyList()
    private var popupSelected = -1
    private var popupAbove = true
    private var downX = 0f
    private var downY = 0f
    private var longPressFired = false

    private val longPress = Runnable {
        val index = pressedIndex
        if (index < 0 || index >= keys.size) return@Runnable
        val key = keys[index]
        if (!key.hasAlternates) return@Runnable
        longPressFired = true
        openPopup(index, key)
    }

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_KEY }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_KEY_PRESSED }
    private val popupPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_POPUP }
    private val popupSelectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_KEY_PRESSED }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_LABEL
        textAlign = Paint.Align.CENTER
        textSize = 15f * resources.displayMetrics.scaledDensity
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    init {
        isClickable = true
        isHapticFeedbackEnabled = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val rows = keyboardLayout.rowCount.coerceAtLeast(1)
        val desired = (rows * rowHeight + (rows + 1) * gap).toInt()
        setMeasuredDimension(width, resolveSize(desired, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildGeometry()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(longPress)
        super.onDetachedFromWindow()
    }

    private fun rebuildGeometry() {
        rects.clear()
        keys.clear()
        val w = width.toFloat()
        val h = height.toFloat()
        val rows = keyboardLayout.rows
        if (w <= 0f || h <= 0f || rows.isEmpty()) return

        val rowHeightPx = (h - gap * (rows.size + 1)) / rows.size
        var top = gap

        for (row in rows) {
            var totalWeight = 0f
            for (key in row) totalWeight += key.widthWeight
            if (totalWeight <= 0f) continue

            val available = w - gap * (row.size + 1)
            var left = gap

            for (key in row) {
                val keyWidth = available * (key.widthWeight / totalWeight)
                rects.add(RectF(left, top, left + keyWidth, top + rowHeightPx))
                keys.add(key)
                left += keyWidth + gap
            }
            top += rowHeightPx + gap
        }
    }

    override fun onDraw(canvas: Canvas) {
        val count = rects.size
        for (i in 0 until count) {
            val r = rects[i]
            canvas.drawRoundRect(r, corner, corner, if (i == pressedIndex) pressedPaint else keyPaint)

            val label = keys[i].label
            if (label.isEmpty()) continue

            val baseline = r.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
            canvas.drawText(label, r.centerX(), baseline, labelPaint)
        }

        if (popupKeys.isNotEmpty()) drawPopup(canvas)
    }

    private fun drawPopup(canvas: Canvas) {
        for (i in popupRects.indices) {
            val r = popupRects[i]
            canvas.drawRoundRect(
                r,
                corner,
                corner,
                if (i == popupSelected) popupSelectedPaint else popupPaint,
            )
            val baseline = r.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
            canvas.drawText(popupKeys[i].label, r.centerX(), baseline, labelPaint)
        }
    }

    // ------------------------------------------------------------- touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                longPressFired = false
                setPressedKey(indexAt(event.x, event.y))
                if (pressedIndex >= 0 && keys[pressedIndex].hasAlternates) {
                    handler.postDelayed(longPress, LONG_PRESS_MS)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (popupKeys.isNotEmpty()) {
                    updatePopupSelection(event.x, event.y)
                    return true
                }
                if (!longPressFired &&
                    (Math.abs(event.x - downX) > touchSlop || Math.abs(event.y - downY) > touchSlop)
                ) {
                    handler.removeCallbacks(longPress)
                }
                setPressedKey(indexAt(event.x, event.y))
                return true
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)

                if (popupKeys.isNotEmpty()) {
                    updatePopupSelection(event.x, event.y)
                    val chosen = popupKeys.getOrNull(popupSelected)
                    dismissPopup()
                    pressedIndex = -1
                    invalidate()
                    if (chosen != null) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        listener?.onKeyUp(chosen)
                    }
                    return true
                }

                val index = indexAt(event.x, event.y)
                pressedIndex = -1
                invalidate()
                if (index >= 0 && index < keys.size) listener?.onKeyUp(keys[index])
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                dismissPopup()
                pressedIndex = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun openPopup(index: Int, key: Key) {
        val anchor = rects[index]
        popupKeys = key.alternates.map { key.withOutput(it) }
        popupRects.clear()
        if (popupKeys.isEmpty()) return

        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

        val cellWidth = anchor.width()
        val totalWidth = cellWidth * popupKeys.size
        val startX = (anchor.centerX() - totalWidth / 2f).coerceIn(gap, width - totalWidth - gap)
        val height = anchor.height() * 1.15f

        // Flip below the key when there is no room above it (the number row).
        popupAbove = anchor.top - height - gap >= 0f
        val top = if (popupAbove) anchor.top - height - gap else anchor.bottom + gap

        for (i in popupKeys.indices) {
            val left = startX + i * cellWidth
            popupRects.add(RectF(left, top, left + cellWidth, top + height))
        }
        popupSelected = -1
        invalidate()
    }

    private fun updatePopupSelection(x: Float, y: Float) {
        if (popupRects.isEmpty()) return
        val row = popupRects.first()
        val insideRow = y >= row.top - gap * 2 && y <= row.bottom + gap * 2
        val newSelection = if (!insideRow) {
            -1
        } else {
            var best = -1
            var bestDistance = Float.MAX_VALUE
            for (i in popupRects.indices) {
                val distance = Math.abs(popupRects[i].centerX() - x)
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = i
                }
            }
            best
        }
        if (newSelection != popupSelected) {
            popupSelected = newSelection
            invalidate()
        }
    }

    private fun dismissPopup() {
        if (popupKeys.isEmpty() && popupRects.isEmpty()) return
        popupKeys = emptyList()
        popupRects.clear()
        popupSelected = -1
        invalidate()
    }

    private fun setPressedKey(index: Int) {
        if (index == pressedIndex) return
        val previous = pressedIndex
        pressedIndex = index
        if (previous >= 0) invalidateKey(previous)
        if (index >= 0) {
            invalidateKey(index)
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            listener?.onKeyDown(keys[index])
        }
    }

    /** Partial invalidation: repaint this key's rectangle only. */
    private fun invalidateKey(index: Int) {
        val r = rects.getOrNull(index) ?: return
        invalidate(
            (r.left - 2f).toInt(),
            (r.top - 2f).toInt(),
            (r.right + 2f).toInt(),
            (r.bottom + 2f).toInt(),
        )
    }

    private fun indexAt(x: Float, y: Float): Int {
        for (i in rects.indices) {
            if (rects[i].contains(x, y)) return i
        }
        return -1
    }

    private companion object {
        const val LONG_PRESS_MS = 320L
        const val COLOR_KEY = 0xFFF4F4F5.toInt()
        const val COLOR_KEY_PRESSED = 0xFFE8641A.toInt()
        const val COLOR_LABEL = 0xFF1A1A1A.toInt()
        const val COLOR_POPUP = 0xFFFFFFFF.toInt()
    }
}
