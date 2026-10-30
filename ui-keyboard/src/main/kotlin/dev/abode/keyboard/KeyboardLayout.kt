package dev.abode.keyboard

data class KeyboardLayout(val rows: List<List<Key>>) {

    val rowCount: Int get() = rows.size

    companion object {

        private fun letters(row: String, shifted: Boolean, alternates: Map<Char, List<String>>): List<Key> =
            row.map { c ->
                val label = if (shifted) c.uppercaseChar().toString() else c.toString()
                Key(label = label, output = label, alternates = alternates[c].orEmpty())
            }

        /**
         * Shift shows its state on the key itself: lit when a capital is queued,
         * and a filled arrow when caps lock is on, so the person never has to
         * guess why the next letter came out the wrong case.
         */
        private fun shiftKey(shifted: Boolean, capsLock: Boolean): Key = Key(
            label = if (capsLock) "⇪" else "⇧",
            kind = KeyKind.SHIFT,
            widthWeight = 1.5f,
            active = shifted,
        )

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
        fun qwerty(
            shifted: Boolean = false,
            numberRow: Boolean = true,
            bottomStrip: Boolean = true,
            capsLock: Boolean = false,
            enterLabel: String = "return",
        ): KeyboardLayout {
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
                shiftKey(shifted, capsLock),
            ) + letters("zxcvbnm", shifted, LETTER_ALTERNATES) + listOf(
                Key("⌫", KeyKind.DELETE, widthWeight = 1.5f),
            )

            rows += listOf(
                Key("123", KeyKind.SYMBOLS, widthWeight = 1.4f),
                Key("✎", KeyKind.EDIT, widthWeight = 1.2f),
                Key("space", KeyKind.SPACE, output = " ", widthWeight = 4.6f),
                Key(enterLabel, KeyKind.ENTER, widthWeight = 1.9f),
            )

            // The strip under the keys, as iOS has it: the two keys that are not
            // about typing sit away from the ones that are.
            if (bottomStrip) {
                rows += listOf(
                    Key("", KeyKind.GLOBE, widthWeight = 1f),
                    Key("", KeyKind.SPACER, widthWeight = 5f),
                    Key("", KeyKind.VOICE, widthWeight = 1f),
                )
            }

            return KeyboardLayout(rows)
        }

        /**
         * The editing toolbar: the actions the system's own selection menu
         * offers, on the keyboard where the cursor already is.
         */
        fun editing(): KeyboardLayout = KeyboardLayout(
            listOf(
                listOf(
                    Key("Select all", KeyKind.SELECT_ALL, widthWeight = 2.4f),
                    Key("Cut", KeyKind.CUT, widthWeight = 1.6f),
                    Key("Copy", KeyKind.COPY, widthWeight = 1.6f),
                    Key("Paste", KeyKind.PASTE, widthWeight = 1.6f),
                    Key("Undo", KeyKind.UNDO, widthWeight = 1.4f),
                    Key("Speak", KeyKind.SPEAK, widthWeight = 1.4f),
                    Key("Clip", KeyKind.CLIPBOARD, widthWeight = 1.4f),
                ),
                listOf(
                    Key("Done", KeyKind.EDIT, widthWeight = 2.4f),
                    Key("space", KeyKind.SPACE, output = " ", widthWeight = 3.2f),
                    Key("⌫", KeyKind.DELETE, widthWeight = 1.6f),
                    Key("return", KeyKind.ENTER, widthWeight = 1.9f),
                ),
            ),
        )

        /**
         * The numeric page: a calculator's worth of digits and the operators
         * people actually type into forms, with nothing else in the way.
         */
        fun numeric(): KeyboardLayout = KeyboardLayout(
            listOf(
                listOf("7", "8", "9").map { Key(it) } + listOf(
                    Key("÷", KeyKind.CHARACTER, output = "÷", widthWeight = 1f),
                    Key("⌫", KeyKind.DELETE, widthWeight = 1.4f),
                ),
                listOf("4", "5", "6").map { Key(it) } + listOf(
                    Key("×", KeyKind.CHARACTER, output = "×", widthWeight = 1f),
                    Key("(", KeyKind.CHARACTER, output = "(", widthWeight = 1.4f),
                ),
                listOf("1", "2", "3").map { Key(it) } + listOf(
                    Key("−", KeyKind.CHARACTER, output = "-", widthWeight = 1f),
                    Key(")", KeyKind.CHARACTER, output = ")", widthWeight = 1.4f),
                ),
                listOf(
                    Key("ABC", KeyKind.NUMERIC, widthWeight = 1.4f),
                    Key("0", KeyKind.CHARACTER, output = "0", widthWeight = 1.2f),
                    Key(",", KeyKind.CHARACTER, output = ",", widthWeight = 1f),
                    Key(".", KeyKind.CHARACTER, output = ".", widthWeight = 1f),
                    Key("+", KeyKind.CHARACTER, output = "+", widthWeight = 1f),
                    Key("=", KeyKind.CHARACTER, output = "=", widthWeight = 1f),
                    Key("return", KeyKind.ENTER, widthWeight = 1.8f),
                ),
            ),
        )

        /**
         * Recent clipboard entries, one per row.
         *
         * Rendered through the same key grid rather than a new view: the panel
         * inherits the touch handling, the theming and the height, and a clip is
         * committed by the same code path as a letter.
         */
        fun clipboard(entries: List<String>): KeyboardLayout {
            val rows = mutableListOf<List<Key>>()
            entries.take(MAX_CLIP_ROWS).forEach { entry ->
                val preview = entry.replace("\n", " ").trim().let {
                    if (it.length <= 34) it else it.take(33) + "\u2026"
                }
                rows += listOf(Key(preview, KeyKind.CHARACTER, output = entry))
            }
            rows += listOf(
                Key("Back", KeyKind.CLIPBOARD, widthWeight = 1.6f),
                Key("space", KeyKind.SPACE, output = " ", widthWeight = 3.2f),
                Key("return", KeyKind.ENTER, widthWeight = 1.9f),
            )
            return KeyboardLayout(rows)
        }

        private const val MAX_CLIP_ROWS = 6

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
                    listOf("%", "*", "~", "|", "\\", "<", ">", "©", "®", "°").map { Key(it) },
                    listOf(
                        shiftKey(shifted, capsLock = false),
                    ) + listOf("#", "+", "=", ".", ",", "?", "!").map { Key(it) } + listOf(
                        Key("⌫", KeyKind.DELETE, widthWeight = 1.5f),
                    ),
                    listOf(
                        Key("ABC", KeyKind.SYMBOLS, widthWeight = 1.2f),
                        Key("123", KeyKind.NUMERIC, widthWeight = 1.2f),
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
