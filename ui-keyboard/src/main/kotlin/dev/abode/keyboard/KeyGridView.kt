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
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.ui.graphics.vector.ImageVector
import compose.icons.TablerIcons
import compose.icons.tablericons.Backspace
import compose.icons.tablericons.Clipboard
import compose.icons.tablericons.CornerDownLeft
import compose.icons.tablericons.Language
import compose.icons.tablericons.Microphone
import compose.icons.tablericons.MoodSmile
import dev.abode.core.common.theme.KeyShape
import dev.abode.core.common.theme.KeyboardTheme
import java.io.File

/**
 * Caches typefaces loaded from user-imported font files. Parsing a font is
 * expensive; doing it per keystroke or per show would be visible.
 */
object ThemeTypeface {
    private val cache = HashMap<String, Typeface>()

    fun forTheme(path: String?): Typeface? {
        if (path.isNullOrBlank()) return null
        cache[path]?.let { return it }
        val file = File(path)
        if (!file.exists()) return null
        return runCatching { Typeface.createFromFile(file) }
            .onSuccess { cache[path] = it }
            .getOrNull()
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
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 15f * resources.displayMetrics.scaledDensity
    }
    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 26f * resources.displayMetrics.scaledDensity
    }

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
        iconPaint.color = theme.keyLabel
        iconCache.clear()
        labelPaint.typeface = ThemeTypeface.forTheme(theme.fontPath)
            ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
        trailPaint.color = theme.accent
        trailPaint.strokeWidth = 4f * density

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
        cancelRepeat()
        super.onDetachedFromWindow()
    }

    private fun rebuildGeometry() {
        rects.clear()
        keyPaths.clear()
        keys.clear()
        val w = width.toFloat()
        val h = height.toFloat()
        val rows = keyboardLayout.rows
        if (w <= 0f || h <= 0f || rows.isEmpty()) return

        val rowHeightPx = (h - gap * (rows.size + 1)) / rows.size
        var top = gap
        var rowIndex = 0

        for (row in rows) {
            // A honeycomb only reads as one if alternate rows step in by half a
            // key; without it the hexagons line up in columns and look like
            // rounded rectangles with clipped corners.
            val stagger = if (keyShape == KeyShape.HEXAGON && rowIndex % 2 == 1) 0.5f else 0f
            var totalWeight = 0f
            for (key in row) totalWeight += key.widthWeight
            if (totalWeight <= 0f) continue

            // Half a key of offset on alternate rows, and the *same* key width:
            // shrinking the row to make room for the offset left the honeycomb
            // ragged down its right edge, which is what made it look broken.
            // The last key of an offset row hangs half a unit into the gap, the
            // way a honeycomb does.
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
        KeyKind.GLOBE -> TablerIcons.Language
        KeyKind.VOICE -> TablerIcons.Microphone
        KeyKind.CLIPBOARD -> TablerIcons.Clipboard
        // The "ABC" that returns from emoji stays a word: it names the page it goes to.
        KeyKind.EMOJI -> if (key.label == "ABC") null else TablerIcons.MoodSmile
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
        val count = rects.size
        for (i in 0 until count) {
            if (keys[i].kind == KeyKind.SPACER) continue
            val r = rects[i]
            val key = keys[i]
            val fill = fillFor(i, key)
            if (fill != null) canvas.drawPath(keyPaths[i], fill)
            if (key.kind == KeyKind.SHIFT) {
                drawShift(canvas, r, caps = key.label == KeyboardLayout.CAPS_LOCK_LABEL, active = key.active)
                continue
            }

            // Return is drawn as its arrow, unless the field asked for a named action
            // (send, go, search): then the word is the thing the person needs to see.
            val namedEnter = keys[i].kind == KeyKind.ENTER && keys[i].label != "return"
            val icon = if (namedEnter) null else iconFor(keys[i])
            if (icon != null) {
                drawIcon(canvas, r, icon, keys[i].kind)
                continue
            }

            val label = keys[i].label
            if (label.isEmpty()) continue

            // Emoji are drawn larger than text so the emoji page reads at a glance.
            // The test is the code point, not the label's length: a letter is never
            // an emoji, and the glyph keys that carry one are named kinds.
            val glyph = keys[i].kind in GLYPH_KINDS && label.codePointAt(0) > 0x2000
            val paint = if (glyph) emojiPaint else labelPaint
            val baseline = r.centerY() - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(label, r.centerX(), baseline, paint)
        }

        if (gliding) canvas.drawPath(glideTrail, trailPaint)
        if (popupKeys.isNotEmpty()) drawPopup(canvas)
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
                glideKeys.clear()
                glideTrail.reset()
                glideTrail.moveTo(event.x, event.y)
                lastGlideX = event.x
                lastGlideY = event.y

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

                if (!longPressFired) {
                    val moved = Math.abs(event.x - downX) > touchSlop ||
                        Math.abs(event.y - downY) > touchSlop
                    if (moved) {
                        // A swipe, not a press: the delete repeat and the long
                        // press both belong to a finger that is staying put.
                        cancelRepeat()
                        handler.removeCallbacks(longPress)
                        gliding = true
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
                    glideTrail.quadTo(
                        lastGlideX,
                        lastGlideY,
                        (lastGlideX + event.x) / 2f,
                        (lastGlideY + event.y) / 2f,
                    )
                    glideTrail.lineTo(event.x, event.y)
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
                    if (word.size >= MIN_GLIDE_KEYS) listener?.onGlide(word)
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
                dismissPopup()
                gliding = false
                glideKeys.clear()
                glideTrail.reset()
                pressedIndex = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
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
            if (theme.keySound) playSoundEffect(SoundEffectConstants.CLICK)
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
        val GLYPH_KINDS = setOf(KeyKind.CHARACTER, KeyKind.EMOJI, KeyKind.EMOJI_TAB)
        const val LONG_PRESS_MS = 320L
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_INTERVAL_MS = 55L
        const val MIN_GLIDE_KEYS = 3
    }
}
