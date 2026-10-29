package dev.abode.ime

import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.abode.core.common.AbodeDispatchers
import dev.abode.core.common.AbodeLog
import dev.abode.keyboard.Key
import dev.abode.keyboard.KeyGridView
import dev.abode.keyboard.KeyKind
import dev.abode.keyboard.KeyboardLayout
import dev.abode.text.DictionaryLoader
import dev.abode.text.SuggestionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The IME.
 *
 * Three things in here matter more than the rest:
 *
 *  1. It is a Service, not an Activity. There is no lifecycle handed to us, so
 *     the service implements LifecycleOwner / ViewModelStoreOwner /
 *     SavedStateRegistryOwner itself and attaches them to the window's decor
 *     view. Without that, any Compose content (the clipboard panel, the
 *     snippet manager) crashes on a missing ViewTreeLifecycleOwner.
 *
 *  2. The input view is built ONCE and reused. onCreateInputView runs on the
 *     show path; rebuilding the view there is the difference between a
 *     keyboard that appears and one that arrives.
 *
 *  3. Nothing blocking happens here. The dictionary loads in a coroutine, the
 *     prediction is asked for after the character is committed, and the
 *     latency of the show path is logged so it can be tracked over time.
 */
class AbodeInputMethodService : InputMethodService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    private val scope = CoroutineScope(SupervisorJob() + AbodeDispatchers.main)
    private val suggestionEngine = SuggestionEngine(scope)

    private lateinit var root: LinearLayout
    private lateinit var grid: KeyGridView
    private lateinit var strip: TextView

    private var shifted = false
    private var showStartedAt = 0L

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        // Off the main thread by construction; the trie is not ready on the
        // first keystrokes and that is fine.
        scope.launch {
            val dictionary = DictionaryLoader(this@AbodeInputMethodService).load()
            suggestionEngine.setDictionary(dictionary)
            AbodeLog.i("dictionary loaded: ${dictionary.wordCount} words")
        }

        scope.launch {
            suggestionEngine.suggestions.collect { list ->
                if (::strip.isInitialized) {
                    strip.text = list.joinToString(separator = "    ")
                }
            }
        }
    }

    override fun onCreateInputView(): View {
        window?.window?.let { window ->
            // The system already animates the IME window in. Adding our own
            // animation only adds frames to the show path.
            window.setWindowAnimations(0)

            val decor = window.decorView
            decor.setViewTreeLifecycleOwner(this)
            decor.setViewTreeViewModelStoreOwner(this)
            decor.setViewTreeSavedStateRegistryOwner(this)
        }

        if (!::root.isInitialized) {
            strip = TextView(this).apply {
                textSize = 14f
                maxLines = 1
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setTextColor(0xFF5F5F5F.toInt())
            }

            grid = KeyGridView(this).apply {
                keyboardLayout = KeyboardLayout.qwerty()
                listener = object : KeyGridView.Listener {
                    override fun onKeyPressed(key: Key) = this@AbodeInputMethodService.onKeyPressed(key)
                    override fun onKeyReleased(key: Key) = this@AbodeInputMethodService.onKeyReleased(key)
                }
            }

            root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(strip, LinearLayout.LayoutParams(MATCH_PARENT, dp(36)))
                addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }

            root.viewTreeObserver.addOnPreDrawListener {
                if (showStartedAt != 0L) {
                    val elapsed = SystemClock.uptimeMillis() - showStartedAt
                    AbodeLog.i("show latency: ${elapsed}ms")
                    showStartedAt = 0L
                }
                true
            }
        }

        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        showStartedAt = SystemClock.uptimeMillis()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        setShift(false)
        suggestionEngine.clear()

        if (isSensitiveField()) {
            // TODO(M3): disable the clipboard, AI and voice keys entirely here.
            AbodeLog.i("sensitive field: history, AI and cloud voice are suppressed")
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onDestroy() {
        scope.cancel()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- input

    private fun onKeyPressed(key: Key) {
        when (key.kind) {
            KeyKind.CHARACTER -> {
                commit(key.output)
                if (shifted) setShift(false)
                suggestionEngine.onPrefixChanged(currentWordPrefix())
            }

            KeyKind.SPACE -> {
                commit(" ")
                suggestionEngine.onPrefixChanged("")
            }

            KeyKind.ENTER -> handleEnter()
            KeyKind.DELETE -> deleteBackward()
            KeyKind.SHIFT -> setShift(!shifted)

            // TODO(M2/M3/M4): clipboard panel, voice, AI sheet.
            KeyKind.SYMBOLS, KeyKind.GLOBE, KeyKind.VOICE, KeyKind.CLIPBOARD, KeyKind.AI -> Unit
        }
    }

    /** Reserved for press-and-hold repeat on delete. */
    private fun onKeyReleased(key: Key) = Unit

    private fun commit(text: String) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        ic.commitText(text, 1)
        ic.endBatchEdit()
    }

    private fun deleteBackward() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        ic.beginBatchEdit()
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingText(1, 0)
        }
        ic.endBatchEdit()
    }

    private fun handleEnter() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        if (info != null && (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0) {
            val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
            if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                if (ic.performEditorAction(action)) return
            }
        }
        commit("\n")
    }

    private fun setShift(value: Boolean) {
        if (shifted == value) return
        shifted = value
        if (::grid.isInitialized) {
            grid.keyboardLayout = KeyboardLayout.qwerty(shifted)
        }
    }

    private fun currentWordPrefix(): String {
        val ic = currentInputConnection ?: return ""
        val before = ic.getTextBeforeCursor(24, 0)?.toString() ?: return ""
        if (before.isEmpty()) return ""
        val lastSpace = before.lastIndexOfAny(charArrayOf(' ', '\n', '\t'))
        val word = if (lastSpace >= 0) before.substring(lastSpace + 1) else before
        return if (word.all { it.isLetter() || it == '\'' }) word else ""
    }

    private fun isSensitiveField(): Boolean {
        val info = currentInputEditorInfo ?: return false
        val type = info.inputType
        return when (type and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> {
                val variation = type and InputType.TYPE_MASK_VARIATION
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            }

            InputType.TYPE_CLASS_NUMBER -> {
                (type and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            }

            else -> false
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
