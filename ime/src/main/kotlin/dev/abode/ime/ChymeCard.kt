package dev.abode.ime

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.abode.ai.AiAction
import dev.abode.ai.ChymeConfig
import dev.abode.core.common.theme.KeyboardTheme

/**
 * The Chyme card: a floating panel over the keys, opened from the strip.
 *
 * It floats rather than replacing the keyboard because the thing being worked
 * on is usually in the field behind it — replacing the keys would hide the
 * text you are rewriting.
 *
 * Built from real widgets instead of a Canvas pass. The key grid draws itself
 * because it repaints on every touch; this card is touched a handful of times
 * and then dismissed, so being cheap to write matters more than being cheap to
 * draw.
 */
class ChymeCard(context: Context) : LinearLayout(context) {

    interface Listener {
        fun onAction(action: AiAction)
        fun onInsert(text: String)
        fun onClose()

        /** Opens the Abode app, where the key and endpoint are set. */
        fun onSettings()
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density

    private val title = TextView(context).apply {
        text = "Chyme"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
    }

    private val close = TextView(context).apply {
        text = "\u2715"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(2), dp(10), dp(2))
        setOnClickListener { listener?.onClose() }
    }

    private val status = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        visibility = View.GONE
    }

    private val result = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        maxLines = 5
        visibility = View.GONE
    }

    private val insert = chip("Insert").apply {
        visibility = View.GONE
        setOnClickListener { lastResult?.let { listener?.onInsert(it) } }
    }

    private val settings = chip("Set key").apply {
        visibility = View.GONE
        setOnClickListener { listener?.onSettings() }
    }

    private val actionChips = LinkedHashMap<AiAction, TextView>()
    private var lastResult: String? = null

    init {
        orientation = VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(12))
        elevation = 8f * density

        addView(
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(close, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )

        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // Two columns, so six actions fit above the keys without a scroll view.
        val actions = AiAction.values().toList()
        for (row in actions.chunked(2)) {
            addView(
                LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    for (action in row) {
                        val view = chip(action.label).apply {
                            setOnClickListener { listener?.onAction(action) }
                        }
                        actionChips[action] = view
                        addView(view, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                            setMargins(dp(2), dp(2), dp(2), dp(2))
                        })
                    }
                    if (row.size == 1) {
                        // Keep the lone chip the same width as a paired one.
                        addView(View(context), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                    }
                },
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
        }

        addView(result, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                addView(insert, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(2), dp(4), dp(2), 0)
                })
                addView(settings, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(2), dp(4), dp(2), 0)
                })
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
    }

    fun applyTheme(theme: KeyboardTheme) {
        background = rounded(theme.popupBackground, theme.cornerRadiusDp)
        title.setTextColor(theme.keyLabel)
        close.setTextColor(theme.keyLabel)
        status.setTextColor((theme.keyLabel and 0x00FFFFFF) or (0xAA shl 24))
        result.setTextColor(theme.keyLabel)
        for (chipView in actionChips.values) {
            chipView.setTextColor(theme.keyLabel)
            chipView.background = rounded(theme.keyBackground, theme.cornerRadiusDp)
        }
        insert.setTextColor(theme.background)
        insert.background = rounded(theme.accent, theme.cornerRadiusDp)
        settings.setTextColor(theme.keyLabel)
        settings.background = rounded(theme.keyBackground, theme.cornerRadiusDp)
    }

    /** Idle state: the actions, or a pointer to settings when there is no key. */
    fun showIdle(config: ChymeConfig) {
        lastResult = null
        result.visibility = View.GONE
        insert.visibility = View.GONE
        for (chipView in actionChips.values) chipView.isEnabled = config.isConfigured
        if (config.isConfigured) {
            status.visibility = View.GONE
            settings.visibility = View.GONE
        } else {
            status.text = "No key yet. Set one in the Abode app, then come back."
            status.visibility = View.VISIBLE
            settings.visibility = View.VISIBLE
        }
    }

    fun showWorking(action: AiAction) {
        lastResult = null
        result.visibility = View.GONE
        insert.visibility = View.GONE
        status.text = "${action.label}\u2026"
        status.visibility = View.VISIBLE
        for (chipView in actionChips.values) chipView.isEnabled = false
    }

    fun showResult(text: String) {
        lastResult = text
        status.visibility = View.GONE
        result.text = text
        result.visibility = View.VISIBLE
        insert.visibility = View.VISIBLE
        for (chipView in actionChips.values) chipView.isEnabled = true
    }

    fun showError(message: String) {
        lastResult = null
        insert.visibility = View.GONE
        result.visibility = View.GONE
        status.text = message
        status.visibility = View.VISIBLE
        for (chipView in actionChips.values) chipView.isEnabled = true
    }

    private fun chip(label: String): TextView = TextView(context).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(7), dp(10), dp(7))
    }

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radiusDp * density
        }

    private fun dp(value: Int): Int = (value * density).toInt()
}
