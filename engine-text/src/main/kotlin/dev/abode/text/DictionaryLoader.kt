package dev.abode.text

import android.content.Context
import dev.abode.core.common.AbodeDispatchers
import kotlinx.coroutines.withContext

/**
 * Loads the bundled word list off the main thread.
 *
 * Call this from a coroutine started in the IME service, never from
 * Application.onCreate or onCreateInputView: reading assets there is exactly
 * the kind of work that turns a 60 ms keyboard into a 400 ms one.
 */
class DictionaryLoader(private val context: Context) {

    suspend fun load(assetName: String = "words.txt"): TrieDictionary =
        withContext(AbodeDispatchers.io) {
            context.assets.open(assetName).bufferedReader().useLines { lines ->
                TrieDictionary.parse(lines)
            }
        }
}
