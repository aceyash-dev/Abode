package dev.abode.keyboard

enum class KeyKind {
    CHARACTER,
    SPACE,
    ENTER,
    DELETE,
    SHIFT,
    SYMBOLS,
    NUMERIC,
    GLOBE,
    VOICE,
    CLIPBOARD,
    AI,
    EDIT,

    // The editing toolbar. These act on the field through InputConnection's
    // context-menu actions rather than reimplementing selection and clipboard
    // handling, so they behave like the app's own menu does.
    SELECT_ALL,
    CUT,
    COPY,
    PASTE,
    UNDO,
    SPEAK,

    /** Switches the grid to the emoji page, and back to letters. */
    EMOJI,

    /** Moves between symbol pages; [Key.output] is the page number. */
    SYMBOLS_PAGE,

    /** Selects an emoji category; [Key.output] is its index. */
    EMOJI_TAB,

    /** Moves to the next page of the current emoji category, wrapping. */
    EMOJI_MORE,

    /** Searches the web for the selected text, or the word before the cursor. */
    SEARCH_WEB,

    /** Selects the word before the cursor. */
    SELECT_WORD,

    /** Opens the Chyme card. */
    CHYME,

    /** Turns serif output on or off: letters you type become bold serif characters. */
    SERIF_TOGGLE,

    /** Opens emoji search: letters filter the emoji, a tap on one inserts it. */
    EMOJI_SEARCH,

    /** Opens Giphy search: letters are the query, a tap inserts a GIF link. */
    GIF,

    /** A label row in the clipboard panel: "Links", "Text". Draws, does nothing. */
    CLIP_HEADER,

    /** One screenshot in the clipboard panel. [Key.output] is its file path. */
    CLIP_IMAGE,

    /** Opens and closes the selection group in the tool row. */
    EDIT_GROUP,

    /** One result in the GIF grid. [Key.output] is the media URL to insert. */
    GIF_RESULT,

    /** Draws nothing and does nothing: the gap in the iOS-style bottom strip. */
    SPACER,
}

/**
 * A single key.
 *
 * [widthWeight] is relative within its row, so a space bar is 4.0 against a
 * letter's 1.0 and the layout engine does the arithmetic.
 *
 * [alternates] are what a long press offers. Long-pressing 'e' and sliding to
 * '3' should never require a mode switch, which is the whole reason the number
 * row can stay a convenience rather than a requirement.
 */
data class Key(
    val label: String,
    val kind: KeyKind = KeyKind.CHARACTER,
    val output: String = label,
    val widthWeight: Float = 1f,
    val alternates: List<String> = emptyList(),
    /** A toggle that is currently on (shift, caps lock). Drawn in the accent colour. */
    val active: Boolean = false,
    /** A small second symbol drawn in the corner, tapped with the same key. Set only while shift is off. */
    val secondary: String? = null,
) {
    val hasAlternates: Boolean get() = alternates.isNotEmpty()

    /** The same key, retargeted at one of its long-press alternates. */
    fun withOutput(value: String): Key = copy(label = value, output = value)
}

/**
 * A layout is rows of weighted keys. Everything geometric is derived in
 * KeyGridView from this, so adding a layout is a data change and never a
 * rendering change.
 */
