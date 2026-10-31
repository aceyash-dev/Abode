package dev.abode.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import dev.abode.ai.AiAction
import dev.abode.ai.ChymeBridge
import dev.abode.ai.ChymeClient
import dev.abode.ai.ChymePrompts
import dev.abode.ai.ChymeStore
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Chyme, in a window over the keyboard.
 *
 * The card inside the keyboard could insert directly; a window cannot, so it hands
 * the result back through [ChymeBridge], which the keyboard registers while it is
 * alive. Same process, so no IPC and no round trip through the system.
 */
class ChymeActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_TEXT = "text"

        fun intent(context: Context, text: String): Intent =
            Intent(context, ChymeActivity::class.java)
                .putExtra(EXTRA_TEXT, text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val density get() = resources.displayMetrics.density
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var insert: TextView
    private val actions = LinearLayout(this)
    private var lastResult: String? = null
    private var source = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        source = intent?.getStringExtra(EXTRA_TEXT).orEmpty()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.WHITE)
                cornerRadius = 20f * density
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(this).apply {
                text = "Chyme"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
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

        status = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#888888"))
            text = if (source.isBlank()) "Type something first" else ""
        }
        card.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        actions.orientation = LinearLayout.VERTICAL
        for (row in AiAction.values().toList().chunked(2)) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (action in row) {
                line.addView(
                    chip(action.label) { run(action) },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                        setMargins(dp(3), dp(3), dp(3), dp(3))
                    },
                )
            }
            if (row.size == 1) line.addView(View(this), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            actions.addView(line, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        card.addView(actions, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        result = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 6
            visibility = View.GONE
        }
        card.addView(result, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        insert = chip("Insert") { lastResult?.let(::deliver) }.apply { visibility = View.GONE }
        card.addView(
            insert,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                setMargins(dp(3), dp(8), dp(3), 0)
            },
        )

        setContentView(
            LinearLayout(this).apply {
                setPadding(dp(12), dp(12), dp(12), dp(12))
                addView(card, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            },
        )
    }

    private fun run(action: AiAction) {
        val config = ChymeStore(this).load()
        if (!config.isConfigured) {
            status.text = "No key yet \u2014 set one in the Abode app"
            return
        }
        if (source.isBlank()) {
            status.text = if (action == AiAction.WRITE) "Type a topic first" else "Nothing to work on"
            return
        }
        status.text = "${action.label}\u2026"
        result.visibility = View.GONE
        insert.visibility = View.GONE
        scope.launch {
            val language = Locale.getDefault().getDisplayLanguage(Locale.ENGLISH).ifBlank { "English" }
            runCatching {
                withContext(Dispatchers.IO) {
                    ChymeClient.complete(config, ChymePrompts.forAction(action, source, language))
                }
            }
                .onSuccess {
                    lastResult = it
                    result.text = it
                    result.visibility = View.VISIBLE
                    insert.visibility = View.VISIBLE
                    status.text = ""
                }
                .onFailure { status.text = it.message ?: "Request failed" }
        }
    }

    /** Back to the keyboard, or to the clipboard if the keyboard has gone. */
    private fun deliver(text: String) {
        val sink = ChymeBridge.insert
        if (sink != null) {
            sink(text)
        } else {
            val manager = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            manager?.setPrimaryClip(android.content.ClipData.newPlainText("Chyme", text))
        }
        finish()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun chip(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(9), dp(10), dp(9))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor("#F1E9DC"))
            cornerRadius = 14f * density
        }
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}
