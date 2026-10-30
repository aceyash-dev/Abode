package dev.abode.ai

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One GIF: what to show, what to insert, and the still to draw in the grid. */
data class GifResult(val title: String, val url: String, val thumbUrl: String)

/**
 * Giphy search, for the GIF tab on the emoji page.
 *
 * Inserts a link rather than the image: an input connection can only carry text,
 * and putting the GIF itself into a message needs the app on the other end to
 * fetch and render it. The media URL is the better link of the two Giphy returns
 * — it points at the file, so most chat apps preview it inline, where the page
 * URL would only unfurl.
 *
 * The key is compiled in from a repository secret. It is not in the source, and
 * it is not in this file's history.
 */
object GiphyClient {

    /**
     * How many GIFs one search asks for.
     *
     * One request per search, not per keystroke: the key is a quota, and a
     * keyboard that searched on every letter would spend a day's allowance on
     * one word. Twelve fills four rows of the grid and costs one call.
     */
    const val GIFS_PER_SEARCH = 12

    /**
     * The tabs above the GIF grid: a glyph, and the query behind it.
     *
     * A null query is trending, which is what the panel opens on — a GIF picker
     * that starts empty and waits for a word is a picker nobody opens twice.
     */
    val CATEGORIES: List<Pair<String, String?>> = listOf(
        "\uD83D\uDD25" to null,
        "\uD83D\uDE02" to "reactions",
        "\uD83D\uDC36" to "animals",
        "\u2764\uFE0F" to "love",
        "\uD83C\uDF54" to "food",
        "\uD83C\uDFAC" to "movies",
        "\u26BD" to "sports",
        "\u2728" to "wow",
    )

    suspend fun search(query: String, limit: Int = GIFS_PER_SEARCH): List<GifResult> =
        withContext(Dispatchers.IO) {
            val key = BuildConfig.GIPHY_API_KEY
            if (key.isBlank()) {
                throw ChymeException("No Giphy key in this build")
            }
            if (query.isBlank()) return@withContext emptyList()

            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL(
                "https://api.giphy.com/v1/gifs/search" +
                    "?api_key=$key&q=$encoded&limit=$limit&rating=pg-13&lang=en",
            )
            val connection = runCatching { url.openConnection() as HttpURLConnection }.getOrElse {
                throw ChymeException("Could not reach Giphy")
            }
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    throw ChymeException("Giphy returned HTTP $code")
                }
                parse(text)
            } catch (e: IOException) {
                throw ChymeException(e.message ?: "Network error")
            } finally {
                connection.disconnect()
            }
        }

    /** What Giphy is showing everyone, for the panel's first tab. One request. */
    suspend fun trending(limit: Int = GIFS_PER_SEARCH): List<GifResult> = withContext(Dispatchers.IO) {
        val key = BuildConfig.GIPHY_API_KEY
        if (key.isBlank()) throw ChymeException("No Giphy key in this build")
        val url = URL(
            "https://api.giphy.com/v1/gifs/trending" +
                "?api_key=$key&limit=$limit&rating=pg-13",
        )
        val connection = runCatching { url.openConnection() as HttpURLConnection }.getOrElse {
            throw ChymeException("Could not reach Giphy")
        }
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw ChymeException("Giphy returned HTTP $code")
            parse(text)
        } catch (e: IOException) {
            throw ChymeException(e.message ?: "Network error")
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(raw: String): List<GifResult> {
        val data = runCatching { JSONObject(raw).optJSONArray("data") }.getOrNull()
            ?: return emptyList()
        val results = ArrayList<GifResult>(data.length())
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val title = item.optString("title").ifBlank { item.optString("slug") }
            val images = item.optJSONObject("images")
            val url = images?.optJSONObject("original")?.optString("url").orEmpty()
                .ifBlank { item.optString("url") }
            // The still is a few KB where the GIF is megabytes: the grid draws the
            // still, and only the link is inserted.
            val thumb = images?.optJSONObject("fixed_height")?.optString("url").orEmpty()
                .ifBlank { images?.optJSONObject("downsized")?.optString("url").orEmpty() }
                .ifBlank { url }
            if (url.isBlank()) continue
            results.add(GifResult(title = title.ifBlank { "GIF" }, url = url, thumbUrl = thumb))
        }
        return results
    }
}
