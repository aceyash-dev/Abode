package dev.abode.ime

import android.content.Context
import dev.abode.text.TrieDictionary

/**
 * The words this person actually types.
 *
 * A dictionary of ten thousand words knows English; it does not know that this
 * person writes "Kling", "Chyme" or the name of their own street. Every word
 * committed with a space or a punctuation mark is counted, and the ones typed
 * more than once are added to the trie with a frequency high enough to be
 * offered next time.
 *
 * Counted in `SharedPreferences` rather than in the database: it is a small map
 * of word to number, it is read once when the keyboard starts, and a schema for
 * it would be more code than the feature.
 */
class LearnedWords(context: Context) {

    private val store = context.getSharedPreferences("learned_words", Context.MODE_PRIVATE)

    /** Word to how many times it has been typed. */
    private val counts: MutableMap<String, Int> =
        store.all.mapNotNull { (word, value) ->
            val count = (value as? Int) ?: return@mapNotNull null
            word to count
        }.toMap().toMutableMap()

    /**
     * Records a finished word, and returns true when it is worth inserting into
     * the trie — the first time it reaches two, so a single typo cannot become a
     * suggestion.
     */
    fun record(raw: String): Boolean {
        val word = raw.trim().lowercase()
        if (word.length < 2) return false
        if (word.any { !it.isLetter() && it != '\'' }) return false

        val next = (counts[word] ?: 0) + 1
        counts[word] = next
        if (next % 4 == 0 || next == 2) store.edit().putInt(word, next).apply()
        return next == 2
    }

    /** Puts everything learned so far into [dictionary]. */
    fun applyTo(dictionary: TrieDictionary) {
        for ((word, count) in counts) {
            if (count < 2) continue
            // Above the dictionary's floor, below the commonest English words:
            // a word somebody types often should beat an obscure one it shares a
            // prefix with, and should not beat "the".
            dictionary.insert(word, count * 20)
        }
    }

    fun clear() {
        counts.clear()
        store.edit().clear().apply()
    }
}
