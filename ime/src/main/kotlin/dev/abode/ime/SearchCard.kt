package dev.abode.ime

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.abode.ai.SearchHit
import dev.abode.core.common.theme.KeyboardTheme
import dev.abode.keyboard.ThemeTypeface

/**
 * The search card: what the Google key opens.
 *
 * It used to throw the person out of their app and into a browser, which loses
 * the field they were typing in. The card answers in place — the query comes from
 * the selection or the word before the cursor — and the browser is one deliberate
 * tap away rather than the only option.
 */
class SearchCard(context: Context) : LinearLayout(context) {

    interface Listener {
        /** A result was tapped: insert its link. */
        fun onInsert(url: String)

        /** Open the query in a browser. */
        fun onOpenBrowser()

        /** Open one result in a browser. */
        fun onOpen(url: String)

        fun onClose()
    }

    var listener: Listener? = null

    /** The last theme applied, so rows built later can be styled like the rest. */
    private var currentTheme: KeyboardTheme? = null

    private val density = resources.displayMetrics.density

    private val title = TextView(context).apply {
        text = "Search"
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

    private val queryLine = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        maxLines = 2
    }

    private val status = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        visibility = View.GONE
    }

    private val results = LinearLayout(context).apply { orientation = VERTICAL }

    private val openBrowser = chip("Google")
    private val dismiss = chip("Close")

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
        addView(queryLine, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(results, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        openBrowser.setOnClickListener { listener?.onOpenBrowser() }
        dismiss.setOnClickListener { listener?.onClose() }
        addView(
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                addView(openBrowser, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(2), dp(6), dp(2), 0)
                })
                addView(dismiss, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(2), dp(6), dp(2), 0)
                })
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
    }

    fun applyTheme(theme: KeyboardTheme) {
        currentTheme = theme
        background = rounded(theme.popupBackground, theme.cornerRadiusDp)
        val face = ThemeTypeface.forTheme(theme.fontPath, theme.fontFamily)
        title.setTextColor(theme.keyLabel)
        title.typeface = face ?: title.typeface
        close.setTextColor(theme.keyLabel)
        queryLine.setTextColor(theme.keyLabel)
        queryLine.typeface = face ?: queryLine.typeface
        status.setTextColor((theme.keyLabel and 0x00FFFFFF) or (0xAA shl 24))
        openBrowser.setTextColor(theme.background)
        openBrowser.background = rounded(theme.accent, theme.cornerRadiusDp)
        dismiss.setTextColor(theme.keyLabel)
        dismiss.background = rounded(theme.keyBackground, theme.cornerRadiusDp)
        for (i in 0 until results.childCount) {
            val row = results.getChildAt(i) as? TextView ?: continue
            row.setTextColor(theme.keyLabel)
            row.background = rounded(theme.keyBackground, theme.cornerRadiusDp)
        }
    }

    /** The query, while the answer is still on its way. */
    fun showQuery(query: String) {
        queryLine.text = query
        status.text = "Searching\u2026"
        status.visibility = View.VISIBLE
        results.removeAllViews()
    }

    fun showHits(hits: List<SearchHit>) {
        status.visibility = View.GONE
        results.removeAllViews()
        if (hits.isEmpty()) {
            status.text = "No answer for that. Google will have one."
            status.visibility = View.VISIBLE
            return
        }
        for (hit in hits) {
            val row = TextView(context).apply {
                text = hit.title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 2
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setOnClickListener { listener?.onInsert(hit.url) }
                setOnLongClickListener {
                    listener?.onOpen(hit.url)
                    true
                }
            }
            // Styled as it is built: a row added after the last applyTheme used to
            // keep the default label colour on the popup background, which on a dark
            // theme is near-invisible.
            currentTheme?.let { theme ->
                row.setTextColor(theme.keyLabel)
                row.background = rounded(theme.keyBackground, theme.cornerRadiusDp)
            }
            results.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(2), dp(4), dp(2), 0)
            })
        }
    }

    fun showError(message: String) {
        results.removeAllViews()
        status.text = message
        status.visibility = View.VISIBLE
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
