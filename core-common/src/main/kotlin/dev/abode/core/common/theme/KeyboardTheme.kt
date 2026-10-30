package dev.abode.core.common.theme

/**
 * Everything about how the keyboard looks.
 *
 * Colours are ARGB ints so the model stays pure data and can be persisted,
 * compared and unit-tested without touching Android graphics classes.
 */
/**
 * How a key is drawn. Hexagons are not decoration: they tile without gaps, so
 * the finger lands on a shape whose centre is unambiguous, which is the idea
 * Typewise built a keyboard on.
 */
enum class KeyShape { ROUNDED, HEXAGON }

data class KeyboardTheme(
    val id: String = "abode",
    val name: String = "Abode",
    val background: Int = 0xFFEDEDEF.toInt(),
    val keyBackground: Int = 0xFFF7F7F8.toInt(),
    val keyPressed: Int = 0xFFE8641A.toInt(),
    val keyLabel: Int = 0xFF1A1A1A.toInt(),
    val popupBackground: Int = 0xFFFFFFFF.toInt(),
    val accent: Int = 0xFFE8641A.toInt(),
    /** Absolute path to a font file the user imported, or null for the system face. */
    val fontPath: String? = null,
    val cornerRadiusDp: Float = 12f,
    val keyGapDp: Float = 6f,
    val rowHeightDp: Float = 52f,
    val keyShape: KeyShape = KeyShape.HEXAGON,
    /**
     * 0 corrects nothing, 1 corrects words of four letters or more, 2 also takes
     * two-letter words. People have strong opinions about this, and the ones who
     * turn autocorrect off are usually reacting to it firing too early.
     */
    val autocorrectSensitivity: Int = 1,
    val haptics: Boolean = true,
    val keySound: Boolean = true,
    val doubleSpacePeriod: Boolean = true,
) {
    companion object {
        /** Role -> human label, in the order the editor shows them. */
        val COLOR_ROLES: List<Pair<String, (KeyboardTheme) -> Int>> = listOf(
            "Background" to { t: KeyboardTheme -> t.background },
            "Key" to { t: KeyboardTheme -> t.keyBackground },
            "Key pressed" to { t: KeyboardTheme -> t.keyPressed },
            "Label" to { t: KeyboardTheme -> t.keyLabel },
            "Popup" to { t: KeyboardTheme -> t.popupBackground },
            "Accent" to { t: KeyboardTheme -> t.accent },
        )

        fun withColor(theme: KeyboardTheme, role: String, color: Int): KeyboardTheme = when (role) {
            "Background" -> theme.copy(background = color)
            "Key" -> theme.copy(keyBackground = color)
            "Key pressed" -> theme.copy(keyPressed = color)
            "Label" -> theme.copy(keyLabel = color)
            "Popup" -> theme.copy(popupBackground = color)
            "Accent" -> theme.copy(accent = color)
            else -> theme
        }

        fun colorOf(theme: KeyboardTheme, role: String): Int = when (role) {
            "Background" -> theme.background
            "Key" -> theme.keyBackground
            "Key pressed" -> theme.keyPressed
            "Label" -> theme.keyLabel
            "Popup" -> theme.popupBackground
            "Accent" -> theme.accent
            else -> theme.keyBackground
        }
    }
}

object ThemePresets {

    val Abode = KeyboardTheme()

    val Midnight = KeyboardTheme(
        id = "midnight",
        name = "Midnight",
        background = 0xFF141416.toInt(),
        keyBackground = 0xFF2C2C2E.toInt(),
        keyPressed = 0xFFE8641A.toInt(),
        keyLabel = 0xFFFFFFFF.toInt(),
        popupBackground = 0xFF3A3A3C.toInt(),
    )

    val Paper = KeyboardTheme(
        id = "paper",
        name = "Paper",
        background = 0xFFF1E9DC.toInt(),
        keyBackground = 0xFFFFFBF4.toInt(),
        keyPressed = 0xFFB44A12.toInt(),
        keyLabel = 0xFF2B2620.toInt(),
        popupBackground = 0xFFFFFFFF.toInt(),
        accent = 0xFFB44A12.toInt(),
    )

    val all: List<KeyboardTheme> = listOf(Abode, Midnight, Paper)

    fun byId(id: String): KeyboardTheme = all.firstOrNull { it.id == id } ?: Abode
}
