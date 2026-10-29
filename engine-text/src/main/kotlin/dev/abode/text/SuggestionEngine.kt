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
 * Predictions for the strip above the keys.
 *
 * Two rules keep this off the critical path:
 *  1. Every keystroke cancels the previous lookup, so a slow lookup can never
 *     queue behind itself.
 *  2. Results land in a StateFlow, which conflates: if the UI misses an
 *     intermediate value it simply sees the latest one.
 *
 * The caller commits the character to the field first and asks for
 * suggestions second. Never the other way round.
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

    fun onPrefixChanged(prefix: String) {
        job?.cancel()

        val dict = dictionary
        if (dict == null || prefix.isEmpty()) {
            _suggestions.value = emptyList()
            return
        }

        job = scope.launch(dispatcher) {
            _suggestions.value = dict.suggestions(prefix.lowercase(), LIMIT)
        }
    }

    fun clear() {
        job?.cancel()
        _suggestions.value = emptyList()
    }

    private companion object {
        const val LIMIT = 3
    }
}
