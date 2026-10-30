package dev.abode.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import compose.icons.TablerIcons
import compose.icons.tablericons.LayoutGrid
import dev.abode.core.common.theme.KeyboardTheme

/**
 * The row of predictions above the keys.
 *
 * It was one `TextView` showing one suggestion, which is why the second and third
 * were unreachable: they existed in the engine and nowhere on the screen. A
 * three-slot strip with real gaps between the slots gives each one a target big
 * enough to hit and makes the order legible — the first is what you probably
 * meant, the next two are what you might have.
 *
 * Drawn rather than composed, because everything else in this keyboard is a
 * `Canvas` and a strip that measured itself differently from the grid above it
 * would be the one row that never lines up.
 */
class SuggestionStrip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        /** A slot was tapped. [index] is 0, 1 or 2. */
        fun onSuggestion(index: Int)

        /** The toolbar button at the left of the strip was tapped. */
        fun onToolbar() {}
    }

    /** Shows the toolbar button at the left edge; the slots move right to make room. */
    var toolbarButton = false
        set(value) {
            if (field == value) return
            field = value
            buildSlots()
            invalidate()
        }
    private var toolbarPressed = false
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val toolbarRect = RectF()
    private var toolbarRendered: VectorPaths.Rendered? = null
    private val leftInset: Float get() = if (toolbarButton) 44f * density else 0f

    var listener: Listener? = null

    /** Up to three predictions, best first. */
    var suggestions: List<String> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            buildSlots()
            invalidate()
        }

    /** A line of text in place of suggestions — "Undo", a hint, a status. */
    var status: String = ""
        set(value) {
            if (field == value) return
            field = value
            buildSlots()
            invalidate()
        }

    private val density = resources.displayMetrics.density

    /** Space between slots. Generous on purpose: two words touching read as one. */
    private var gap = 10f * density

    private var sidePadding = 12f * density
    private var slotHeight = 34f * density
    private var corner = 10f * density

    private val slotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 14f * density
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
    }

    private val slots = ArrayList<RectF>(3)
    private var pressed = -1

    fun applyTheme(theme: KeyboardTheme) {
        slotPaint.color = theme.keyBackground
        pressedPaint.color = theme.keyPressed
        accentPaint.color = theme.accent
        // Dividers are the label colour at a low opacity, so they read on any theme.
        dividerPaint.color = (theme.keyLabel and 0x00FFFFFF) or (0x66 shl 24)
        labelPaint.color = theme.keyLabel
        iconPaint.color = theme.keyLabel
        toolbarRendered = null
        gap = theme.keyGapDp * density
        slotHeight = (theme.rowHeightDp * 0.7f) * density
        corner = theme.cornerRadiusDp * density
        labelPaint.typeface = ThemeTypeface.forTheme(theme.fontPath)
            ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
        buildSlots()
        invalidate()
    }

    private fun labels(): List<String> = when {
        suggestions.isNotEmpty() -> suggestions
        status.isNotEmpty() -> listOf(status)
        else -> emptyList()
    }

    private fun buildSlots() {
        slots.clear()
        val items = labels()
        if (items.isEmpty() || width == 0) return
        val count = items.size.coerceAtMost(3)
        val available = width - sidePadding * 2 - leftInset - gap * (count - 1)
        if (available <= 0f) return

        // Slots are equal width rather than weighted by their text: a strip whose
        // targets move with every prediction is a strip you miss.
        val slotWidth = available / count
        val top = (height - slotHeight) / 2f
        for (i in 0 until count) {
            val left = sidePadding + leftInset + i * (slotWidth + gap)
            slots.add(RectF(left, top, left + slotWidth, top + slotHeight))
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desired = (slotHeight + gap * 2).toInt()
        setMeasuredDimension(width, resolveSize(desired, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildSlots()
    }

    override fun onDraw(canvas: Canvas) {
        if (slots.isEmpty()) buildSlots()
        val items = labels()
        // The first suggestion is the one the keyboard expects you to want, so it
        // takes the accent. That is the cue that tapping it is the quick choice.
        if (toolbarButton) drawToolbarButton(canvas)
        val highlightFirst = suggestions.isNotEmpty()
        for (i in slots.indices) {
            val r = slots[i]
            val fill = when {
                i == pressed -> pressedPaint
                i == 0 && highlightFirst -> accentPaint
                else -> slotPaint
            }
            canvas.drawRoundRect(r, corner, corner, fill)
            if (i > 0) {
                val x = (slots[i - 1].right + r.left) / 2f
                val inset = 8f * density
                canvas.drawLine(x, r.top + inset, x, r.bottom - inset, dividerPaint)
            }
            val label = items.getOrNull(i) ?: continue
            val baseline = r.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
            // Ellipsised by hand: a suggestion wider than its slot would run
            // into the next one, and a strip of overlapping words is unreadable.
            canvas.drawText(ellipsise(label, r.width() - 16f * density), r.centerX(), baseline, labelPaint)
        }
    }

    private fun drawToolbarButton(canvas: Canvas) {
        val size = 20f * density
        val cx = leftInset / 2f
        val cy = height / 2f
        toolbarRect.set(cx - size, cy - size, cx + size, cy + size)
        val fill = if (toolbarPressed) pressedPaint else slotPaint
        canvas.drawRoundRect(toolbarRect, corner, corner, fill)
        val rendered = toolbarRendered ?: VectorPaths.render(TablerIcons.LayoutGrid, size * 1.1f)
            .also { toolbarRendered = it }
        canvas.save()
        canvas.translate(cx - size * 0.55f, cy - size * 0.55f)
        iconPaint.style = if (rendered.filled) Paint.Style.FILL else Paint.Style.STROKE
        iconPaint.strokeWidth = rendered.strokeWidth
        canvas.drawPath(rendered.path, iconPaint)
        canvas.restore()
    }

    private fun ellipsise(text: String, maxWidth: Float): String {
        if (labelPaint.measureText(text) <= maxWidth) return text
        var cut = text.length
        while (cut > 1 && labelPaint.measureText(text.substring(0, cut) + "…") > maxWidth) cut--
        return text.substring(0, cut) + "…"
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (toolbarButton && event.x < leftInset) {
                    toolbarPressed = true
                    invalidate()
                    return true
                }
                pressed = slotAt(event.x, event.y)
                if (pressed < 0) return false
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val now = slotAt(event.x, event.y)
                if (now != pressed) {
                    pressed = now
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (toolbarPressed) {
                    toolbarPressed = false
                    invalidate()
                    listener?.onToolbar()
                    performClick()
                    return true
                }
                val hit = slotAt(event.x, event.y)
                pressed = -1
                invalidate()
                if (hit >= 0) {
                    // Status text is not a suggestion; tapping it means "yes, the
                    // thing you are offering" — undo, usually — which is slot 0.
                    listener?.onSuggestion(if (suggestions.isEmpty()) 0 else hit)
                    performClick()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pressed = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun slotAt(x: Float, y: Float): Int {
        for (i in slots.indices) if (slots[i].contains(x, y)) return i
        return -1
    }
}
