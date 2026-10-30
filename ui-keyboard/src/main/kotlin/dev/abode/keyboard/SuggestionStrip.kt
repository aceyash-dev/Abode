package dev.abode.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
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

        /** The Chyme button, beside the toolbar button, was tapped. */
        fun onChyme() {}
    }

    /** Shows the toolbar button at the left edge; the slots move right to make room. */
    var toolbarButton = false
        set(value) {
            if (field == value) return
            field = value
            buildSlots()
            invalidate()
        }

    /** Shows the Chyme button beside the toolbar button; slots move right again. */
    var chymeButton = false
        set(value) {
            if (field == value) return
            field = value
            buildSlots()
            invalidate()
        }

    private var pressedButton = -1
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var toolbarRendered: VectorPaths.Rendered? = null

    private val buttonCount: Int
        get() = (if (toolbarButton) 1 else 0) + (if (chymeButton) 1 else 0)

    private val leftInset: Float get() = BUTTON_WIDTH * density * buttonCount

    /** Which button sits at [index]: the toolbar is laid out first, Chyme second. */
    private fun kindAt(index: Int): Int =
        if (index == 0 && toolbarButton) BUTTON_TOOLBAR else BUTTON_CHYME

    private fun buttonRectAt(index: Int): RectF {
        val size = BUTTON_WIDTH * density
        val left = index * size
        val top = (height - slotHeight) / 2f
        return RectF(left, top, left + size, top + slotHeight)
    }

    private fun buttonAt(x: Float): Int {
        if (x < 0f) return -1
        val index = (x / (BUTTON_WIDTH * density)).toInt()
        return if (index in 0 until buttonCount) index else -1
    }

    var listener: Listener? = null

    /** Up to three predictions, best first. */
    var suggestions: List<String> = emptyList()
        set(value) {
            if (field == value) return
            val before = labels()
            field = value
            buildSlots()
            startSlotAnimations(before, labels())
            invalidate()
        }

    /** A line of text in place of suggestions — "Undo", a hint, a status. */
    var status: String = ""
        set(value) {
            if (field == value) return
            val before = labels()
            field = value
            buildSlots()
            startSlotAnimations(before, labels())
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

    private val slots = ArrayList<RectF>(MAX_SLOTS)
    private var pressed = -1

    /**
     * When each slot began its entrance, or -1 for a slot that has settled.
     *
     * Gboard fills its strip one slot after another instead of swapping the whole
     * row at once: the word you most likely want lands first and the alternatives
     * follow a beat behind. The stagger is also what keeps a fast typist from
     * seeing the row blink on every keystroke.
     */
    private val slotAnimStart = LongArray(MAX_SLOTS) { -1L }

    /** Starts an entrance for every slot whose text actually changed. */
    private fun startSlotAnimations(before: List<String>, after: List<String>) {
        val now = SystemClock.uptimeMillis()
        for (i in 0 until MAX_SLOTS) {
            val old = before.getOrNull(i)
            val new = after.getOrNull(i)
            slotAnimStart[i] = if (new != null && new != old) now + i * STAGGER_MS else -1L
        }
    }

    /** 0 while a slot is still waiting its turn, 1 once it has settled. */
    private fun slotProgress(i: Int): Float {
        val start = slotAnimStart.getOrElse(i) { -1L }
        if (start < 0L) return 1f
        val elapsed = SystemClock.uptimeMillis() - start
        if (elapsed <= 0L) return 0f
        val t = (elapsed.toFloat() / SLOT_FADE_MS).coerceIn(0f, 1f)
        if (t >= 1f) slotAnimStart[i] = -1L
        return t
    }

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
        labelPaint.typeface = ThemeTypeface.forTheme(theme.fontPath, theme.fontFamily)
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
        val count = items.size.coerceAtMost(MAX_SLOTS)
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
        if (buttonCount > 0) drawButtons(canvas)
        val highlightFirst = suggestions.isNotEmpty()
        var animating = false
        for (i in slots.indices) {
            val r = slots[i]
            val progress = slotProgress(i)
            if (progress < 1f) animating = true
            if (progress <= 0f) continue
            // Fade up a few dp as it arrives, so a new word reads as landing
            // rather than the row blinking.
            val rise = (1f - progress) * 6f * density
            val alpha = (255 * progress).toInt().coerceIn(0, 255)
            val fill = when {
                i == pressed -> pressedPaint
                i == 0 && highlightFirst -> accentPaint
                else -> slotPaint
            }
            canvas.save()
            canvas.translate(0f, rise)
            val fillAlpha = fill.alpha
            fill.alpha = alpha * fillAlpha / 255
            canvas.drawRoundRect(r, corner, corner, fill)
            fill.alpha = fillAlpha
            if (i > 0) {
                val x = (slots[i - 1].right + r.left) / 2f
                val inset = 8f * density
                val dividerAlpha = dividerPaint.alpha
                dividerPaint.alpha = alpha * dividerAlpha / 255
                canvas.drawLine(x, r.top + inset, x, r.bottom - inset, dividerPaint)
                dividerPaint.alpha = dividerAlpha
            }
            val label = items.getOrNull(i)
            if (label != null) {
                val baseline = r.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
                val labelAlpha = labelPaint.alpha
                labelPaint.alpha = alpha * labelAlpha / 255
                // Ellipsised by hand: a suggestion wider than its slot would run
                // into the next one, and a strip of overlapping words is unreadable.
                canvas.drawText(ellipsise(label, r.width() - 16f * density), r.centerX(), baseline, labelPaint)
                labelPaint.alpha = labelAlpha
            }
            canvas.restore()
        }
        if (animating) postInvalidateOnAnimation()
    }

    private fun drawButtons(canvas: Canvas) {
        for (i in 0 until buttonCount) {
            val r = buttonRectAt(i)
            val fill = if (pressedButton == i) pressedPaint else slotPaint
            canvas.drawRoundRect(r, corner, corner, fill)
            if (kindAt(i) == BUTTON_TOOLBAR) drawGridIcon(canvas, r) else drawSparkle(canvas, r)
        }
    }

    private fun drawGridIcon(canvas: Canvas, r: RectF) {
        val size = 20f * density
        val rendered = toolbarRendered ?: VectorPaths.render(TablerIcons.LayoutGrid, size * 1.1f)
            .also { toolbarRendered = it }
        canvas.save()
        canvas.translate(r.centerX() - size * 0.55f, r.centerY() - size * 0.55f)
        iconPaint.style = if (rendered.filled) Paint.Style.FILL else Paint.Style.STROKE
        iconPaint.strokeWidth = rendered.strokeWidth
        canvas.drawPath(rendered.path, iconPaint)
        canvas.restore()
    }

    /**
     * A four-point star for the Chyme button.
     *
     * Drawn rather than taken from the icon set: it is four curves, and a
     * vector icon here would be the only dependency in the strip's draw path
     * that can be renamed out from under a build.
     */
    private fun drawSparkle(canvas: Canvas, r: RectF) {
        val s = minOf(r.height() * 0.26f, r.width() * 0.30f)
        if (s <= 0f) return
        val cx = r.centerX()
        val cy = r.centerY()
        val waist = s * 0.22f
        val path = Path().apply {
            moveTo(cx, cy - s)
            quadTo(cx + waist, cy - waist, cx + s, cy)
            quadTo(cx + waist, cy + waist, cx, cy + s)
            quadTo(cx - waist, cy + waist, cx - s, cy)
            quadTo(cx - waist, cy - waist, cx, cy - s)
            close()
        }
        iconPaint.style = Paint.Style.FILL
        canvas.drawPath(path, iconPaint)
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
                val button = buttonAt(event.x)
                if (button >= 0) {
                    pressedButton = button
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
                if (pressedButton >= 0) {
                    val button = pressedButton
                    pressedButton = -1
                    invalidate()
                    if (kindAt(button) == BUTTON_TOOLBAR) listener?.onToolbar() else listener?.onChyme()
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
                pressedButton = -1
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

    private companion object {
        const val MAX_SLOTS = 3

        /** One button's width, in dp. Two buttons inset the slots by twice this. */
        const val BUTTON_WIDTH = 44f
        const val BUTTON_TOOLBAR = 0
        const val BUTTON_CHYME = 1

        /** How long one slot waits behind the slot before it. */
        const val STAGGER_MS = 45L

        /** How long a single slot takes to fade and rise into place. */
        const val SLOT_FADE_MS = 130f
    }
}
