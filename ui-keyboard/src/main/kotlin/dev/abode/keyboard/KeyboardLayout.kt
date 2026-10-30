package dev.abode.keyboard

data class KeyboardLayout(val rows: List<List<Key>>) {

    val rowCount: Int get() = rows.size

    companion object {

        /**
         * Keys are labelled in capitals, as the system keyboards are, and the
         * output follows the shift state: a lower-case letter is typed unless
         * shift is on.
         */
        private fun letters(row: String, shifted: Boolean, alternates: Map<Char, List<String>>): List<Key> =
            row.map { c ->
                val output = if (shifted) c.uppercaseChar().toString() else c.toString()
                Key(label = c.uppercaseChar().toString(), output = output, alternates = alternates[c].orEmpty())
            }

        /**
         * Shift shows its state on the key itself: lit when a capital is queued,
         * and a filled arrow when caps lock is on, so the person never has to
         * guess why the next letter came out the wrong case.
         */
        /** Label of the shift key while caps lock is on. KeyGridView draws the key from it. */
        const val CAPS_LOCK_LABEL = "⇪"

        private fun shiftKey(shifted: Boolean, capsLock: Boolean): Key = Key(
            label = if (capsLock) CAPS_LOCK_LABEL else "⇧",
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

        /**
         * What a long press offers on the symbol pages.
         *
         * The same idea as the letter row's alternates: the symbol you want is
         * usually the neighbour of the one you can see, and reaching for the
         * other page to get it costs two taps and a mode switch.
         */
        private val SYMBOL_ALTERNATES = mapOf(
            ',' to listOf(";", "\u2026"),
            '.' to listOf(":", "\u2026"),
            '?' to listOf("\u00BF", "\u203D"),
            '!' to listOf("\u00A1", "\u203C"),
            '\'' to listOf("\"", "`"),
            '-' to listOf("\u2013", "\u2014"),
            '/' to listOf("\\", "\u00F7"),
            ':' to listOf(";", "\u00B7"),
            ';' to listOf(":", "\u2026"),
            '(' to listOf("[", "{"),
            ')' to listOf("]", "}"),
            '&' to listOf("\u00A7", "\u00B6"),
            '@' to listOf("\u00A9", "\u00AE"),
            '"' to listOf("'", "\u00AB"),
            '#' to listOf("\u2116", "\u2248"),
            '%' to listOf("\u2030", "\u00B0"),
            '^' to listOf("~", "\u00AC"),
            '*' to listOf("\u00D7", "\u2022"),
            '+' to listOf("\u00B1", "\u2211"),
            '[' to listOf("(", "{"),
            ']' to listOf(")", "}"),
            '{' to listOf("[", "("),
            '}' to listOf("]", ")"),
            '_' to listOf("-", "\u203F"),
            '\\' to listOf("/", "|"),
            '|' to listOf("\\", "\u00A6"),
            '~' to listOf("\u2248", "\u02DC"),
            '<' to listOf("\u00AB", "\u2039"),
            '>' to listOf("\u00BB", "\u203A"),
            '\u20AC' to listOf("\u00A3", "\u00A5"),
            '\u00A3' to listOf("\u20AC", "\u00A5"),
            '\u00A5' to listOf("\u20AC", "\u00A3"),
            '\u2022' to listOf("\u00B7", "\u25E6"),
        )

        /** A symbol key, with whatever a long press should offer for it. */
        private fun symbol(c: String): Key =
            Key(c, alternates = SYMBOL_ALTERNATES[c.firstOrNull()].orEmpty())

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
         * The letter page laid out as the Apple keyboard is: ten letters, nine
         * indented by half a key on each side, then shift and delete on the third
         * row. The bottom row is 123, emoji, space and return, with globe, editing
         * tools and the microphone underneath.
         *
         * The number row is off by default; numbers live on the 123 page, as they do
         * on the system keyboard, so the letter page stays calm.
         */
        fun qwerty(
            shifted: Boolean = false,
            numberRow: Boolean = false,
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
            rows += indent(letters("asdfghjkl", shifted, LETTER_ALTERNATES))
            rows += listOf(
                shiftKey(shifted, capsLock),
            ) + letters("zxcvbnm", shifted, LETTER_ALTERNATES) + listOf(
                Key("\u232B", KeyKind.DELETE, widthWeight = 1.5f),
            )

            // Comma and full stop either side of the space bar, as on the system
            // keyboards. The small "/" and "₹" only show while shift is off; with
            // shift on the keys are plain so the capitals are not crowded.
            rows += listOf(
                Key("123", KeyKind.SYMBOLS, widthWeight = 1.4f),
                Key("\u263A", KeyKind.EMOJI, widthWeight = 1.1f),
                Key(",", KeyKind.CHARACTER, output = ",", widthWeight = 1f, secondary = if (shifted) null else "/"),
                Key("space", KeyKind.SPACE, output = " ", widthWeight = 3.6f),
                Key(".", KeyKind.CHARACTER, output = ".", widthWeight = 1f, secondary = if (shifted) null else "\u20B9"),
                Key(enterLabel, KeyKind.ENTER, widthWeight = 1.9f),
            )

            if (bottomStrip) {
                rows += listOf(
                    Key("\uD83C\uDF10", KeyKind.GLOBE, widthWeight = 1f),
                    Key("", KeyKind.SPACER, widthWeight = 4f),
                    Key("", KeyKind.VOICE, widthWeight = 1f),
                )
            }

            return KeyboardLayout(rows)
        }

        /**
         * Half a key of space on each end of a row, so it sits between the row
         * above and below it the way the Apple layout does.
         */
        private fun indent(row: List<Key>): List<Key> =
            listOf(Key("", KeyKind.SPACER, widthWeight = 0.5f)) + row +
                listOf(Key("", KeyKind.SPACER, widthWeight = 0.5f))

        /** Emoji per page: four rows of nine. */
        private const val EMOJI_PER_ROW = 9
        private const val EMOJI_ROWS = 4
        const val EMOJI_PER_PAGE = EMOJI_PER_ROW * EMOJI_ROWS

        /** How many pages a category needs. Always at least one. */
        fun emojiPageCount(category: Int, renders: (String) -> Boolean = { true }): Int {
            val tab = EmojiPalette.tabs.getOrNull(category)
            val items = tab?.emoji.orEmpty().count(renders)
            val perPage = if (tab?.name == EmojiPalette.KAOMOJI_NAME) KAOMOJI_PER_PAGE else EMOJI_PER_PAGE
            return ((items + perPage - 1) / perPage).coerceAtLeast(1)
        }

        /** Kaomoji are long, so one per row, six rows to a page. */
        private const val KAOMOJI_PER_PAGE = 6

        /**
         * The emoji page as one scrolling column: the search row, then every glyph
         * in the category, then the tabs and the bottom row.
         *
         * The keyboard scrolls the middle rows itself, so this returns the whole
         * category rather than a page of it. The three rows the person needs to
         * reach at any moment — search, categories, and the way back to letters —
         * are the ones the view pins.
         */
        fun emojiScroll(category: Int, renders: (String) -> Boolean = { true }): KeyboardLayout {
            val catalog = EmojiPalette.tabs
            val current = category.coerceIn(0, catalog.lastIndex)
            val isKaomoji = catalog[current].name == EmojiPalette.KAOMOJI_NAME
            val items = catalog[current].emoji.filter(renders)

            val rows = mutableListOf<List<Key>>()
            rows += listOf(Key("Search emoji", KeyKind.EMOJI_SEARCH, widthWeight = 1f))
            if (isKaomoji) {
                // One kaomoji per row: they are words, not glyphs, and two to a row
                // would shrink both until neither could be read.
                items.forEach { text ->
                    rows += listOf(Key(text, KeyKind.CHARACTER, output = text, widthWeight = 1f))
                }
            } else {
                items.chunked(EMOJI_PER_ROW).forEach { row ->
                    rows += row.map { glyph -> Key(glyph, KeyKind.CHARACTER, output = glyph) }
                }
            }

            rows += catalog.mapIndexed { index, entry ->
                Key(
                    label = entry.tabGlyph,
                    kind = KeyKind.EMOJI_TAB,
                    output = index.toString(),
                    widthWeight = 1f,
                    active = index == current,
                )
            }

            rows += listOf(
                Key("ABC", KeyKind.EMOJI, widthWeight = 1.4f),
                Key("GIF", KeyKind.GIF, widthWeight = 1.1f),
                Key("space", KeyKind.SPACE, output = " ", widthWeight = 3.5f),
                Key("\u232B", KeyKind.DELETE, widthWeight = 1.2f),
                Key("return", KeyKind.ENTER, widthWeight = 1.8f),
            )
            return KeyboardLayout(rows)
        }

        /**
         * The emoji page: one page of a category, a row of category tabs, and a
         * bottom row to get back to letters. Category and page come from the
         * keyboard's state, so this stays a pure function of its arguments.
         */
        fun emoji(category: Int, page: Int, renders: (String) -> Boolean = { true }): KeyboardLayout {
            val catalog = EmojiPalette.tabs
            val current = category.coerceIn(0, catalog.lastIndex)
            val isKaomoji = catalog[current].name == EmojiPalette.KAOMOJI_NAME
            val items = catalog[current].emoji.filter(renders)
            val pageIndex = page.coerceIn(0, emojiPageCount(current, renders) - 1)
            val perPage = if (isKaomoji) KAOMOJI_PER_PAGE else EMOJI_PER_PAGE
            val visible = items.drop(pageIndex * perPage).take(perPage)

            val rows = mutableListOf<List<Key>>()
            // The search bar is the top row, so finding an emoji is one tap away.
            rows += listOf(Key("Search emoji", KeyKind.EMOJI_SEARCH, widthWeight = 1f))
            if (isKaomoji) {
                visible.forEach { text -> rows += listOf(Key(text, KeyKind.CHARACTER, output = text, widthWeight = 1f)) }
            } else {
                visible.chunked(EMOJI_PER_ROW).forEach { row ->
                    rows += row.map { glyph -> Key(glyph, KeyKind.CHARACTER, output = glyph) }
                }
            }

            // The current category is drawn lit, the way the shift key shows its
            // state. Unlit, the tab row reads as another row of emoji rather than
            // as the thing that switches categories.
            rows += catalog.mapIndexed { index, category ->
                Key(
                    label = category.tabGlyph,
                    kind = KeyKind.EMOJI_TAB,
                    output = index.toString(),
                    widthWeight = 1f,
                    active = index == current,
                )
            }

            rows += listOf(
                Key("ABC", KeyKind.EMOJI, widthWeight = 1.4f),
                Key("\u203A", KeyKind.EMOJI_MORE, widthWeight = 1f),
                Key("space", KeyKind.SPACE, output = " ", widthWeight = 4.6f),
                Key("\u232B", KeyKind.DELETE, widthWeight = 1.2f),
                Key("return", KeyKind.ENTER, widthWeight = 1.8f),
            )
            return KeyboardLayout(rows)
        }

        /**
         * The editing toolbar: the actions the system's own selection menu
         * offers, on the keyboard where the cursor already is.
         */
        /**
         * The tools panel. The top row is the text toolbar: the same five actions
         * the system offers on a selection. Below it, the keyboard's own tools.
         */
        fun editing(serifOn: Boolean = false, fontLabel: String = "Device"): KeyboardLayout = KeyboardLayout(
            listOf(
                listOf(
                    Key("Select", KeyKind.SELECT_WORD, widthWeight = 1f),
                    Key("Select all", KeyKind.SELECT_ALL, widthWeight = 1.2f),
                    Key("Copy", KeyKind.COPY, widthWeight = 1f),
                    Key("Cut", KeyKind.CUT, widthWeight = 1f),
                    Key("Paste", KeyKind.PASTE, widthWeight = 1f),
                ),
                listOf(
                    Key("Undo", KeyKind.UNDO, widthWeight = 1f),
                    Key("Speak", KeyKind.SPEAK, widthWeight = 1f),
                    Key("Google", KeyKind.SEARCH_WEB, widthWeight = 1f),
                    Key("Clip", KeyKind.CLIPBOARD, widthWeight = 1f),
                ),
                listOf(
                    Key("Chyme", KeyKind.CHYME, widthWeight = 1f),
                    Key("Serif", KeyKind.SERIF_TOGGLE, widthWeight = 1f, active = serifOn),
                    Key("Font: $fontLabel", KeyKind.FONT_CYCLE, widthWeight = 1.2f),
                    Key("\u232B", KeyKind.DELETE, widthWeight = 1f),
                    Key("Done", KeyKind.EDIT, widthWeight = 1f),
                ),
                listOf(
                    Key("space", KeyKind.SPACE, output = " ", widthWeight = 3f),
                    Key("return", KeyKind.ENTER, widthWeight = 1.6f),
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
        fun symbols(page: Int = 1): KeyboardLayout {
            fun digits(row: String): List<Key> = row.map { c ->
                Key(
                    label = c.toString(),
                    output = c.toString(),
                    alternates = DIGIT_ALTERNATES[c].orEmpty(),
                )
            }

            // Two pages, as on the system keyboard: the first is the everyday
            // punctuation, the second has room for brackets and maths. The page
            // switch is the key in the lower-left of row three.
            val punctuation = listOf(".", ",", "?", "!", "'").map { symbol(it) }
            val rows: List<List<Key>> = if (page == 2) {
                listOf(
                    listOf("[", "]", "{", "}", "#", "%", "^", "*", "+").map { symbol(it) },
                    listOf("_", "\\", "|", "~", "<", ">", "€", "£", "¥", "•").map { symbol(it) },
                    listOf(
                        Key("123", KeyKind.SYMBOLS_PAGE, output = "1", widthWeight = 1.5f),
                    ) + punctuation + listOf(Key("⌫", KeyKind.DELETE, widthWeight = 1.5f)),
                )
            } else {
                listOf(
                    digits("1234567890"),
                    listOf("-", "/", ":", ";", "(", ")", "₹", "&", "@", "\"").map { symbol(it) },
                    listOf(
                        Key("#+=", KeyKind.SYMBOLS_PAGE, output = "2", widthWeight = 1.5f),
                    ) + punctuation + listOf(Key("⌫", KeyKind.DELETE, widthWeight = 1.5f)),
                )
            }

            return KeyboardLayout(
                rows + listOf(
                    listOf(
                        Key("ABC", KeyKind.SYMBOLS, widthWeight = 1.2f),
                        Key("123", KeyKind.NUMERIC, widthWeight = 1.2f),
                        Key("🌐", KeyKind.GLOBE, widthWeight = 1.1f),
                        Key("☺", KeyKind.EMOJI, widthWeight = 1.1f),
                        Key("space", KeyKind.SPACE, output = " ", widthWeight = 3.4f),
                        Key("🎤", KeyKind.VOICE, widthWeight = 1.2f),
                        Key("return", KeyKind.ENTER, widthWeight = 1.9f),
                    ),
                ),
            )
        }
    }
}
