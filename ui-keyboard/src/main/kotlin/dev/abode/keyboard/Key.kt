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
