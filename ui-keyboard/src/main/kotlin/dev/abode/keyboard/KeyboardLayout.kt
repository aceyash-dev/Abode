package dev.abode.keyboard

/**
 * A keyboard layout is just rows of weighted keys. Everything geometric is
 * derived in KeyGridView from this, so adding a layout is a data change and
 * never a rendering change.
 */
data class KeyboardLayout(val rows: List<List<Key>>) {

    val rowCount: Int get() = rows.size

    companion object {

        fun qwerty(shifted: Boolean = false): KeyboardLayout {
            fun letters(row: String): List<Key> = row.map { c ->
                val label = if (shifted) c.uppercaseChar().toString() else c.toString()
                Key(label = label, output = label)
            }

            return KeyboardLayout(
                listOf(
                    letters("qwertyuiop"),
                    letters("asdfghjkl"),
                    listOf(
                        Key("SHIFT", KeyKind.SHIFT, widthWeight = 1.6f),
                    ) + letters("zxcvbnm") + listOf(
                        Key("DEL", KeyKind.DELETE, widthWeight = 1.6f),
                    ),
                    listOf(
                        Key("?123", KeyKind.SYMBOLS, widthWeight = 1.5f),
                        Key("MIC", KeyKind.VOICE, widthWeight = 1.2f),
                        Key("", KeyKind.SPACE, output = " ", widthWeight = 4f),
                        Key("CLIP", KeyKind.CLIPBOARD, widthWeight = 1.2f),
                        Key("ENTER", KeyKind.ENTER, widthWeight = 1.8f),
                    ),
                ),
            )
        }
    }
}
