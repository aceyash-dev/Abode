package dev.abode.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

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
 * Allocation rule: nothing in onDraw allocates. Paints and Rects are created
 * once; the geometry list is rebuilt only on size or layout change.
 */
class KeyGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onKeyPressed(key: Key)
        fun onKeyReleased(key: Key)
    }

    var listener: Listener? = null

    var keyboardLayout: KeyboardLayout = KeyboardLayout.qwerty()
        set(value) {
            if (field === value) return
            field = value
            rebuildGeometry()
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val rowHeight = 54f * density
    private val gap = 4f * density
    private val corner = 8f * density

    private val rects = ArrayList<RectF>(48)
    private val keys = ArrayList<Key>(48)
    private var pressedIndex = -1

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_KEY }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR_KEY_PRESSED }
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
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                setPressedKey(indexAt(event.x, event.y))
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                setPressedKey(indexAt(event.x, event.y))
                return true
            }

            MotionEvent.ACTION_UP -> {
                val index = indexAt(event.x, event.y)
                if (index >= 0 && index < keys.size) listener?.onKeyReleased(keys[index])
                setPressedKey(-1)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                setPressedKey(-1)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun setPressedKey(index: Int) {
        if (index == pressedIndex) return
        val previous = pressedIndex
        pressedIndex = index
        if (previous >= 0) invalidateKey(previous)
        if (index >= 0) {
            invalidateKey(index)
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            listener?.onKeyPressed(keys[index])
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
        const val COLOR_KEY = 0xFFF4F4F5.toInt()
        const val COLOR_KEY_PRESSED = 0xFFE8641A.toInt()
        const val COLOR_LABEL = 0xFF1A1A1A.toInt()
    }
}
