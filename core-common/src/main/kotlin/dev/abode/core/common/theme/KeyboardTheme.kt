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
    // The default is the dark keyboard people already know: grey letter keys on a
    // near-black tray, with the return key and the shift accent in system blue.
    val background: Int = 0xFF212226.toInt(),
    val keyBackground: Int = 0xFF636366.toInt(),
    val keyPressed: Int = 0xFF8E8E93.toInt(),
    val keyLabel: Int = 0xFFFFFFFF.toInt(),
    val popupBackground: Int = 0xFF48484A.toInt(),
    val accent: Int = 0xFF0A84FF.toInt(),
    /** Absolute path to a font file the user imported, or null for the system face. */
    val fontPath: String? = null,
    val cornerRadiusDp: Float = 12f,
    val keyGapDp: Float = 6f,
    val rowHeightDp: Float = 52f,
    val keyShape: KeyShape = KeyShape.ROUNDED,
    /**
     * 0 corrects nothing, 1 corrects words of four letters or more, 2 also takes
     * two-letter words. People have strong opinions about this, and the ones who
     * turn autocorrect off are usually reacting to it firing too early.
     */
    val autocorrectSensitivity: Int = 1,
    val haptics: Boolean = true,
    val keySound: Boolean = true,
    val doubleSpacePeriod: Boolean = true,
    /**
     * The one colour the person chooses. The six colour roles above are derived
     * from it by [fromSeed]; editing a role directly is still possible through
     * presets, but a seed change always rebuilds the palette.
     */
    val seedColor: Int = 0xFF0A84FF.toInt(),
    /** Dark keyboard (light keys on a dark tray) or light. Drives [fromSeed]. */
    val isDark: Boolean = true,
) {
    companion object {

        /**
         * Builds the colour roles from one seed, in the spirit of Material 3's
         * tonal palettes: surfaces sit at low tones in dark mode and high tones in
         * light mode, and the accent keeps the seed's hue at a tone that reads
         * against the keys. This is a close approximation of the Material tonal
         * system (HSL here, HCT in Material Color Utilities), not a bit-for-bit match.
         */
        fun fromSeed(seed: Int, dark: Boolean, base: KeyboardTheme = KeyboardTheme()): KeyboardTheme {
            val hsl = toHsl(seed)
            val h = hsl[0]
            val s = hsl[1]
            val l = hsl[2]
            return if (dark) {
                base.copy(
                    seedColor = seed,
                    isDark = true,
                    background = fromHsl(h, minOf(s, 0.22f), 0.11f),
                    keyBackground = fromHsl(h, minOf(s, 0.20f), 0.36f),
                    keyPressed = fromHsl(h, minOf(s, 0.25f), 0.52f),
                    keyLabel = 0xFFFFFFFF.toInt(),
                    popupBackground = fromHsl(h, minOf(s, 0.20f), 0.30f),
                    accent = fromHsl(h, maxOf(s, 0.55f), l.coerceIn(0.50f, 0.66f)),
                )
            } else {
                base.copy(
                    seedColor = seed,
                    isDark = false,
                    background = fromHsl(h, minOf(s, 0.12f), 0.93f),
                    keyBackground = fromHsl(h, minOf(s, 0.08f), 0.995f),
                    keyPressed = fromHsl(h, minOf(s, 0.20f), 0.84f),
                    keyLabel = fromHsl(h, 0.12f, 0.10f),
                    popupBackground = fromHsl(h, minOf(s, 0.06f), 1.0f),
                    accent = fromHsl(h, maxOf(s, 0.55f), l.coerceIn(0.35f, 0.50f)),
                )
            }
        }

        /** Hue in degrees, saturation and lightness in 0..1, from an ARGB colour. */
        private fun toHsl(argb: Int): FloatArray {
            val r = ((argb shr 16) and 0xFF) / 255f
            val g = ((argb shr 8) and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val delta = max - min
            val l = (max + min) / 2f
            val s = if (delta == 0f) 0f else delta / (1f - kotlin.math.abs(2f * l - 1f))
            val h = when {
                delta == 0f -> 0f
                max == r -> 60f * (((g - b) / delta) % 6f)
                max == g -> 60f * ((b - r) / delta + 2f)
                else -> 60f * ((r - g) / delta + 4f)
            }.let { if (it < 0f) it + 360f else it }
            return floatArrayOf(h, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
        }

        private fun fromHsl(hue: Float, saturation: Float, lightness: Float): Int {
            val c = (1f - kotlin.math.abs(2f * lightness - 1f)) * saturation
            val x = c * (1f - kotlin.math.abs((hue / 60f) % 2f - 1f))
            val m = lightness - c / 2f
            val (r1, g1, b1) = when {
                hue < 60f -> Triple(c, x, 0f)
                hue < 120f -> Triple(x, c, 0f)
                hue < 180f -> Triple(0f, c, x)
                hue < 240f -> Triple(0f, x, c)
                hue < 300f -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }
            fun channel(v: Float): Int = ((v + m) * 255f).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (channel(r1) shl 16) or (channel(g1) shl 8) or channel(b1)
        }

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

    val Light = KeyboardTheme(
        id = "light",
        name = "Light",
        isDark = false,
        background = 0xFFEDEDEF.toInt(),
        keyBackground = 0xFFF7F7F8.toInt(),
        keyPressed = 0xFFE8641A.toInt(),
        keyLabel = 0xFF1A1A1A.toInt(),
        popupBackground = 0xFFFFFFFF.toInt(),
        accent = 0xFFE8641A.toInt(),
    )

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
        isDark = false,
        background = 0xFFF1E9DC.toInt(),
        keyBackground = 0xFFFFFBF4.toInt(),
        keyPressed = 0xFFB44A12.toInt(),
        keyLabel = 0xFF2B2620.toInt(),
        popupBackground = 0xFFFFFFFF.toInt(),
        accent = 0xFFB44A12.toInt(),
    )

    val all: List<KeyboardTheme> = listOf(Abode, Light, Midnight, Paper)

    fun byId(id: String): KeyboardTheme = all.firstOrNull { it.id == id } ?: Abode
}
