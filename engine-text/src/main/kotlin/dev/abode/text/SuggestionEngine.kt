package dev.abode.text

import dev.abode.core.common.AbodeDispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Predictions for the strip above the keys, and the correction applied when a
 * word is finished.
 *
 * Two rules keep this off the critical path:
 *  1. Every keystroke cancels the previous lookup, so a slow lookup can never
 *     queue behind itself.
 *  2. Results land in a StateFlow, which conflates: if the UI misses an
 *     intermediate value it simply sees the latest one.
 *
 * The caller commits the character to the field first and asks for suggestions
 * second. Never the other way round.
 *
 * Autocorrect is deliberately NOT predictive. It runs once, when a word is
 * finished, and only when the word is not already in the dictionary — a
 * keyboard that rewrites words it already knows is worse than one that misses
 * a typo.
 */
class SuggestionEngine(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = AbodeDispatchers.prediction,
) {

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    private var dictionary: TrieDictionary? = null
    private var job: Job? = null

    fun setDictionary(dictionary: TrieDictionary) {
        this.dictionary = dictionary
    }

    fun onPrefixChanged(prefix: String, capitalise: Boolean = false) {
        job?.cancel()

        val dict = dictionary
        if (dict == null || prefix.isEmpty()) {
            _suggestions.value = emptyList()
            return
        }

        job = scope.launch(dispatcher) {
            _suggestions.value = suggestFor(dict, prefix, capitalise)
        }
    }

    fun clear() {
        job?.cancel()
        _suggestions.value = emptyList()
    }

    /**
     * Ranked completions.
     *
     * The word itself is offered first when the user has typed a complete word —
     * on a keyboard with no separate "keep what I typed" affordance, that is the
     * only way to accept your own spelling without hunting for it.
     */
    fun suggestFor(
        dictionary: TrieDictionary,
        prefix: String,
        capitalise: Boolean = false,
        limit: Int = 3,
    ): List<String> {
        if (prefix.isEmpty()) return emptyList()

        val lower = prefix.lowercase()
        val exact = dictionary.contains(lower)

        val completions = dictionary.suggestions(lower, limit + 1)
            .filter { it != lower }
            .map { if (capitalise) it.replaceFirstChar { c -> c.uppercaseChar() } else it }

        val results = ArrayList<String>(limit)
        if (exact) {
            results.add(if (capitalise) lower.replaceFirstChar { it.uppercaseChar() } else lower)
        }
        for (candidate in completions) {
            if (results.size >= limit) break
            if (candidate.lowercase() != lower || results.isEmpty()) results.add(candidate)
        }
        return results.take(limit)
    }

    /**
     * The correction for a finished word, or null to leave it alone.
     *
     * Bounded and synchronous by design: it runs on the input thread when a word
     * is committed, so it must not be allowed to become a job. The dictionary
     * filters by length and caps the scan, which keeps this in the low
     * milliseconds even on a large word list.
     */
    fun autocorrect(word: String, sensitivity: Int = 1): String? {
        val dict = dictionary ?: return null
        if (sensitivity <= 0) return null
        val minimumLength = if (sensitivity >= 2) 2 else 4
        if (word.length < minimumLength) return null
        if (word.all { !it.isLetter() }) return null
        if (dict.contains(word.lowercase())) return null

        val capitalised = word.first().isUpperCase()
        val nearest = dict.nearest(word.lowercase(), maxDistance = 1) ?: return null
        return if (capitalised) nearest.replaceFirstChar { it.uppercaseChar() } else nearest
    }

    fun isWord(word: String): Boolean = dictionary?.contains(word.lowercase()) ?: false
}
