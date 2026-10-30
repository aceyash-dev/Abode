package dev.abode.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Search, in a window that floats over the keyboard.
 *
 * The keyboard's Google button used to hand the query to a browser, which meant
 * leaving the app you were typing in and coming back to a field that had lost its
 * cursor. This is a small window over the keyboard instead: the page is a WebView,
 * the close button puts you back where you were, and nothing else changes state.
 *
 * It lives in the app rather than the keyboard on purpose. A WebView is a browser
 * engine, and ADR-002 keeps the IME's process graph lean — an IME that carries a
 * WebView pays for it on every show.
 */
class WebSearchActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_QUERY = "query"

        fun intent(context: Context, query: String): Intent =
            Intent(context, WebSearchActivity::class.java)
                .putExtra(EXTRA_QUERY, query)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private val density get() = resources.displayMetrics.density

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val query = intent?.getStringExtra(EXTRA_QUERY).orEmpty()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.WHITE)
                cornerRadius = 20f * density
            }
            clipToOutline = true
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(8), dp(10))
        }
        header.addView(
            TextView(this).apply {
                text = query.ifBlank { "Search" }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
        )
        header.addView(
            TextView(this).apply {
                text = "\u2715"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(4), dp(12), dp(4))
                setOnClickListener { finish() }
            },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
        )
        card.addView(header, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val web = WebView(this).apply {
            // JavaScript, because a search page is not a document: DuckDuckGo's
            // results do not render without it. No file access and no third-party
            // cookies, so the window is a viewer rather than a browser.
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            webViewClient = WebViewClient()
            loadUrl("https://duckduckgo.com/?q=" + android.net.Uri.encode(query))
        }
        card.addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(card, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        setContentView(root)
    }

    override fun onBackPressed() {
        finish()
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}
