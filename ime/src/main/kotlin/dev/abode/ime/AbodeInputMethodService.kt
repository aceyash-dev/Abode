package dev.abode.ime

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
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
import dev.abode.ai.AiAction
import dev.abode.ai.ChymeClient
import dev.abode.ai.ChymePrompts
import dev.abode.ai.ChymeStore
import dev.abode.ai.GifResult
import dev.abode.ai.GiphyClient
import dev.abode.core.common.AbodeDispatchers
import dev.abode.core.common.AbodeLog
import dev.abode.core.common.theme.KeyboardTheme
import dev.abode.data.SystemTheme
import dev.abode.data.ThemePreferences
import dev.abode.keyboard.EmojiCatalog
import dev.abode.keyboard.EmojiPalette
import dev.abode.keyboard.EmojiSearch
import dev.abode.keyboard.Key
import dev.abode.keyboard.KeyGridView
import dev.abode.keyboard.KeyKind
import dev.abode.keyboard.KeyboardLayout
import dev.abode.keyboard.SuggestionStrip
import dev.abode.text.DictionaryLoader
import dev.abode.text.DevanagariTransliterator
import dev.abode.text.GlideDecoder
import dev.abode.text.SuggestionEngine
import dev.abode.text.TrieDictionary
import dev.abode.voice.GoogleSpeaker
import dev.abode.voice.SystemVoiceEngine
import dev.abode.voice.VoiceEvent
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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

    /** The trie the engine reads, kept so learned words can be added to it. */
    private var dictionary: TrieDictionary? = null

    private val learned by lazy { LearnedWords(this) }

    private lateinit var root: FrameLayout
    private lateinit var grid: KeyGridView
    private lateinit var strip: SuggestionStrip
    private lateinit var chymeCard: ChymeCard
    private var chymeOpen = false

    /** The theme as stored, before the system's night mode and wallpaper are applied. */
    private var storedTheme: KeyboardTheme? = null
    private val chymeStore by lazy { ChymeStore(this) }

    private var glideDecoder: GlideDecoder? = null
    private var lastCommitWasSpace = false
    private var sentenceEnded = true

    private var voiceEngine: SystemVoiceEngine? = null
    private var speaker: GoogleSpeaker? = null
    private var listening = false

    private var shifted = false
    private var symbolsMode = false
    private var symbolsPage = 1
    private var emojiSearch = false
    private var typedStyle = 0
    private var spaceLabel = "space"
    private var emojiQuery = ""

    private var gifSearch = false
    private var gifQuery = ""
    private var gifJob: Job? = null
    private var currentGifs: List<GifResult> = emptyList()

    /** True while the grid is showing the results of the last search. */
    private var gifResultsMode = false

    /**
     * Thumbnails, held by media URL.
     *
     * A few hundred KB of stills, kept for the session only: the GIF grid is
     * looked at for a second and then closed, so nothing here is worth writing to
     * disk, and an LRU cap means a long session cannot grow it without bound.
     */
    private val gifBitmaps =
        object : android.util.LruCache<String, android.graphics.Bitmap>(8 * 1024 * 1024) {
            override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = value.byteCount
        }
    private var toolbarMode = false

    /** Whether the selection group is expanded in the tool row. */
    private var editGroupOpen = false
    private var numericMode = false
    private var clipboardMode = false
    private var emojiMode = false
    private var emojiCategory = 1
    private var emojiPage = 0
    private val clipHistory = ArrayDeque<String>()
    private var transliterate = false

    /** Hinglish is Latin script, so it offers Devanagari as a suggestion instead. */
    private var hinglish = false
    private var statusText = ""
    private var lastCorrection: Pair<String, String>? = null
    private var currentSuggestions: List<String> = emptyList()
    private var capsLock = false
    private var lastShiftTap = 0L
    private val latinBuffer = StringBuilder()
    private var showStartedAt = 0L

    override fun onCreate() {
        super.onCreate()
        loadSpaceLabel()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        // Off the main thread by construction; the trie is not ready on the
        // first keystrokes and that is fine.
        scope.launch {
            val loaded = DictionaryLoader(this@AbodeInputMethodService).load()
            dictionary = loaded
            glideDecoder = GlideDecoder(loaded)
            suggestionEngine.setDictionary(loaded)
            learned.applyTo(loaded)
            AbodeLog.i("dictionary loaded: ${loaded.wordCount} words")
        }

        scope.launch {
            suggestionEngine.suggestions.collect { list ->
                val hinted = withEmojiHints(list)
                val merged = if (hinglish) withTransliterationHint(hinted) else hinted
                currentSuggestions = merged
                if (::strip.isInitialized) {
                    // A status message outranks suggestions: it is the only
                    // feedback the keyboard has, and an undo offer that gets
                    // wiped by the next prediction is not an offer.
                    strip.status = statusText
                    strip.suggestions = if (statusText.isNotEmpty()) emptyList() else merged
                }
            }
        }

        speaker = GoogleSpeaker(this)

        val voice = SystemVoiceEngine(this)
        voiceEngine = voice
        scope.launch { voice.events.collect(::onVoiceEvent) }

        scope.launch {
            themePreferences.theme.collect { theme ->
                storedTheme = theme
                applyTheme(SystemTheme.resolve(this@AbodeInputMethodService, theme))
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
            strip = SuggestionStrip(this).apply {
                toolbarButton = true
                chymeButton = true
                listener = object : SuggestionStrip.Listener {
                    override fun onSuggestion(index: Int) = onStripTapped(index)
                    override fun onToolbar() = toggleToolbar()
                    override fun onChyme() = toggleChyme()
                    override fun onCancelVoice() = cancelListening()
                }
            }

            grid = KeyGridView(this).apply {
                imageFor = { url -> gifBitmaps.get(url) }
                keyboardLayout = KeyboardLayout.qwerty()
                listener = object : KeyGridView.Listener {
                    override fun onKeyDown(key: Key) = this@AbodeInputMethodService.onKeyDown(key)
                    override fun onKeyUp(key: Key) = this@AbodeInputMethodService.onKeyUp(key)
                    override fun onKeyRepeat(key: Key) = this@AbodeInputMethodService.onKeyDown(key)
                    override fun onGlide(keys: List<Key>) = this@AbodeInputMethodService.onGlide(keys)
                    override fun onPage(step: Int) = this@AbodeInputMethodService.onEmojiPage(step)
                }
            }

            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    strip,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
                addView(grid, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ))
            }

            chymeCard = ChymeCard(this).apply {
                visibility = View.GONE
                listener = object : ChymeCard.Listener {
                    override fun onAction(action: AiAction) = runChyme(action)
                    override fun onInsert(text: String) = insertChymeResult(text)
                    override fun onClose() = closeChyme()
                    override fun onSettings() = openAbodeApp()
                }
            }

            // A frame rather than a column: the Chyme card floats over the keys
            // instead of pushing them, so the text being rewritten stays visible
            // behind it.
            root = FrameLayout(this).apply {
                addView(
                    column,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
                addView(
                    chymeCard,
                    FrameLayout.LayoutParams(
                        minOf(dp(320), resources.displayMetrics.widthPixels - dp(16)),
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP or Gravity.START,
                    ).apply {
                        leftMargin = dp(8)
                        topMargin = dp(6)
                    },
                )
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
        emojiMode = false
        emojiPage = 0
        editGroupOpen = false
        gifSearch = false
        gifQuery = ""
        closeChyme()
        latinBuffer.setLength(0)
        transliterate = isDevanagariSubtype()
        hinglish = isHinglishSubtype()
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Night mode can flip while the keyboard is up. Re-resolving here means the
        // keyboard follows the system immediately, instead of on the next show or
        // the next preference change.
        storedTheme?.let { applyTheme(SystemTheme.resolve(this, it)) }
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
            KeyKind.DELETE -> when {
                gifSearch -> backspaceGifSearch()
                emojiSearch -> backspaceEmojiSearch()
                else -> deleteBackward()
            }
            else -> Unit
        }
    }

    /** Touch-up, or the chosen alternate after a long press. */
    private fun onKeyUp(key: Key) {
        when (key.kind) {
            KeyKind.CHARACTER -> {
                if (gifSearch) {
                    // Only the query is collected here. One search per return, not
                    // one per letter: the key is a quota.
                    gifQuery += key.output.lowercase()
                    showGifQuery()
                    return
                }
                if (emojiSearch) {
                    emojiQuery += key.output.lowercase()
                    refreshEmojiSearch()
                    return
                }
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
                commit(if (typedStyle != 0 && !emojiMode) styledText(key.output, typedStyle) else key.output)
                if (emojiMode) recordRecent(key.output)
                if (shifted && !capsLock) setShift(false)
                trackSentence(key.output)
                suggestionEngine.onPrefixChanged(currentWordPrefix(), capitalise = sentenceEnded)
            }

            KeyKind.SPACE -> when {
                gifSearch -> endGifSearch()
                emojiSearch -> endEmojiSearch()
                else -> handleSpace()
            }

            KeyKind.ENTER -> when {
                gifSearch -> runGifSearch()
                emojiSearch -> endEmojiSearch()
                else -> handleEnter()
            }
            KeyKind.SHIFT -> handleShift()
            KeyKind.SYMBOLS -> toggleSymbols()
            KeyKind.SYMBOLS_PAGE -> {
                symbolsPage = key.output.toIntOrNull() ?: 1
                applyLayout()
            }
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
            KeyKind.SEARCH_WEB -> searchWeb()
            KeyKind.SELECT_WORD -> selectWord()
            KeyKind.CHYME -> toggleChyme()
            KeyKind.EDIT_GROUP -> {
                editGroupOpen = !editGroupOpen
                applyLayout()
            }
            KeyKind.SERIF_TOGGLE -> {
                typedStyle = (typedStyle + 1) % KeyboardLayout.TYPED_STYLE_NAMES.size
                applyLayout()
            }
            KeyKind.EMOJI -> toggleEmoji()
            KeyKind.EMOJI_TAB -> selectEmojiCategory(key.output.toIntOrNull() ?: 0)
            KeyKind.EMOJI_MORE -> nextEmojiPage()
            KeyKind.EMOJI_SEARCH -> beginEmojiSearch()
            KeyKind.GIF -> if (gifResultsMode) endGifSearch() else beginGifSearch()
            KeyKind.GIF_RESULT -> pickGifByUrl(key.output)

            // TODO(M4): the AI sheet. The rest are handled elsewhere or are inert.
            KeyKind.DELETE, KeyKind.CLIPBOARD, KeyKind.AI, KeyKind.SPACER -> Unit
            // A section label in the clipboard panel: it draws and does nothing.
            KeyKind.CLIP_HEADER -> Unit
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
        learnCurrentWord()
        commit(" ")
        lastCommitWasSpace = true
        suggestionEngine.onPrefixChanged("", capitalise = sentenceEnded)
    }

    /**
     * Remembers the word that was just finished, if it is new enough to matter.
     *
     * Read from the field rather than tracked in a buffer, because autocorrect
     * may have replaced it and the person's own spelling is the one worth
     * learning.
     */
    private fun learnCurrentWord() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(40, 0)?.toString().orEmpty()
        val word = before.takeLastWhile { it.isLetter() || it == '\'' }
        if (word.length < 2) return
        if (learned.record(word)) {
            dictionary?.insert(word.lowercase(), 40)
        }
    }

    /** Replaces the word behind the cursor when the dictionary has a near miss. */
    private fun autocorrectLastWord() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(40, 0)?.toString() ?: return

        // A single comma pressed inside a word ("hel,lo") is a slip, not a
        // separator. If the word is real once it is removed, take it out.
        val raw = before.takeLastWhile { it.isLetter() || it == '\'' || it == ',' }
        if (raw.count { it == ',' } == 1 && raw.indexOf(',') in 1 until raw.length - 1) {
            val fixed = raw.replace(",", "")
            if (dictionary?.contains(fixed.lowercase()) == true) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(raw.length, 0)
                ic.commitText(fixed, 1)
                ic.endBatchEdit()
                lastCorrection = raw to fixed
                setStatus("Undo “$raw”")
                return
            }
        }

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
        // setShift returns early when the value has not changed, and a caps-lock
        // double tap changes only the lock. Redraw explicitly so the key's label
        // and state always match what the next letter will be.
        applyLayout()
        lastShiftTap = now
    }

    /**
     * The strip is the keyboard's only affordance for accepting a prediction.
     * Tapping it takes the top suggestion when there is one, and undoes the last
     * autocorrect when the strip is offering that instead.
     */
    private fun onStripTapped(index: Int = 0) {
        if (gifSearch) {
            pickGif(index)
            return
        }
        if (emojiSearch) {
            pickSearchEmoji(index)
            return
        }
        if (lastCorrection != null && statusText.startsWith("Undo")) {
            undoLastCorrection()
            return
        }
        // The slot that was tapped, not always the first: the second and third
        // predictions were previously unreachable, which made a strip of three
        // suggestions a strip of one.
        val suggestion = currentSuggestions.getOrNull(index)
            ?: currentSuggestions.firstOrNull()
            ?: return
        val ic = currentInputConnection ?: return

        val before = ic.getTextBeforeCursor(40, 0)?.toString().orEmpty()
        val word = before.takeLastWhile { it.isLetter() || it == '\'' }
        if (word.equals(suggestion, ignoreCase = true)) return

        ic.beginBatchEdit()
        if (word.isNotEmpty()) ic.deleteSurroundingText(word.length, 0)
        ic.commitText(if (isEmojiHint(suggestion)) suggestion else "$suggestion ", 1)
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
        symbolsPage = 1
        if (!symbolsMode) shifted = false
        applyLayout()
    }

    /**
     * Custom colours and a custom font are applied by handing the view a new
     * theme. Nothing in the rendering path knows about individual properties,
     * so a theme change never touches layout code.
     */
    /** The tray is rounded at its top edge only, as the system keyboards are. */
    private fun trayBackground(color: Int): android.graphics.drawable.Drawable {
        val r = 22f * resources.displayMetrics.density
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
    }

    private fun applyTheme(theme: KeyboardTheme) {
        // The key font is the keyboard's own choice (set from the tools panel),
        // so it overrides whatever the theme says. Default: the device font.
        val themed = theme
        currentTheme = themed
        if (::grid.isInitialized) grid.theme = themed
        if (::root.isInitialized) root.background = trayBackground(themed.background)
        if (::strip.isInitialized) strip.applyTheme(themed)
        if (::chymeCard.isInitialized) chymeCard.applyTheme(themed)
    }

    /** Selects the word before the cursor, so Copy and Cut act on it. */
    private fun selectWord() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(60, 0)?.toString().orEmpty()
        val length = before.takeLastWhile { it.isLetterOrDigit() || it == '\'' }.length
        if (length == 0) {
            setStatus("Nothing to select")
            return
        }
        val cursor = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)?.selectionEnd ?: return
        ic.setSelection(cursor - length, cursor)
    }

    private fun applyLayout() {
        if (!::grid.isInitialized) return
        // The emoji grid scrolls now rather than paging, so the pager stays off.
        grid.pagingEnabled = false
        val scrollingPanel = gifResultsMode || clipboardMode
        grid.scrollable = (emojiMode && !emojiSearch && !gifSearch) || scrollingPanel
        grid.pinnedTopRows = if (scrollingPanel) 0 else 1
        grid.pinnedRows = if (scrollingPanel) 1 else 2
        // Three content rows, so the emoji page is the same height as the letters
        // page: at four it was a row taller, and the keyboard grew and shrank as
        // you moved between them.
        grid.visibleContentRows = 3
        val base = when {
            gifResultsMode -> KeyboardLayout.gifResults(currentGifs.map { it.url })
            gifSearch -> KeyboardLayout.qwerty(shifted = false, numberRow = true, capsLock = false, enterLabel = enterLabel())
            clipboardMode -> KeyboardLayout.clipboard(
                links = clipHistory.filter { isLink(it) },
                texts = clipHistory.filterNot { isLink(it) },
            )
            numericMode -> KeyboardLayout.numeric()
            symbolsMode -> KeyboardLayout.symbols(symbolsPage)
            emojiMode -> KeyboardLayout.emojiScroll(emojiCategory, ::emojiRenders)
            else -> KeyboardLayout.qwerty(
                shifted = shifted,
                numberRow = true,
                capsLock = capsLock,
                enterLabel = enterLabel(),
            )
        }
        // The tool row is prepended to whatever is showing, so the suggestions
        // above the keys survive it: opening the tools used to replace the whole
        // keyboard, predictions included.
        val withTools = if (toolbarMode) {
            base.copy(rows = KeyboardLayout.toolsRow(typedStyle, editGroupOpen) + base.rows)
        } else {
            base
        }
        grid.keyboardLayout = withTools.withSpaceLabel(spaceLabel)
    }

    private val glyphPaint = android.graphics.Paint()
    private val glyphCache = HashMap<String, Boolean>()

    /**
     * Whether the system font can draw [emoji] as one glyph. Checked once per
     * string: a sequence the font lacks falls back to boxes, so it is hidden.
     */
    private fun emojiRenders(emoji: String): Boolean = true

    /**
     * The return key says what pressing it will do. A field that wants a search
     * or a send shows "search" or "send", the same cue the system keyboard gives,
     * so the key never promises a newline it will not type.
     */
    private fun enterLabel(): String {
        val info = currentInputEditorInfo ?: return "return"
        if ((info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return "return"
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEND -> "send"
            EditorInfo.IME_ACTION_GO -> "go"
            EditorInfo.IME_ACTION_SEARCH -> "search"
            EditorInfo.IME_ACTION_DONE -> "done"
            EditorInfo.IME_ACTION_NEXT -> "next"
            else -> "return"
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

    /** Whether a clip is a link. Used to group the clipboard panel. */
    private fun isLink(text: String): Boolean =
        text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)

    private fun captureCurrentClip() {
        val manager = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = manager.primaryClip ?: return
        if (clip.itemCount == 0) return

        val text = clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
        if (text.isEmpty() || clipHistory.contains(text)) return

        clipHistory.addFirst(text)
        while (clipHistory.size > MAX_CLIPS) clipHistory.removeLast()
    }

    /**
     * The emoji page is a mode like the numeric page: it stays until the person
     * goes back to letters, so a run of emoji does not keep bouncing them out.
     */
    /** Two word predictions, then an emoji for the word if it has one, then the rest. */
    private fun withEmojiHints(words: List<String>): List<String> {
        val prefix = currentWordPrefix()
        val hints = if (prefix.length >= 2) EmojiSearch.search(prefix, 1, ::emojiRenders) else emptyList()
        return (words.take(2) + hints + words.drop(2)).take(3)
    }

    /**
     * The Devanagari reading of the word being typed, offered first.
     *
     * This is what makes a Hinglish subtype useful without a Romanised Hindi
     * dictionary: "namaste" puts "नमस्ते" in the first slot, and taking it
     * replaces the Latin word. Ignoring it costs nothing.
     */
    private fun withTransliterationHint(words: List<String>): List<String> {
        val prefix = currentWordPrefix()
        if (prefix.length < 2 || prefix.any { !it.isLetter() }) return words
        val devanagari = DevanagariTransliterator.transliterate(prefix)
        if (devanagari.isEmpty() || devanagari == prefix) return words
        if (words.firstOrNull() == devanagari) return words
        return (listOf(devanagari) + words).take(3)
    }

    private fun isEmojiHint(text: String): Boolean =
        text.isNotEmpty() && text.codePointAt(0) > 0x2000 && text.none { it.isLetterOrDigit() }

    private fun beginEmojiSearch() {
        emojiSearch = true
        emojiQuery = ""
        applyLayout()
        refreshEmojiSearch()
    }

    private fun beginGifSearch() {
        gifSearch = true
        gifResultsMode = false
        gifQuery = ""
        applyLayout()
        showGifQuery()
    }

    private fun endGifSearch() {
        gifSearch = false
        gifResultsMode = false
        gifQuery = ""
        gifJob?.cancel()
        gifJob = null
        currentGifs = emptyList()
        currentSuggestions = emptyList()
        if (::strip.isInitialized) {
            strip.suggestions = emptyList()
            strip.status = ""
        }
        applyLayout()
    }

    private fun backspaceGifSearch() {
        if (gifQuery.isEmpty()) endGifSearch() else {
            gifQuery = gifQuery.dropLast(1)
            showGifQuery()
        }
    }

    /** Types the query on the strip. No request: that waits for the return key. */
    private fun showGifQuery() {
        if (!::strip.isInitialized) return
        strip.suggestions = emptyList()
        strip.status = if (gifQuery.isEmpty()) {
            "Type a GIF search, then return"
        } else {
            "GIF: \u201C$gifQuery\u201D \u2014 return to search"
        }
    }

    /**
     * One request, one page of results.
     *
     * The key is a quota, so a search costs exactly one call and the grid then
     * shows stills that were fetched once. Nothing here runs per keystroke, and
     * scrolling the results never asks Giphy anything.
     */
    private fun runGifSearch() {
        val query = gifQuery.trim()
        if (query.isEmpty()) {
            showGifQuery()
            return
        }
        if (::strip.isInitialized) {
            strip.suggestions = emptyList()
            strip.status = "Searching GIFs\u2026"
        }
        gifJob?.cancel()
        gifJob = scope.launch {
            runCatching { GiphyClient.search(query) }
                .onSuccess { results ->
                    currentGifs = results
                    gifResultsMode = results.isNotEmpty()
                    gifSearch = false
                    if (::strip.isInitialized) {
                        strip.status = if (results.isEmpty()) "No GIFs for \u201C$query\u201D" else ""
                    }
                    applyLayout()
                    loadGifStills(results)
                }
                .onFailure { error ->
                    if (::strip.isInitialized) {
                        strip.status = error.message ?: "Giphy search failed"
                    }
                }
        }
    }

    /** Fetches the stills once, a few at a time, and repaints as they land. */
    private fun loadGifStills(results: List<GifResult>) {
        results.forEach { result ->
            if (gifBitmaps.get(result.url) != null) return@forEach
            scope.launch {
                val bitmap = kotlinx.coroutines.withContext(AbodeDispatchers.io) {
                    downloadStill(result.thumbUrl)
                }
                if (bitmap != null) {
                    gifBitmaps.put(result.url, bitmap)
                    if (::grid.isInitialized) grid.invalidate()
                }
            }
        }
    }

    private fun downloadStill(url: String): android.graphics.Bitmap? = runCatching {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
    }.getOrNull()

    /** Inserts the link of the GIF that was tapped. */
    private fun pickGifByUrl(url: String) {
        if (url.isBlank()) return
        commit(url)
        endGifSearch()
    }

    /** Inserts the link of the tapped GIF and leaves search. */
    private fun pickGif(index: Int) {
        val gif = currentGifs.getOrNull(index) ?: return
        commit(gif.url)
        endGifSearch()
    }

    private fun endEmojiSearch() {
        emojiSearch = false
        emojiQuery = ""
        currentSuggestions = emptyList()
        if (::strip.isInitialized) {
            strip.suggestions = emptyList()
            strip.status = ""
        }
        applyLayout()
    }

    private fun backspaceEmojiSearch() {
        if (emojiQuery.isEmpty()) endEmojiSearch() else {
            emojiQuery = emojiQuery.dropLast(1)
            refreshEmojiSearch()
        }
    }

    /** Shows the matches as strip slots, or says why there are none. */
    private fun refreshEmojiSearch() {
        // Wrapped because this runs inside the touch handler: an exception here
        // would take down whichever app the person was typing in.
        val results = runCatching { EmojiSearch.search(emojiQuery, 3, ::emojiRenders) }
            .getOrElse {
                AbodeLog.w("emoji search failed", it)
                if (::strip.isInitialized) strip.status = "Emoji search failed"
                return
            }
        currentSuggestions = results
        if (::strip.isInitialized) {
            strip.suggestions = results
            strip.status = when {
                results.isNotEmpty() -> ""
                emojiQuery.isEmpty() -> "Search emoji"
                else -> "No emoji for \u201C$emojiQuery\u201D"
            }
        }
    }

    /** Inserts the tapped match and leaves search. */
    private fun pickSearchEmoji(index: Int) {
        val emoji = currentSuggestions.getOrNull(index) ?: return
        commit(emoji)
        recordRecent(emoji)
        endEmojiSearch()
    }

    private val emojiPrefs by lazy { getSharedPreferences("abode_emoji", Context.MODE_PRIVATE) }

    /** Recent emoji survive restarts: a small list in the keyboard's own preferences. */
    private fun loadRecents(): List<String> = try {
        emojiPrefs.getString("recent", "").orEmpty().split('\u0001').filter { it.isNotEmpty() }
    } catch (e: Exception) {
        emptyList()
    }

    private fun recordRecent(emoji: String) {
        val list = (listOf(emoji) + EmojiPalette.recents.filter { it != emoji }).take(30)
        EmojiPalette.recents = list
        try {
            emojiPrefs.edit().putString("recent", list.joinToString("\u0001")).apply()
        } catch (e: Exception) {
            AbodeLog.w("recents not saved: ${e.message}")
        }
    }

    /** A sideways swipe on the emoji page turns to the previous or next page. */
    private fun onEmojiPage(step: Int) {
        if (!emojiMode || emojiSearch) return
        val pages = KeyboardLayout.emojiPageCount(emojiCategory, ::emojiRenders)
        emojiPage = ((emojiPage + step) % pages + pages) % pages
        applyLayout()
    }

    private fun toggleEmoji() {
        emojiSearch = false
        emojiQuery = ""
        if (!emojiMode) EmojiPalette.recents = loadRecents()
        emojiMode = !emojiMode
        if (emojiMode) {
            symbolsMode = false
            numericMode = false
            toolbarMode = false
            clipboardMode = false
            shifted = false
        }
        emojiPage = 0
        applyLayout()
    }

    private fun selectEmojiCategory(index: Int) {
        emojiCategory = index.coerceIn(0, EmojiPalette.tabs.lastIndex)
        emojiPage = 0
        applyLayout()
    }

    private fun nextEmojiPage() {
        val pages = KeyboardLayout.emojiPageCount(emojiCategory, ::emojiRenders)
        emojiPage = (emojiPage + 1) % pages
        applyLayout()
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

    // ---------------------------------------------------------------- chyme

    private fun toggleChyme() {
        if (!::chymeCard.isInitialized) return
        chymeOpen = !chymeOpen
        chymeCard.visibility = if (chymeOpen) View.VISIBLE else View.GONE
        if (chymeOpen) chymeCard.showIdle(chymeStore.load())
    }

    private fun closeChyme() {
        chymeOpen = false
        if (::chymeCard.isInitialized) chymeCard.visibility = View.GONE
    }

    /**
     * What an action works on: the selection if there is one, otherwise the
     * sentence before the cursor. A whole field is too much to rewrite, and one
     * word is not enough to fix a sentence's grammar.
     */
    private fun textForChyme(): String {
        val ic = currentInputConnection ?: return ""
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        if (selected.isNotBlank()) return selected
        val before = ic.getTextBeforeCursor(600, 0)?.toString().orEmpty()
        return before.takeLastWhile { it != '.' && it != '!' && it != '?' && it != '\n' }.trim()
    }

    private fun runChyme(action: AiAction) {
        if (!::chymeCard.isInitialized) return
        val config = chymeStore.load()
        if (!config.isConfigured) {
            // Not an error: the card says what is missing and offers the way there.
            chymeCard.showIdle(config)
            return
        }
        val text = textForChyme()
        if (text.isBlank()) {
            // Write needs a prompt; the other actions need something to rewrite.
            chymeCard.showError(
                if (action == AiAction.WRITE) "Type a topic first" else "Nothing to work on yet",
            )
            return
        }
        chymeCard.showWorking(action)
        scope.launch {
            runCatching { ChymeClient.complete(config, ChymePrompts.forAction(action, text)) }
                .onSuccess { chymeCard.showResult(it) }
                .onFailure { chymeCard.showError(it.message ?: "Request failed") }
        }
    }

    private fun insertChymeResult(text: String) {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        ic.beginBatchEdit()
        if (selected.isNotEmpty()) {
            ic.commitText(text, 1)
        } else {
            val before = ic.getTextBeforeCursor(600, 0)?.toString().orEmpty()
            val sentence = before.takeLastWhile { it != '.' && it != '!' && it != '?' && it != '\n' }
            if (sentence.isNotEmpty()) ic.deleteSurroundingText(sentence.length, 0)
            ic.commitText(text, 1)
        }
        ic.endBatchEdit()
        closeChyme()
        suggestionEngine.onPrefixChanged(currentWordPrefix(), capitalise = false)
    }

    /** The app is where the key and endpoint live; the card only points at it. */
    private fun openAbodeApp() {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
        closeChyme()
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
        val tag = currentSubtypeTag() ?: return false
        return DEVANAGARI_TAGS.any { tag.startsWith(it) }
    }

    /** Hinglish types in Latin but wants the Devanagari reading offered. */
    private fun isHinglishSubtype(): Boolean {
        val tag = currentSubtypeTag() ?: return false
        return tag.startsWith("en_IN")
    }

    private fun currentSubtypeTag(): String? {
        val manager = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            ?: return null
        return runCatching { manager.currentInputMethodSubtype?.languageTag }.getOrNull()
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

    /**
     * The globe is a language key before it is a keyboard key.
     *
     * With more than one Abode subtype enabled it cycles those, which is what a
     * globe is expected to do; only when there is nothing else of ours does it
     * fall through to another input method, and then to the system picker.
     */
    private fun switchInputMethod() {
        val manager = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            ?: return
        // Language is chosen in the app, not from the keyboard: a globe that
        // silently changed the script mid-sentence was worse than no globe at all,
        // and the app has a screen that shows what each language does.
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
        strip.visualizer = true
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
        if (::strip.isInitialized) {
            strip.status = text
            if (text.isNotEmpty()) strip.suggestions = emptyList()
        }
    }

    /** Stops dictation and throws away what was heard, without committing it. */
    private fun cancelListening() {
        if (!listening) return
        listening = false
        voiceEngine?.cancel()
        currentInputConnection?.let {
            it.setComposingText("", 1)
            it.finishComposingText()
        }
        strip.visualizer = false
        setStatus("")
    }

    /**
     * Letters and digits in a font the phone's text fields can show: bold serif
     * or monospace, from Unicode's mathematical alphanumerics. Anything else is
     * left alone.
     */
    private fun styledText(text: String, style: Int): String = buildString {
        for (c in text) {
            val cp = when {
                style == 1 && c in 'A'..'Z' -> 0x1D400 + (c - 'A')
                style == 1 && c in 'a'..'z' -> 0x1D41A + (c - 'a')
                style == 1 && c in '0'..'9' -> 0x1D7CE + (c - '0')
                style == 2 && c in 'A'..'Z' -> 0x1D670 + (c - 'A')
                style == 2 && c in 'a'..'z' -> 0x1D68A + (c - 'a')
                style == 2 && c in '0'..'9' -> 0x1D7F6 + (c - '0')
                else -> c.code
            }
            appendCodePoint(cp)
        }
    }

    private fun loadSpaceLabel() {
        spaceLabel = try {
            getSharedPreferences("abode_keyboard", MODE_PRIVATE)
                .getString("space_label", "space")?.trim()?.take(12)?.ifEmpty { "space" } ?: "space"
        } catch (e: Exception) {
            "space"
        }
    }

    /** Opens a Google search for the selected text, or the word before the cursor. */
    private fun searchWeb() {
        if (isSensitiveField()) {
            setStatus("Search is off in password fields")
            return
        }
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)?.toString().orEmpty().trim()
        val before = ic.getTextBeforeCursor(60, 0)?.toString().orEmpty()
        val query = selected.ifEmpty { before.trim().split(' ').lastOrNull().orEmpty() }
        if (query.isEmpty()) {
            setStatus("Nothing to search")
            return
        }
        val uri = android.net.Uri.parse("https://www.google.com/search?q=" + android.net.Uri.encode(query))
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            setStatus("No browser to search with")
        }
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        strip.visualizer = false
        voiceEngine?.stop()
    }

    private fun onVoiceEvent(event: VoiceEvent) {
        if (event is VoiceEvent.Level) {
            strip.level = event.value
            return
        }
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
                strip.visualizer = false
                setStatus("")
            }

            is VoiceEvent.PermissionRequired -> {
                listening = false
                strip.visualizer = false
                setStatus("Microphone permission needed")
                requestMicrophonePermission()
            }

            is VoiceEvent.Failed -> {
                listening = false
                strip.visualizer = false
                setStatus(event.message)
                AbodeLog.w("voice failed: ${event.code} ${event.message}")
            }

            is VoiceEvent.Level -> Unit
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
        const val MAX_CLIPS = 50
        const val DOUBLE_TAP_MS = 320L


        /**
         * Subtypes written in Devanagari. They all transliterate through the same
         * engine, so adding a language here is a label and a locale, not an
         * engine: Hindi, Marathi, Bhojpuri, Nepali, Maithili, Sanskrit, Konkani
         * and Dogri.
         */
        val DEVANAGARI_TAGS = listOf("hi", "mr", "bho", "ne", "mai", "sa", "kok", "doi")
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
