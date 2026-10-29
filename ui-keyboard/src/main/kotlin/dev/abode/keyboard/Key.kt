package dev.abode.keyboard

enum class KeyKind {
    CHARACTER,
    SPACE,
    ENTER,
    DELETE,
    SHIFT,
    SYMBOLS,
    GLOBE,
    VOICE,
    CLIPBOARD,
    AI,
}

/**
 * A single key. [widthWeight] is relative within its row, so a space bar is
 * 4.0 against a letter's 1.0 and the layout engine does the arithmetic.
 */
data class Key(
    val label: String,
    val kind: KeyKind = KeyKind.CHARACTER,
    val output: String = label,
    val widthWeight: Float = 1f,
)
