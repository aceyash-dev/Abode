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

    /**
     * The list ships gzipped: 400,000 words is 4.6 MB as text and 1.4 MB as a
     * gzip, and the compressed form is what a repository and an APK both want.
     * A plain file is still accepted, so an older build's asset keeps working.
     */
    suspend fun load(assetName: String = "words.txt.gz"): TrieDictionary =
        withContext(AbodeDispatchers.io) {
            val stream = runCatching {
                java.util.zip.GZIPInputStream(context.assets.open(assetName))
            }.getOrElse {
                context.assets.open("words.txt")
            }
            stream.bufferedReader().useLines { lines -> TrieDictionary.parse(lines) }
        }
}
