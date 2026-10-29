package dev.abode.keyboard

data class KeyboardLayout(val rows: List<List<Key>>) {

    val rowCount: Int get() = rows.size

    companion object {

        private fun letters(row: String, shifted: Boolean, alternates: Map<Char, List<String>>): List<Key> =
            row.map { c ->
                val label = if (shifted) c.uppercaseChar().toString() else c.toString()
                Key(label = label, output = label, alternates = alternates[c].orEmpty())
            }

        private val LETTER_ALTERNATES = mapOf(
            'q' to listOf("1", "~"),
            'w' to listOf("2", "€"),
            'e' to listOf("3", "é"),
            'r' to listOf("4", "₹"),
            't' to listOf("5", "™"),
            'y' to listOf("6", "¥"),
            'u' to listOf("7", "ú"),
            'i' to listOf("8", "í"),
            'o' to listOf("9", "ó"),
            'p' to listOf("0", "°"),
            'a' to listOf("@", "á"),
            's' to listOf("#", "ß"),
            'd' to listOf("$", "ð"),
            'f' to listOf("%", "£"),
            'g' to listOf("&", "€"),
            'h' to listOf("*", "ğ"),
            'j' to listOf("-", "ĵ"),
            'k' to listOf("+", "ķ"),
            'l' to listOf("(", "ł"),
            'z' to listOf(".", "ž"),
            'x' to listOf(",", "×"),
            'c' to listOf("?", "ç"),
            'v' to listOf("!", "✓"),
            'b' to listOf("'", "ß"),
            'n' to listOf("\"", "ñ"),
            'm' to listOf(")", "µ"),
        )

        private val DIGIT_ALTERNATES = mapOf(
            '1' to listOf("!", "¹"),
            '2' to listOf("@", "²"),
            '3' to listOf("#", "³"),
            '4' to listOf("$", "£"),
            '5' to listOf("%", "€"),
            '6' to listOf("^", "¥"),
            '7' to listOf("&", "₹"),
            '8' to listOf("*", "•"),
            '9' to listOf("(", "["),
            '0' to listOf(")", "]"),
        )

        /**
         * QWERTY with the number row on top, shift and delete on the third row,
         * and 123 / globe / space / mic / return along the bottom.
         */
        fun qwerty(shifted: Boolean = false, numberRow: Boolean = true): KeyboardLayout {
            val rows = mutableListOf<List<Key>>()

            if (numberRow) {
                rows += "1234567890".map { c ->
                    Key(
                        label = c.toString(),
                        output = c.toString(),
                        alternates = DIGIT_ALTERNATES[c].orEmpty(),
                    )
                }
            }

            rows += letters("qwertyuiop", shifted, LETTER_ALTERNATES)
            rows += letters("asdfghjkl", shifted, LETTER_ALTERNATES)
            rows += listOf(
                Key("⇧", KeyKind.SHIFT, widthWeight = 1.5f),
            ) + letters("zxcvbnm", shifted, LETTER_ALTERNATES) + listOf(
                Key("⌫", KeyKind.DELETE, widthWeight = 1.5f),
            )

            rows += listOf(
                Key("123", KeyKind.SYMBOLS, widthWeight = 1.4f),
                Key("🌐", KeyKind.GLOBE, widthWeight = 1.1f),
                Key("space", KeyKind.SPACE, output = " ", widthWeight = 4.2f),
                Key("🎤", KeyKind.VOICE, widthWeight = 1.2f),
                Key("return", KeyKind.ENTER, widthWeight = 1.9f),
            )

            return KeyboardLayout(rows)
        }

        /** The 123 page. */
        fun symbols(shifted: Boolean = false): KeyboardLayout {
            fun digits(row: String): List<Key> = row.map { c ->
                Key(
                    label = c.toString(),
                    output = c.toString(),
                    alternates = DIGIT_ALTERNATES[c].orEmpty(),
                )
            }

            return KeyboardLayout(
                listOf(
                    digits("1234567890"),
                    listOf("-", "/", ":", ";", "(", ")", "₹", "&", "@", "\"").map { Key(it) },
                    listOf(
                        Key("⇧", KeyKind.SHIFT, widthWeight = 1.5f),
                    ) + listOf("#", "+", "=", ".", ",", "?", "!").map { Key(it) } + listOf(
                        Key("⌫", KeyKind.DELETE, widthWeight = 1.5f),
                    ),
                    listOf(
                        Key("ABC", KeyKind.SYMBOLS, widthWeight = 1.4f),
                        Key("🌐", KeyKind.GLOBE, widthWeight = 1.1f),
                        Key("space", KeyKind.SPACE, output = " ", widthWeight = 4.2f),
                        Key("🎤", KeyKind.VOICE, widthWeight = 1.2f),
                        Key("return", KeyKind.ENTER, widthWeight = 1.9f),
                    ),
                ),
            )
        }
    }
}
