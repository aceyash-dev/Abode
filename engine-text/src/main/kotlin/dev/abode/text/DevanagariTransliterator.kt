package dev.abode.text

/**
 * Latin to Devanagari, the way people actually type Hindi on a Latin keyboard.
 *
 * This is transliteration, not translation and not a phonetic engine: "namaste"
 * becomes नमस्ते because the letters map, not because anything understood the
 * word. It is the input method every Indian keyboard ships, and it exists
 * because learning an 80-key Devanagari layout is a much bigger ask than
 * learning where the letters are on the keyboard you already type on.
 *
 * Known limits, deliberately not hidden: there is no schwa deletion, so
 * consonant clusters get a virama where Hindi would drop the vowel, and long
 * vowels have to be typed as doubled letters. It gets a word right when the
 * spelling is regular, which is most short words and fewer long ones.
 */
object DevanagariTransliterator {

    /** Consonants, longest key first so "kh" wins over "k". */
    private val consonants: List<Pair<String, String>> = listOf(
        "kh" to "ख", "gh" to "घ", "chh" to "छ", "ch" to "च", "jh" to "झ",
        "th" to "ठ", "dh" to "ढ", "ph" to "फ", "bh" to "भ", "sh" to "श",
        "ss" to "ष", "ng" to "ङ", "ny" to "ञ", "gn" to "ज्ञ",
        "k" to "क", "g" to "ग", "j" to "ज", "t" to "त", "d" to "द",
        "n" to "न", "p" to "प", "b" to "ब", "m" to "म", "y" to "य",
        "r" to "र", "l" to "ल", "v" to "व", "w" to "व", "s" to "स", "h" to "ह",
        "f" to "फ़", "z" to "ज़", "q" to "क़", "x" to "क्ष",
    )

    /** Standalone vowels, used when no consonant precedes them. */
    private val independentVowels: List<Pair<String, String>> = listOf(
        "aa" to "आ", "ai" to "ऐ", "au" to "औ", "ii" to "ई", "ee" to "ई",
        "oo" to "ऊ", "uu" to "ऊ", "ri" to "ऋ",
        "a" to "अ", "i" to "इ", "u" to "उ", "e" to "ए", "o" to "ओ",
    )

    /** Vowel signs, used after a consonant. Short 'a' is inherent, so it is empty. */
    private val matras: List<Pair<String, String>> = listOf(
        "aa" to "ा", "ai" to "ै", "au" to "ौ", "ii" to "ी", "ee" to "ी",
        "oo" to "ू", "uu" to "ू", "ri" to "ृ",
        "a" to "", "i" to "ि", "u" to "ु", "e" to "े", "o" to "ो",
    )

    private const val VIRAMA = "्"

    private fun match(source: String, index: Int, table: List<Pair<String, String>>): Pair<String, String>? {
        for ((latin, devanagari) in table) {
            if (source.startsWith(latin, index)) return latin to devanagari
        }
        return null
    }

    fun transliterate(source: String): String {
        if (source.isEmpty()) return source

        val out = StringBuilder(source.length * 2)
        var index = 0
        var afterConsonant = false

        // Lowercased once, not once per character: this runs on the typing path, so
        // a 40-letter word used to allocate 40 copies of itself. The length guard
        // matters more than the speed: a character whose lowercase is longer than
        // itself would shift every index after it and silently transliterate the
        // wrong letters.
        val lower = source.lowercase()
        if (lower.length != source.length) return source

        while (index < source.length) {
            val at = index

            val consonant = match(lower, at, consonants)
            if (consonant != null) {
                // Two consonants in a row need a virama: without it the first
                // would carry an inherent 'a' and the cluster would read wrong.
                if (afterConsonant) out.append(VIRAMA)
                out.append(consonant.second)
                afterConsonant = true
                index += consonant.first.length
                continue
            }

            val vowel = match(lower, at, independentVowels)
            if (vowel != null) {
                if (afterConsonant) {
                    out.append(match(lower, at, matras)?.second ?: "")
                } else {
                    out.append(vowel.second)
                }
                afterConsonant = false
                index += vowel.first.length
                continue
            }

            // Anything else — spaces, digits, punctuation — passes through and
            // breaks the cluster.
            out.append(source[at])
            afterConsonant = false
            index++
        }
        return out.toString()
    }
}
