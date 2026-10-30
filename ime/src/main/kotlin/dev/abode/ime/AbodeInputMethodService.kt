package dev.abode.ime

import android.content.ClipboardManager
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
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
import dev.abode.core.common.theme.KeyboardTheme
import dev.abode.data.ThemePreferences
import dev.abode.keyboard.Key
import dev.abode.keyboard.KeyGridView
import dev.abode.keyboard.KeyKind
import dev.abode.keyboard.KeyboardLayout
import dev.abode.text.DictionaryLoader
import dev.abode.text.DevanagariTransliterator
import dev.abode.text.GlideDecoder
import dev.abode.text.SuggestionEngine
import dev.abode.voice.GoogleSpeaker
import dev.abode.voice.SystemVoiceEngine
import dev.abode.voice.VoiceEvent
import java.util.Locale
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
 *
 * Characters commit on touch-UP, not down, so that a long press can offer
 * alternates without the primary character already having been typed.
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
    private val themePreferences by lazy { ThemePreferences(this) }
    private var currentTheme: KeyboardTheme = KeyboardTheme()
    private val suggestionEngine = SuggestionEngine(scope)

    private lateinit var root: LinearLayout
    private lateinit var grid: KeyGridView
    private lateinit var strip: TextView

    private var dictionary: dev.abode.text.TrieDictionary? = null
    private var glideDecoder: GlideDecoder? = null
    private var lastCommitWasSpace = false
    private var sentenceEnded = true

    private var voiceEngine: SystemVoiceEngine? = null
    private var speaker: GoogleSpeaker? = null
    private var listening = false

    private var shifted = false
    private var symbolsMode = false
    private var toolbarMode = false
    private var numericMode = false
    private var clipboardMode = false
    private val clipHistory = ArrayDeque<String>()
    private var transliterate = false
    private var statusText = ""
    private var lastCorrection: Pair<String, String>? = null
    private var currentSuggestions: List<String> = emptyList()
    private var capsLock = false
    private var lastShiftTap = 0L
    private val latinBuffer = StringBuilder()
    private var showStartedAt = 0L

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        // Off the main thread by construction; the trie is not ready on the
        // first keystrokes and that is fine.
        scope.launch {
            val loaded = DictionaryLoader(this@AbodeInputMethodService).load()
            dictionary = loaded
            glideDecoder = GlideDecoder(loaded)
            suggestionEngine.setDictionary(loaded)
            AbodeLog.i("dictionary loaded: ${loaded.wordCount} words")
        }

        scope.launch {
            suggestionEngine.suggestions.collect { list ->
                currentSuggestions = list
                if (::strip.isInitialized) {
                    // A status message outranks suggestions: it is the only
                    // feedback the keyboard has, and an undo offer that gets
                    // wiped by the next prediction is not an offer.
                    strip.text = if (statusText.isNotEmpty()) {
                        statusText
                    } else {
                        list.joinToString(separator = "    ")
                    }
                }
            }
        }

        speaker = GoogleSpeaker(this)

        val voice = SystemVoiceEngine(this)
        voiceEngine = voice
        scope.launch { voice.events.collect(::onVoiceEvent) }

        scope.launch {
            themePreferences.theme.collect { theme -> applyTheme(theme) }
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
                setOnClickListener { onStripTapped() }
            }

            grid = KeyGridView(this).apply {
                keyboardLayout = KeyboardLayout.qwerty()
                listener = object : KeyGridView.Listener {
                    override fun onKeyDown(key: Key) = this@AbodeInputMethodService.onKeyDown(key)
                    override fun onKeyUp(key: Key) = this@AbodeInputMethodService.onKeyUp(key)
                    override fun onKeyRepeat(key: Key) = this@AbodeInputMethodService.onKeyDown(key)
                    override fun onGlide(keys: List<Key>) = this@AbodeInputMethodService.onGlide(keys)
                }
            }

            root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(strip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34)))
                addView(grid, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ))
            }

            applyTheme(currentTheme)

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

        symbolsMode = false
        toolbarMode = false
        numericMode = false
        clipboardMode = false
        latinBuffer.setLength(0)
        transliterate = isDevanagariSubtype()
        setShift(false)
        applyLayout()
        suggestionEngine.clear()

        if (isSensitiveField()) {
            // TODO(M2/M3): disable the clipboard, AI and cloud voice keys entirely.
            AbodeLog.i("sensitive field: history, AI and cloud voice are suppressed")
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // Read-back must not outlive the keyboard, or it keeps talking to a
        // screen the user has already left.
        speaker?.stop()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        stopListening()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onDestroy() {
        voiceEngine?.release()
        voiceEngine = null
        speaker?.release()
        speaker = null
        scope.cancel()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- input

    /**
     * Touch-down. Only keys that must act immediately live here: a character
     * typed on the way down could not be replaced by a long-press alternate.
     */
    private fun onKeyDown(key: Key) {
        when (key.kind) {
            KeyKind.DELETE -> deleteBackward()
            else -> Unit
        }
    }

    /** Touch-up, or the chosen alternate after a long press. */
    private fun onKeyUp(key: Key) {
        when (key.kind) {
            KeyKind.CHARACTER -> {
                if (clipboardMode) {
                    // A clip is a finished string: commit it and get out of the
                    // way rather than staying in the panel.
                    clipboardMode = false
                    applyLayout()
                }
                if (statusText.isNotEmpty()) setStatus("")
                if (transliterate) {
                    // Buffer the Latin letters and show them as composing text,
                    // so the user sees what they typed and the whole word is
                    // available to map at once — matras need the full word.
                    latinBuffer.append(key.output)
                    currentInputConnection?.setComposingText(latinBuffer, 1)
                    return
                }
                commit(key.output)
                if (shifted && !capsLock) setShift(false)
                trackSentence(key.output)
                suggestionEngine.onPrefixChanged(currentWordPrefix(), capitalise = sentenceEnded)
            }

            KeyKind.SPACE -> handleSpace()

            KeyKind.ENTER -> handleEnter()
            KeyKind.SHIFT -> handleShift()
            KeyKind.SYMBOLS -> toggleSymbols()
            KeyKind.NUMERIC -> toggleNumeric()
            KeyKind.GLOBE -> switchInputMethod()
            KeyKind.VOICE -> toggleListening()
            KeyKind.EDIT -> toggleToolbar()
            KeyKind.CLIPBOARD -> toggleClipboard()
            KeyKind.SELECT_ALL -> contextAction(android.R.id.selectAll)
            KeyKind.CUT -> contextAction(android.R.id.cut)
            KeyKind.COPY -> contextAction(android.R.id.copy)
            KeyKind.PASTE -> contextAction(android.R.id.paste)
            KeyKind.UNDO -> sendUndo()
            KeyKind.SPEAK -> speakField()

            // TODO(M4): the AI sheet. The rest are handled elsewhere or are inert.
            KeyKind.DELETE, KeyKind.CLIPBOARD, KeyKind.AI, KeyKind.SPACER -> Unit
        }
    }

    /**
     * Space is where a word gets finished, so it is where a correction happens.
     * Double space ends the sentence, which is the habit most people already
     * have and the cheapest punctuation shortcut there is.
     */
    private fun handleSpace() {
        finishTransliteration()
        if (lastCommitWasSpace && currentTheme.doubleSpacePeriod) {
            val ic = currentInputConnection ?: return
            ic.beginBatchEdit()
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
            ic.endBatchEdit()
            lastCommitWasSpace = false
            sentenceEnded = true
            setShift(true)
            suggestionEngine.onPrefixChanged("", capitalise = true)
            return
        }

        autocorrectLastWord()
        commit(" ")
        lastCommitWasSpace = true
        suggestionEngine.onPrefixChanged("", capitalise = sentenceEnded)
    }

    /** Replaces the word behind the cursor when the dictionary has a near miss. */
    private fun autocorrectLastWord() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(40, 0)?.toString() ?: return
        val word = before.takeLastWhile { it.isLetter() || it == '\'' }
        if (word.length < 3) return

        val correction = suggestionEngine.autocorrect(word, currentTheme.autocorrectSensitivity) ?: return
        if (correction == word) return

        ic.beginBatchEdit()
        ic.deleteSurroundingText(word.length, 0)
        ic.commitText(correction, 1)
        ic.endBatchEdit()

        // Autocorrect that cannot be taken back is autocorrect people turn off.
        lastCorrection = word to correction
        setStatus("Undo \u201C$word\u201D")
    }

    /** Puts the user's own spelling back, space and all. */
    private fun undoLastCorrection() {
        val (original, corrected) = lastCorrection ?: return
        lastCorrection = null
        val ic = currentInputConnection ?: return

        ic.beginBatchEdit()
        // The corrected word is followed by the space that triggered it.
        ic.deleteSurroundingText(corrected.length + 1, 0)
        ic.commitText("$original ", 1)
        ic.endBatchEdit()
        setStatus("")
    }

    /** Tracks whether the next letter should be a capital. */
    private fun trackSentence(text: String) {
        val last = text.lastOrNull() ?: return
        lastCommitWasSpace = last == ' '
        if (last in ".!?") {
            sentenceEnded = true
            setShift(true)
        } else if (!last.isWhitespace()) {
            sentenceEnded = false
        }
    }

    /**
     * A swipe is decoded against the dictionary and committed as a finished
     * word: the finger has already drawn the word, so there is nothing left to
     * autocomplete.
     */
    private fun onGlide(keys: List<Key>) {
        val decoder = glideDecoder ?: return
        val path = keys.map { it.output.firstOrNull() ?: return }.filter { it.isLetter() }
        val word = decoder.decode(path) ?: return

        val capitalise = sentenceEnded
        val text = if (capitalise) word.replaceFirstChar { it.uppercaseChar() } else word
        commit("$text ")
        lastCommitWasSpace = true
        sentenceEnded = false
        setShift(false)
        suggestionEngine.onPrefixChanged("", capitalise = false)
    }

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
        finishTransliteration()
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

    /**
     * Tap for one capital, double-tap to lock.
     *
     * Caps lock is the difference between typing an acronym and fighting the
     * keyboard for every letter, and it is the one modifier people reach for
     * without thinking.
     */
    private fun handleShift() {
        val now = SystemClock.uptimeMillis()
        if (now - lastShiftTap < DOUBLE_TAP_MS) {
            capsLock = true
            setShift(true)
        } else {
            capsLock = false
            setShift(!shifted)
        }
        lastShiftTap = now
    }

    /**
     * The strip is the keyboard's only affordance for accepting a prediction.
     * Tapping it takes the top suggestion when there is one, and undoes the last
     * autocorrect when the strip is offering that instead.
     */
    private fun onStripTapped() {
        if (lastCorrection != null && statusText.startsWith("Undo")) {
            undoLastCorrection()
            return
        }
        val suggestion = currentSuggestions.firstOrNull() ?: return
        val ic = currentInputConnection ?: return

        val before = ic.getTextBeforeCursor(40, 0)?.toString().orEmpty()
        val word = before.takeLastWhile { it.isLetter() || it == '\'' }
        if (word.equals(suggestion, ignoreCase = true)) return

        ic.beginBatchEdit()
        if (word.isNotEmpty()) ic.deleteSurroundingText(word.length, 0)
        ic.commitText("$suggestion ", 1)
        ic.endBatchEdit()

        currentSuggestions = emptyList()
        setStatus("")
        suggestionEngine.clear()
    }

    private fun setShift(value: Boolean) {
        if (shifted == value) return
        shifted = value
        applyLayout()
    }

    private fun toggleSymbols() {
        symbolsMode = !symbolsMode
        if (!symbolsMode) shifted = false
        applyLayout()
    }

    /**
     * Custom colours and a custom font are applied by handing the view a new
     * theme. Nothing in the rendering path knows about individual properties,
     * so a theme change never touches layout code.
     */
    private fun applyTheme(theme: KeyboardTheme) {
        currentTheme = theme
        if (::grid.isInitialized) grid.theme = theme
        if (::root.isInitialized) root.setBackgroundColor(theme.background)
        if (::strip.isInitialized) strip.setTextColor(theme.keyLabel)
    }

    private fun applyLayout() {
        if (!::grid.isInitialized) return
        grid.keyboardLayout = when {
            clipboardMode -> KeyboardLayout.clipboard(clipHistory.toList())
            toolbarMode -> KeyboardLayout.editing()
            numericMode -> KeyboardLayout.numeric()
            symbolsMode -> KeyboardLayout.symbols(shifted)
            else -> KeyboardLayout.qwerty(shifted, numberRow = true)
        }
    }

    /**
     * Clipboard history, in memory only.
     *
     * Nothing is written to disk: a clipboard holds passwords, one-time codes and
     * addresses, and a keyboard that persists them has taken on a liability it
     * did not need. The history lasts as long as the keyboard process does.
     */
    private fun toggleClipboard() {
        if (clipboardMode) {
            clipboardMode = false
            applyLayout()
            return
        }

        captureCurrentClip()
        if (clipHistory.isEmpty()) {
            setStatus("Nothing copied yet")
            return
        }

        clipboardMode = true
        toolbarMode = false
        numericMode = false
        symbolsMode = false
        applyLayout()
    }

    private fun captureCurrentClip() {
        val manager = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = manager.primaryClip ?: return
        if (clip.itemCount == 0) return

        val text = clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
        if (text.isEmpty() || clipHistory.contains(text)) return

        clipHistory.addFirst(text)
        while (clipHistory.size > MAX_CLIPS) clipHistory.removeLast()
    }

    private fun toggleNumeric() {
        numericMode = !numericMode
        if (numericMode) {
            symbolsMode = false
            shifted = false
        }
        applyLayout()
    }

    private fun toggleToolbar() {
        toolbarMode = !toolbarMode
        if (toolbarMode) {
            symbolsMode = false
            shifted = false
        }
        applyLayout()
    }

    /**
     * Selection and clipboard go through the field's own context menu rather
     * than through setSelection and a private clipboard buffer: the app decides
     * what "select all" means in a rich text field, and copying through the
     * system clipboard is the only way the paste lands in the right place.
     */
    /**
     * Which script the active subtype asks for. The Latin keyboard stays as it
     * is — the point of transliteration is that you never learn a second layout.
     */
    private fun isDevanagariSubtype(): Boolean {
        val manager = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            ?: return false
        val tag = runCatching { manager.currentInputMethodSubtype?.languageTag }.getOrNull() ?: return false
        // Bhojpuri is written in Devanagari too, so it shares the transliterator.
        return tag.startsWith("hi") || tag.startsWith("mr") || tag.startsWith("bho")
    }

    /** Replaces the Latin being composed with its Devanagari reading. */
    private fun finishTransliteration() {
        if (latinBuffer.isEmpty()) return
        val ic = currentInputConnection ?: return
        val devanagari = DevanagariTransliterator.transliterate(latinBuffer.toString())
        ic.beginBatchEdit()
        ic.setComposingText("", 1)
        ic.commitText(devanagari, 1)
        ic.endBatchEdit()
        latinBuffer.setLength(0)
    }

    private fun contextAction(action: Int) {
        val ic = currentInputConnection ?: return
        ic.performContextMenuAction(action)
    }

    /**
     * Reads back what is selected, or the last sentence before the cursor.
     *
     * A sentence rather than the whole field: hearing a paragraph back to check
     * a comma is worse than not hearing it.
     */
    private fun speakField() {
        val engine = speaker ?: return
        val ic = currentInputConnection ?: return

        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        val text = if (selected.isNotBlank()) {
            selected
        } else {
            val before = ic.getTextBeforeCursor(400, 0)?.toString().orEmpty()
            val cut = before.takeLastWhile { it != '.' && it != '!' && it != '?' && it != '\n' }
            cut.ifBlank { before.takeLast(200) }
        }

        if (text.isBlank()) {
            setStatus("Nothing to read")
            return
        }
        engine.speak(text)
        setStatus(if (engine.speaking) "Reading\u2026" else "")
    }

    /** Undo has no InputConnection API; the convention every editor understands is Ctrl+Z. */
    private fun sendUndo() {
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(
            KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 0, KeyEvent.META_CTRL_ON),
        )
        ic.sendKeyEvent(
            KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Z, 0, KeyEvent.META_CTRL_ON),
        )
    }

    private fun switchInputMethod() {
        val manager = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            switchToNextInputMethod(false)
        } else {
            @Suppress("DEPRECATION")
            manager.showInputMethodPicker()
        }
    }

    // ---------------------------------------------------------------- voice

    private fun toggleListening() {
        val engine = voiceEngine
        if (engine == null) {
            setStatus("Voice is not available")
            return
        }
        if (listening) {
            stopListening()
            setStatus("")
            return
        }
        if (isSensitiveField()) {
            AbodeLog.i("voice suppressed in a sensitive field")
            setStatus("Voice is off in password fields")
            return
        }
        listening = true
        setStatus("Listening\u2026")
        engine.start(Locale.getDefault())
    }

    /**
     * The strip doubles as the keyboard's one line of feedback. Voice failures
     * used to go to logcat and nowhere else, which on a phone means the mic key
     * simply did nothing.
     */
    private fun setStatus(text: String) {
        statusText = text
        if (::strip.isInitialized) strip.text = text
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        voiceEngine?.stop()
    }

    private fun onVoiceEvent(event: VoiceEvent) {
        val ic = currentInputConnection ?: return
        when (event) {
            // Partials go into the composing region so the user watches the
            // words appear and can edit them, rather than waiting for a block.
            is VoiceEvent.Partial -> ic.setComposingText(event.text, 1)

            is VoiceEvent.Final -> {
                ic.setComposingText("", 1)
                if (event.text.isNotEmpty()) ic.commitText(event.text, 1)
                ic.finishComposingText()
                listening = false
                setStatus("")
            }

            is VoiceEvent.PermissionRequired -> {
                listening = false
                setStatus("Microphone permission needed")
                requestMicrophonePermission()
            }

            is VoiceEvent.Failed -> {
                listening = false
                setStatus(event.message)
                AbodeLog.w("voice failed: ${event.code} ${event.message}")
            }

            VoiceEvent.Ready -> {
                setStatus("Listening\u2026")
                AbodeLog.d("voice ready")
            }
        }
    }

    /**
     * An InputMethodService cannot call requestPermissions(): it has no Activity
     * to receive the result. The permission lives in :app, and :ime must not
     * depend on :app, so it is launched by class name.
     */
    private fun requestMicrophonePermission() {
        val intent = Intent().apply {
            setClassName(packageName, "dev.abode.app.permission.MicrophonePermissionActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
            .onFailure { AbodeLog.w("could not launch permission activity", it) }
    }

    // ---------------------------------------------------------------- helpers

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

    private companion object {
        const val MAX_CLIPS = 20
        const val DOUBLE_TAP_MS = 320L
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
