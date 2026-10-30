package dev.abode.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.ui.graphics.vector.ImageVector
import compose.icons.TablerIcons
import compose.icons.tablericons.Backspace
import compose.icons.tablericons.Clipboard
import compose.icons.tablericons.CornerDownLeft
import compose.icons.tablericons.Microphone
import dev.abode.core.common.theme.KeyShape
import dev.abode.core.common.theme.KeyboardTheme
import java.io.File

/**
 * Caches typefaces loaded from user-imported font files. Parsing a font is
 * expensive; doing it per keystroke or per show would be visible.
 */
object ThemeTypeface {
    private val cache = HashMap<String, Typeface>()

    /**
     * A file the user imported wins; otherwise one of the three built-in faces.
     * Serif and monospace are the two the platform always has, so they cost
     * nothing to offer and need no font shipped in the APK.
     */
    fun forTheme(path: String?, family: String = KeyboardTheme.FONT_SANS): Typeface? {
        if (!path.isNullOrBlank()) {
            cache[path]?.let { return it }
            val file = File(path)
            if (file.exists()) {
                return runCatching { Typeface.createFromFile(file) }
                    .onSuccess { cache[path] = it }
                    .getOrNull()
            }
        }
        return when (family) {
            KeyboardTheme.FONT_DEVICE -> Typeface.DEFAULT
            KeyboardTheme.FONT_SERIF -> Typeface.SERIF
            KeyboardTheme.FONT_MONO -> Typeface.MONOSPACE
            else -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
    }

    fun clear() = cache.clear()
}

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
 * Three gestures share this one touch stream, in this order of precedence:
 *  1. Long press on a key with alternates opens the popup strip.
 *  2. A swipe that leaves the starting key becomes a glide; lifting decodes it.
 *  3. Anything else is a key press, committed on touch-up.
 *
 * Every visual property comes from [theme].
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

        /** Fires while a key is held, after the first repeat delay. */
        fun onKeyRepeat(key: Key)

        /** Fires on lift after a swipe across the keys. */
        fun onGlide(keys: List<Key>)

        /** An up or down swipe on a paged grid (the emoji page): -1 back, +1 forward. */
        fun onPage(step: Int) {}
    }

    /** When true, a vertical swipe turns pages instead of gliding. */
    var pagingEnabled = false

    /**
     * When true the middle rows scroll vertically inside the view.
     *
     * The emoji grid is the reason: a category holds hundreds of glyphs and paging
     * through them four rows at a time is not browsing. The view keeps a fixed
     * height and moves the rows behind it, with [pinnedTopRows] above and
     * [pinnedRows] below held still so the tabs and the way out never move.
     */
    var scrollable = false
        set(value) {
            if (field == value) return
            field = value
            scrollOffset = 0f
            rebuildGeometry()
            requestLayout()
            invalidate()
        }

    /** Rows at the top that stay put — the emoji search row. */
    var pinnedTopRows = 0

    /** Rows at the bottom that stay put — the category tabs and the bottom row. */
    var pinnedRows = 0

    /** Content rows visible at once; the view is measured to this many. */
    var visibleContentRows = 4

    private var scrollOffset = 0f
    private var maxScroll = 0f
    private var contentTop = 0f
    private var contentBottom = 0f
    private var headerKeyEnd = 0
    private var pinnedStartIndex = 0
    private var scrolling = false
    private var lastScrollY = 0f
    private var scrollbarAlpha = 0f
    private val scroller = android.widget.OverScroller(context)
    private var velocityTracker: android.view.VelocityTracker? = null
    private val scrollbarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val scrollbarRect = RectF()
    private var pageSwiped = false
    private val pageSlop get() = 24f * resources.displayMetrics.density
    private val fitPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Shrinks [base] so [text] fits [maxWidth]; kaomoji are long and cells are not. */
    private fun fitted(base: Paint, text: String, maxWidth: Float): Paint {
        val w = base.measureText(text)
        if (w <= maxWidth || w <= 0f) return base
        fitPaint.set(base)
        fitPaint.textSize = base.textSize * maxWidth / w
        return fitPaint
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

    var theme: KeyboardTheme = KeyboardTheme()
        set(value) {
            if (field == value) return
            field = value
            applyTheme()
            requestLayout()
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private var rowHeight = 52f * density
    private var gap = 6f * density
    private var corner = 12f * density
    private var keyShape: KeyShape = KeyShape.HEXAGON
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val rects = ArrayList<RectF>(64)
    private val keyPaths = ArrayList<Path>(64)
    private val iconCache = HashMap<String, VectorPaths.Rendered>()
    private val keys = ArrayList<Key>(64)
    private var pressedIndex = -1

    // Long-press popup state
    private val handler = Handler(Looper.getMainLooper())
    private val popupRects = ArrayList<RectF>(6)
    private var popupKeys: List<Key> = emptyList()
    private var popupSelected = -1
    private var downX = 0f
    private var downY = 0f
    private var longPressFired = false

    // Glide state
    private var gliding = false
    private val glideTrail = Path()
    private val glideKeys = ArrayList<Key>(12)
    private val glidePoints = ArrayList<FloatArray>(64)
    private var lastGlideX = 0f
    private var lastGlideY = 0f

    // Repeat state (delete held down)
    private var repeatKey: Key? = null

    private val longPress = Runnable {
        val index = pressedIndex
        if (index < 0 || index >= keys.size) return@Runnable
        val key = keys[index]
        if (!key.hasAlternates) return@Runnable
        longPressFired = true
        cancelRepeat()
        openPopup(index, key)
    }

    private val repeat = object : Runnable {
        override fun run() {
            val key = repeatKey ?: return
            listener?.onKeyRepeat(key)
            handler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val actionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shiftOnPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val popupPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val popupSelectedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val trailGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    /** The dot that says a key has a long press. */
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        textSize = 11f * resources.displayMetrics.scaledDensity
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 15f * resources.displayMetrics.scaledDensity
    }
    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 26f * resources.displayMetrics.scaledDensity
    }

    /** Lowercase letters, a shade smaller than capitals. Cloned from [labelPaint]. */
    private val lowerPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
        isHapticFeedbackEnabled = true
        applyTheme()
    }

    fun applyTheme() {
        keyPaint.color = theme.keyBackground
        pressedPaint.color = theme.keyPressed
        activePaint.color = theme.accent
        shiftOnPaint.color = theme.keyLabel
        // Utility keys sit a step darker than letters, as on the system keyboards.
        actionPaint.color = mix(theme.keyBackground, theme.background, 0.6f)
        popupPaint.color = theme.popupBackground
        popupSelectedPaint.color = theme.keyPressed
        labelPaint.color = theme.keyLabel
        secondaryPaint.color = (theme.keyLabel and 0x00FFFFFF) or (0xAA shl 24)
        hintPaint.color = (theme.keyLabel and 0x00FFFFFF) or (0x55 shl 24)
        scrollbarPaint.color = (theme.keyLabel and 0x00FFFFFF) or (0xFF shl 24)
        iconPaint.color = theme.keyLabel
        iconCache.clear()
        labelPaint.typeface = ThemeTypeface.forTheme(theme.fontPath, theme.fontFamily)
            ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
        // Cloned rather than configured separately, so an imported font and the
        // theme colour carry over to the smaller size.
        lowerPaint.set(labelPaint)
        lowerPaint.textSize = labelPaint.textSize * LOWER_LABEL_SCALE
        trailPaint.color = theme.accent
        trailPaint.strokeWidth = 3.5f * density
        trailGlowPaint.color = (theme.accent and 0x00FFFFFF) or (0x40 shl 24)
        trailGlowPaint.strokeWidth = 10f * density

        // Geometry is only rebuilt when the shape actually moved. A colour-only
        // change — which is what dragging a picker produces — used to rebuild
        // every key rectangle on every frame of the drag.
        val previousRowHeight = rowHeight
        val previousGap = gap
        val previousShape = keyShape
        rowHeight = theme.rowHeightDp * density
        gap = theme.keyGapDp * density
        corner = theme.cornerRadiusDp * density

        keyShape = theme.keyShape
        if (rowHeight != previousRowHeight || gap != previousGap || keyShape != previousShape) {
            rebuildGeometry()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val rows = if (scrollable) {
            (visibleContentRows + pinnedTopRows + pinnedRows).coerceAtLeast(1)
        } else {
            keyboardLayout.rowCount.coerceAtLeast(1)
        }
        val desired = (rows * rowHeight + (rows + 1) * gap).toInt()
        setMeasuredDimension(width, resolveSize(desired, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildGeometry()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(longPress)
        cancelRepeat()
        super.onDetachedFromWindow()
    }

    private fun rebuildGeometry() {
        rects.clear()
        keyPaths.clear()
        keys.clear()
        headerKeyEnd = 0
        pinnedStartIndex = 0
        maxScroll = 0f
        contentTop = 0f
        contentBottom = height.toFloat()
        val w = width.toFloat()
        val h = height.toFloat()
        val rows = keyboardLayout.rows
        if (w <= 0f || h <= 0f || rows.isEmpty()) return

        if (!scrollable) {
            layoutRows(w, rows, rowHeightFor(rows.size, h), gap, 0)
            return
        }

        val top = pinnedTopRows.coerceIn(0, rows.size)
        val bottom = pinnedRows.coerceIn(0, rows.size - top)
        val contentRows = rows.size - top - bottom
        val visible = visibleContentRows.coerceAtLeast(1)
        val rowHeightPx = rowHeightFor(top + bottom + visible, h)

        contentTop = if (top > 0) top * (rowHeightPx + gap) + gap else 0f
        val footerHeight = if (bottom > 0) bottom * (rowHeightPx + gap) + gap else 0f
        contentBottom = h - footerHeight

        if (top > 0) {
            layoutRows(w, rows.subList(0, top), rowHeightPx, gap, 0)
            headerKeyEnd = rects.size
        }
        if (contentRows > 0) {
            // Laid out from contentTop and then translated by the scroll offset at
            // draw time, so the rectangles and the key paths never move: scrolling
            // costs a canvas translate, not a rebuild of every shape.
            layoutRows(w, rows.subList(top, top + contentRows), rowHeightPx, contentTop, top)
        }
        pinnedStartIndex = rects.size
        if (bottom > 0) {
            layoutRows(w, rows.subList(top + contentRows, rows.size), rowHeightPx, contentBottom + gap, top + contentRows)
        }

        val contentHeight = contentRows * (rowHeightPx + gap) + gap
        val viewport = (contentBottom - contentTop).coerceAtLeast(1f)
        maxScroll = (contentHeight - viewport).coerceAtLeast(0f)
        scrollOffset = scrollOffset.coerceIn(0f, maxScroll)
    }

    private fun rowHeightFor(rowCount: Int, h: Float): Float =
        (h - gap * (rowCount + 1)) / rowCount

    /** Lays [rows] out from [startTop] in one column, starting at [firstRowIndex]. */
    private fun layoutRows(
        w: Float,
        rows: List<List<Key>>,
        rowHeightPx: Float,
        startTop: Float,
        firstRowIndex: Int,
    ) {
        var top = startTop
        var rowIndex = firstRowIndex
        for (row in rows) {
            // A honeycomb only reads as one if alternate rows step in by half a
            // key; without it the hexagons line up in columns and look like
            // rounded rectangles with clipped corners.
            val stagger = if (keyShape == KeyShape.HEXAGON && rowIndex % 2 == 1) 0.5f else 0f
            var totalWeight = 0f
            for (key in row) totalWeight += key.widthWeight
            if (totalWeight <= 0f) continue

            val unit = (w - gap * (row.size + 1)) / row.size
            val inset = unit * stagger
            val available = w - gap * (row.size + 1)
            var left = gap + inset

            for (key in row) {
                val keyWidth = available * (key.widthWeight / totalWeight)
                val rect = RectF(left, top, left + keyWidth, top + rowHeightPx)
                rects.add(rect)
                keyPaths.add(buildKeyPath(rect))
                keys.add(key)
                left += keyWidth + gap
            }
            top += rowHeightPx + gap
            rowIndex++
        }
    }

    /** Tabler glyphs for the keys that are actions rather than letters. */
    private fun iconFor(key: Key): ImageVector? = when (key.kind) {
        KeyKind.DELETE -> TablerIcons.Backspace
        KeyKind.ENTER -> TablerIcons.CornerDownLeft
        KeyKind.GLOBE -> null
        KeyKind.VOICE -> TablerIcons.Microphone
        KeyKind.CLIPBOARD -> TablerIcons.Clipboard
        // Drawn by hand: the vector Search glyph is a circle plus a stroke, and
        // the converter drops its relative arc, so it rendered as a stray tick.
        KeyKind.EMOJI_SEARCH -> null
        // The "ABC" that returns from emoji stays a word: it names the page it goes to.
        KeyKind.EMOJI -> null
        else -> null
    }

    /** The fill for a key, or null for keys drawn as a bare icon on the tray. */
    private fun fillFor(index: Int, key: Key): Paint? = when {
        index == pressedIndex -> pressedPaint
        key.kind == KeyKind.GLOBE || key.kind == KeyKind.VOICE -> null
        key.kind == KeyKind.EMOJI && iconFor(key) != null -> null
        key.kind == KeyKind.ENTER -> activePaint
        key.kind == KeyKind.SHIFT && key.active -> shiftOnPaint
        key.active -> activePaint
        key.kind == KeyKind.CHARACTER || key.kind == KeyKind.SPACE -> keyPaint
        else -> actionPaint
    }

    /**
     * Shift is a chevron, not a glyph: a thick rounded arrowhead that reads at a
     * glance. Caps lock adds a bar underneath, the way the system keyboards mark it.
     * When shift is on, the key fills light and the chevron takes the tray colour.
     */
    private fun drawShift(canvas: Canvas, rect: RectF, caps: Boolean, active: Boolean) {
        val s = minOf(rect.height() * 0.42f, rect.width() * 0.5f)
        if (s <= 0f) return

        val cx = rect.centerX()
        val cy = rect.centerY() - if (caps) s * 0.12f else 0f
        chevronPaint.color = if (active) theme.background else theme.keyLabel
        chevronPaint.strokeWidth = s * 0.16f

        val chevron = Path().apply {
            moveTo(cx - s * 0.5f, cy + s * 0.2f)
            lineTo(cx, cy - s * 0.3f)
            lineTo(cx + s * 0.5f, cy + s * 0.2f)
        }
        canvas.drawPath(chevron, chevronPaint)

        if (caps) {
            val barY = cy + s * 0.55f
            canvas.drawLine(cx - s * 0.5f, barY, cx + s * 0.5f, barY, chevronPaint)
        }
    }

    /**
     * A thin bar on the right edge of the scrolling region.
     *
     * Fades out when the finger stops: a permanent scrollbar on a keyboard is
     * furniture, and this one only has to answer "how much is left".
     */
    private fun drawScrollbar(canvas: Canvas) {
        if (maxScroll <= 0f || scrollbarAlpha <= 0f) return
        val trackTop = contentTop + 2f * density
        val trackBottom = contentBottom - 2f * density
        val trackHeight = trackBottom - trackTop
        if (trackHeight <= 0f) return
        val viewport = (contentBottom - contentTop).coerceAtLeast(1f)
        val thumbHeight = (trackHeight * (viewport / (viewport + maxScroll)))
            .coerceAtLeast(20f * density)
        val travel = (trackHeight - thumbHeight).coerceAtLeast(0f)
        val fraction = if (maxScroll > 0f) (scrollOffset / maxScroll).coerceIn(0f, 1f) else 0f
        val thumbTop = trackTop + travel * fraction
        val x = width - 3f * density
        scrollbarRect.set(x - 1.5f * density, thumbTop, x + 1.5f * density, thumbTop + thumbHeight)
        scrollbarPaint.alpha = (scrollbarAlpha * 160f).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(scrollbarRect, 2f * density, 2f * density, scrollbarPaint)
    }

    /** A magnifier drawn from a circle and a stroke, so it always reads as one. */
    private fun drawMagnifier(canvas: Canvas, rect: RectF) {
        val s = minOf(rect.height() * 0.30f, rect.width() * 0.34f)
        if (s <= 0f) return
        val cx = rect.centerX() - s * 0.12f
        val cy = rect.centerY() - s * 0.12f
        iconPaint.style = Paint.Style.STROKE
        iconPaint.strokeWidth = s * 0.22f
        canvas.drawCircle(cx, cy, s * 0.6f, iconPaint)
        canvas.drawLine(cx + s * 0.45f, cy + s * 0.45f, cx + s * 0.95f, cy + s * 0.95f, iconPaint)
    }

    /** A round face with two eyes and an open smile, drawn in the label colour. */
    private fun drawSmiley(canvas: Canvas, r: RectF) {
        val rad = minOf(r.height(), r.width()) * 0.32f
        val cx = r.centerX()
        val cy = r.centerY()
        smileyPaint.color = labelPaint.color
        smileyPaint.style = Paint.Style.STROKE
        smileyPaint.strokeWidth = rad * 0.13f
        canvas.drawCircle(cx, cy, rad * 0.9f, smileyPaint)
        smileyPaint.style = Paint.Style.FILL
        canvas.drawCircle(cx - rad * 0.36f, cy - rad * 0.25f, rad * 0.13f, smileyPaint)
        canvas.drawCircle(cx + rad * 0.36f, cy - rad * 0.25f, rad * 0.13f, smileyPaint)
        val top = cy + rad * 0.12f
        val w = rad * 0.5f
        smileyMouth.reset()
        smileyMouth.addArc(RectF(cx - w, top - w, cx + w, top + w), 0f, 180f)
        smileyMouth.close()
        canvas.drawPath(smileyMouth, smileyPaint)
    }

    private val smileyPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val micPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val micRect = RectF()

    /** The microphone from the design: a capsule, four bars, a cradle and a stand. */
    private fun drawMic(canvas: Canvas, r: RectF) {
        val scale = minOf(r.width(), r.height()) * 0.78f / 420f
        val ox = r.centerX() - 360f * scale
        val oy = r.centerY() - 360f * scale
        fun x(v: Float) = ox + v * scale
        fun y(v: Float) = oy + v * scale
        micPaint.color = labelPaint.color
        micPaint.style = Paint.Style.STROKE
        micPaint.strokeWidth = 16f * scale
        micPaint.strokeCap = Paint.Cap.ROUND
        micRect.set(x(275f), y(150f), x(445f), y(430f))
        canvas.drawRoundRect(micRect, 85f * scale, 85f * scale, micPaint)
        for (line in listOf(230f, 270f, 310f, 350f)) {
            canvas.drawLine(x(327f), y(line), x(393f), y(line), micPaint)
        }
        micRect.set(x(247f), y(230f), x(473f), y(456f))
        canvas.drawArc(micRect, 0f, 180f, false, micPaint)
        canvas.drawLine(x(247f), y(343f), x(247f), y(302f), micPaint)
        canvas.drawLine(x(473f), y(343f), x(473f), y(302f), micPaint)
        canvas.drawLine(x(360f), y(456f), x(360f), y(555f), micPaint)
        canvas.drawLine(x(290f), y(562f), x(430f), y(562f), micPaint)
        micPaint.style = Paint.Style.FILL
        canvas.drawCircle(x(247f), y(302f), 9f * scale, micPaint)
        canvas.drawCircle(x(473f), y(302f), 9f * scale, micPaint)
    }
    private val smileyMouth = android.graphics.Path()

    private fun drawIcon(canvas: Canvas, rect: RectF, vector: ImageVector, kind: KeyKind) {
        val size = minOf(rect.height() * 0.44f, rect.width() * 0.5f)
        if (size <= 0f) return

        val key = "${kind.name}-${size.toInt()}"
        val rendered = iconCache.getOrPut(key) { VectorPaths.render(vector, size) }

        canvas.save()
        canvas.translate(rect.centerX() - size / 2f, rect.centerY() - size / 2f)
        iconPaint.strokeWidth = rendered.strokeWidth
        canvas.drawPath(rendered.path, iconPaint)
        canvas.restore()
    }

    override fun onDraw(canvas: Canvas) {
        if (pinnedStartIndex > 0) {
            // Pinned rows are drawn in view space; the scrolling rows are drawn
            // through a translate so their rectangles never have to be rebuilt.
            for (i in 0 until headerKeyEnd) drawKey(canvas, i)
            canvas.save()
            canvas.clipRect(0f, contentTop, width.toFloat(), contentBottom)
            canvas.translate(0f, -scrollOffset)
            for (i in headerKeyEnd until pinnedStartIndex) drawKey(canvas, i)
            canvas.restore()
            for (i in pinnedStartIndex until rects.size) drawKey(canvas, i)
            drawScrollbar(canvas)
        } else {
            for (i in 0 until rects.size) drawKey(canvas, i)
        }

        if (scrollbarAlpha > 0f && !scrolling && scroller.isFinished) {
            scrollbarAlpha = (scrollbarAlpha - SCROLLBAR_FADE_STEP).coerceAtLeast(0f)
            if (scrollbarAlpha > 0f) postInvalidateOnAnimation()
        }

        if (gliding) {
            // Two strokes: a wide soft one under a thin bright one, so the trail
            // reads over both a light and a dark theme without a second colour.
            canvas.drawPath(glideTrail, trailGlowPaint)
            canvas.drawPath(glideTrail, trailPaint)
        }
        if (popupKeys.isNotEmpty()) drawPopup(canvas)
    }

    private fun drawKey(canvas: Canvas, i: Int) {
            if (keys[i].kind == KeyKind.SPACER) return
            val r = rects[i]
            val key = keys[i]
            val fill = fillFor(i, key)
            if (fill != null) canvas.drawPath(keyPaths[i], fill)
            if (key.kind == KeyKind.SHIFT) {
                drawShift(canvas, r, caps = key.label == KeyboardLayout.CAPS_LOCK_LABEL, active = key.active)
                return
            }

            if (key.kind == KeyKind.EMOJI_SEARCH) {
                drawMagnifier(canvas, r)
                return
            }

            // Return is drawn as its arrow, unless the field asked for a named action
            // (send, go, search): then the word is the thing the person needs to see.
            if (keys[i].kind == KeyKind.VOICE) {
                drawMic(canvas, r)
                return
            }
            val namedEnter = keys[i].kind == KeyKind.ENTER && keys[i].label != "return"
            val icon = if (namedEnter) null else iconFor(keys[i])
            if (icon != null) {
                drawIcon(canvas, r, icon, keys[i].kind)
                return
            }

            val label = keys[i].label
            if (label.isEmpty()) return

            // The emoji key is drawn as the smiley from the design, not as a glyph.
            if (keys[i].kind == KeyKind.EMOJI && label == "\u263A") {
                drawSmiley(canvas, r)
                return
            }

            // Emoji are drawn larger than text so the emoji page reads at a glance.
            // The test is the code point, not the label's length: a letter is never
            // an emoji, and the glyph keys that carry one are named kinds.
            val glyph = keys[i].kind in GLYPH_KINDS && label.codePointAt(0) > 0x2000
            val base = when {
                glyph -> emojiPaint
                isLowercaseLetter(label) -> lowerPaint
                else -> labelPaint
            }
            val paint = fitted(base, label, r.width() - 6f * resources.displayMetrics.density)
            val baseline = r.centerY() - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(label, r.centerX(), baseline, paint)

            keys[i].secondary?.let { small ->
                val x = r.right - 7f * resources.displayMetrics.density
                val y = r.top + secondaryPaint.textSize + 3f * resources.displayMetrics.density
                canvas.drawText(small, x, y, secondaryPaint)
            }

            if (keys[i].hasAlternates) {
                // A dot in the corner: this key has a long press. Without it the
                // alternates exist and nobody finds them, which is what happened
                // to the symbol pages.
                canvas.drawCircle(
                    r.right - 7f * density,
                    r.bottom - 7f * density,
                    1.8f * density,
                    hintPaint,
                )
            }
    }

    /**
     * A hexagon with flat top and bottom and points at the middle of each side,
     * so neighbouring keys share an edge and the centre of every key is the only
     * place the shape is widest.
     */
    private fun buildKeyPath(rect: RectF): Path {
        val path = Path()
        if (keyShape == KeyShape.ROUNDED) {
            path.addRoundRect(rect, corner, corner, Path.Direction.CW)
            return path
        }
        val width = rect.width()
        val centreY = rect.centerY()
        val point = width * 0.17f
        val shoulder = width * 0.33f
        path.moveTo(rect.left + point, centreY)
        path.lineTo(rect.left + shoulder, rect.top)
        path.lineTo(rect.right - shoulder, rect.top)
        path.lineTo(rect.right - point, centreY)
        path.lineTo(rect.right - shoulder, rect.bottom)
        path.lineTo(rect.left + shoulder, rect.bottom)
        path.close()
        return path
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
                gliding = false
                pageSwiped = false
                glideKeys.clear()
                glidePoints.clear()
                glideTrail.reset()
                glideTrail.moveTo(event.x, event.y)
                glidePoints.add(floatArrayOf(event.x, event.y))
                lastGlideX = event.x
                lastGlideY = event.y
                scrolling = false
                lastScrollY = event.y
                scroller.forceFinished(true)
                velocityTracker?.recycle()
                velocityTracker = android.view.VelocityTracker.obtain().apply { addMovement(event) }

                setPressedKey(indexAt(event.x, event.y))
                if (pressedIndex >= 0) {
                    val key = keys[pressedIndex]
                    // The first key of a swipe is part of the word. Without this
                    // a glide from "h" to "ello" decoded as "ello".
                    if (key.kind == KeyKind.CHARACTER) glideKeys.add(key)
                    if (key.hasAlternates) handler.postDelayed(longPress, LONG_PRESS_MS)
                    if (key.kind == KeyKind.DELETE) startRepeat(key)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (popupKeys.isNotEmpty()) {
                    updatePopupSelection(event.x, event.y)
                    return true
                }

                if (scrollable && maxScroll > 0f) {
                    val dx = Math.abs(event.x - downX)
                    val dy = event.y - downY
                    if (!scrolling && Math.abs(dy) > touchSlop && Math.abs(dy) > dx) {
                        // A drag down the grid is a scroll, not a press: drop the
                        // key that was lit on the way down.
                        scrolling = true
                        cancelRepeat()
                        handler.removeCallbacks(longPress)
                        if (pressedIndex >= 0) {
                            invalidateKey(pressedIndex)
                            pressedIndex = -1
                        }
                    }
                    if (scrolling) {
                        scrollOffset = (scrollOffset + (lastScrollY - event.y)).coerceIn(0f, maxScroll)
                        lastScrollY = event.y
                        scrollbarAlpha = 1f
                        velocityTracker?.addMovement(event)
                        invalidate()
                        return true
                    }
                }

                if (pagingEnabled && !pageSwiped) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    // Vertical, because the emoji grid is taller than it is wide: a
                    // page turn is a swipe up or down, and a sideways move across
                    // emoji stays a glide.
                    if (Math.abs(dy) > pageSlop && Math.abs(dy) > 2f * Math.abs(dx)) {
                        pageSwiped = true
                        cancelRepeat()
                        handler.removeCallbacks(longPress)
                        if (pressedIndex >= 0) {
                            invalidateKey(pressedIndex)
                            pressedIndex = -1
                        }
                        listener?.onPage(if (dy < 0) 1 else -1)
                    }
                }
                if (pageSwiped) return true

                if (!longPressFired) {
                    val moved = Math.abs(event.x - downX) > touchSlop ||
                        Math.abs(event.y - downY) > touchSlop
                    if (moved) {
                        // A swipe, not a press: the delete repeat and the long
                        // press both belong to a finger that is staying put.
                        cancelRepeat()
                        handler.removeCallbacks(longPress)
                        // The emoji page scrolls rather than glides.
                        gliding = !pagingEnabled
                        // A swipe is not a press: drop the start key's highlight.
                        if (pressedIndex >= 0) {
                            invalidateKey(pressedIndex)
                            pressedIndex = -1
                        }
                    }
                }

                if (gliding) {
                    val index = indexAt(event.x, event.y)
                    if (index >= 0 && index < keys.size) {
                        val key = keys[index]
                        if (glideKeys.lastOrNull() !== key) glideKeys.add(key)
                    }
                    glidePoints.add(floatArrayOf(event.x, event.y))
                    // Bounded: a slow swipe across the whole keyboard produces
                    // hundreds of samples and the tail of them is the same curve.
                    if (glidePoints.size > MAX_GLIDE_POINTS) glidePoints.removeAt(0)
                    rebuildGlideTrail()
                    lastGlideX = event.x
                    lastGlideY = event.y
                    invalidate()
                    return true
                }

                setPressedKey(indexAt(event.x, event.y))
                return true
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                cancelRepeat()

                if (scrolling) {
                    scrolling = false
                    velocityTracker?.let { tracker ->
                        tracker.computeCurrentVelocity(1000)
                        val velocity = tracker.yVelocity
                        if (Math.abs(velocity) > MIN_FLING_VELOCITY) {
                            // Negative y velocity is a finger moving up, which is the
                            // content moving down the screen.
                            scroller.fling(
                                0,
                                scrollOffset.toInt(),
                                0,
                                -velocity.toInt(),
                                0,
                                0,
                                0,
                                maxScroll.toInt(),
                            )
                        }
                    }
                    velocityTracker?.recycle()
                    velocityTracker = null
                    pressedIndex = -1
                    invalidate()
                    return true
                }

                if (pageSwiped) {
                    pageSwiped = false
                    pressedIndex = -1
                    invalidate()
                    return true
                }

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

                if (gliding) {
                    gliding = false
                    pressedIndex = -1
                    invalidate()
                    val word = glideKeys.filter { it.kind == KeyKind.CHARACTER }
                    glidePoints.clear()
                    if (word.size >= MIN_GLIDE_KEYS) {
                        listener?.onGlide(word)
                    } else {
                        // Too short to decode as a word, so this was a press with
                        // a wobble on it. Dropping it is the other half of what
                        // made the keyboard feel like it ignored you.
                        val target = keys.getOrNull(indexAt(event.x, event.y))
                            ?: word.lastOrNull()
                            ?: glideKeys.lastOrNull()
                        if (target != null && target.kind != KeyKind.SPACER) {
                            listener?.onKeyUp(target)
                        }
                    }
                    glideKeys.clear()
                    glideTrail.reset()
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
                cancelRepeat()
                scrolling = false
                velocityTracker?.recycle()
                velocityTracker = null
                dismissPopup()
                gliding = false
                glideKeys.clear()
                glidePoints.clear()
                glideTrail.reset()
                pressedIndex = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollOffset = scroller.currY.toFloat().coerceIn(0f, maxScroll)
            scrollbarAlpha = 1f
            invalidate()
        }
    }

    private fun startRepeat(key: Key) {
        repeatKey = key
        handler.postDelayed(repeat, REPEAT_DELAY_MS)
    }

    private fun cancelRepeat() {
        repeatKey = null
        handler.removeCallbacks(repeat)
    }

    private fun openPopup(index: Int, key: Key) {
        val anchor = rects[index]
        popupKeys = key.alternates.map { key.withOutput(it) }
        popupRects.clear()
        if (popupKeys.isEmpty()) return

        if (theme.haptics) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

        val cellWidth = anchor.width()
        val totalWidth = cellWidth * popupKeys.size
        val startX = (anchor.centerX() - totalWidth / 2f).coerceIn(gap, width - totalWidth - gap)
        val height = anchor.height() * 1.15f

        // Flip below the key when there is no room above it (the number row).
        val above = anchor.top - height - gap >= 0f
        val top = if (above) anchor.top - height - gap else anchor.bottom + gap

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
            if (theme.haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (theme.keySound) KeySound.play(context)
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

    /**
     * Rebuilds the trail as a curve through the midpoints of consecutive samples,
     * using the samples themselves as control points.
     *
     * The old path was a quad to the previous point plus a line to the current
     * one, which is why it looked like a chain of straight segments with a kink
     * at every sample: that construction cannot produce a smooth curve.
     */
    private fun rebuildGlideTrail() {
        glideTrail.reset()
        if (glidePoints.isEmpty()) return
        val first = glidePoints.first()
        glideTrail.moveTo(first[0], first[1])
        if (glidePoints.size == 1) return
        for (i in 1 until glidePoints.size - 1) {
            val point = glidePoints[i]
            val next = glidePoints[i + 1]
            glideTrail.quadTo(point[0], point[1], (point[0] + next[0]) / 2f, (point[1] + next[1]) / 2f)
        }
        val last = glidePoints.last()
        glideTrail.lineTo(last[0], last[1])
    }

    private fun indexAt(x: Float, y: Float): Int {
        // On a scrolling grid the content keys live in scrolled coordinates and
        // the header and footer keys live on screen. A touch is only compared
        // against the keys in its own band, so a tap on a category tab can never
        // land on an emoji that happens to sit at the same height in the content.
        val inBand = scrollable && y >= contentTop && y <= contentBottom
        fun isContent(i: Int) = i >= headerKeyEnd && i < pinnedStartIndex
        fun pointY(i: Int) = if (scrollable && isContent(i)) y + scrollOffset else y
        fun inSameBand(i: Int) = !scrollable || isContent(i) == inBand

        for (i in rects.indices) {
            if (!inSameBand(i)) continue
            if (rects[i].contains(x, pointY(i))) return i
        }
        // The gaps between keys are narrow and fingers are not. A touch that
        // lands a few dp outside every rectangle used to type nothing at all,
        // which reads as an unresponsive keyboard rather than a near miss. The
        // nearest key within a touch slop of its edge takes the touch.
        var best = -1
        var bestDistance = Float.MAX_VALUE
        val reach = touchSlop.toFloat()
        for (i in rects.indices) {
            if (keys[i].kind == KeyKind.SPACER) continue
            if (!inSameBand(i)) continue
            val r = rects[i]
            val py = pointY(i)
            val dx = maxOf(r.left - x, 0f, x - r.right)
            val dy = maxOf(r.top - py, 0f, py - r.bottom)
            val distance = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (distance <= reach && distance < bestDistance) {
                bestDistance = distance
                best = i
            }
        }
        return best
    }

    /**
     * A lone lowercase letter. Capitals keep the full size, so pressing shift
     * changes the look of the grid as well as what it types.
     */
    private fun isLowercaseLetter(label: String): Boolean =
        label.length == 1 && label[0].isLetter() && label[0].isLowerCase()

    /** Linear blend of two ARGB colours; [amount] 0 keeps [from], 1 gives [to]. */
    private fun mix(from: Int, to: Int, amount: Float): Int {
        fun channel(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * amount).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private companion object {
        /** Keys whose label may be an emoji or a symbol glyph. */
        val GLYPH_KINDS = setOf(KeyKind.CHARACTER, KeyKind.EMOJI, KeyKind.EMOJI_TAB, KeyKind.GLOBE)
        const val LONG_PRESS_MS = 320L
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_INTERVAL_MS = 55L
        const val MIN_GLIDE_KEYS = 3

        /** Samples kept for the trail; older ones no longer change its shape. */
        const val MAX_GLIDE_POINTS = 96

        /** Below this the finger was not really thrown; a fling would look wrong. */
        const val MIN_FLING_VELOCITY = 180f

        /** How much of the scrollbar disappears per frame once scrolling stops. */
        const val SCROLLBAR_FADE_STEP = 0.06f

        /** Lowercase letters are drawn at this fraction of the capital size. */
        const val LOWER_LABEL_SCALE = 0.9f
    }
}
