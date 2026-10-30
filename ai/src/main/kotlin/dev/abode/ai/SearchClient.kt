package dev.abode.ai

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One thing a search found: a title to show, a link to insert or open. */
data class SearchHit(val title: String, val url: String, val snippet: String)

/**
 * Search, answered inside the keyboard.
 *
 * DuckDuckGo's instant-answer endpoint is the one search that needs no key and
 * has no quota worth worrying about, which matters after the Giphy lesson. It is
 * not a full web index — it answers questions and offers related topics — so the
 * card always keeps a "Google" button for the cases it cannot answer.
 */
object SearchClient {

    suspend fun answers(query: String, limit: Int = 3): List<SearchHit> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1")
            val connection = runCatching { url.openConnection() as HttpURLConnection }.getOrElse {
                throw ChymeException("Could not reach search")
            }
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Accept", "application/json")
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw ChymeException("Search returned HTTP $code")
                parse(text, limit)
            } catch (e: IOException) {
                throw ChymeException(e.message ?: "Network error")
            } finally {
                connection.disconnect()
            }
        }

    private fun parse(raw: String, limit: Int): List<SearchHit> {
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val hits = ArrayList<SearchHit>(limit)

        // The abstract is the direct answer, when there is one.
        val abstractText = root.optString("AbstractText").orEmpty()
        val abstractUrl = root.optString("AbstractURL").orEmpty()
        if (abstractText.isNotBlank() && abstractUrl.isNotBlank()) {
            hits.add(SearchHit(root.optString("Heading").ifBlank { abstractUrl }, abstractUrl, abstractText))
        }

        val topics = root.optJSONArray("RelatedTopics") ?: return hits
        for (i in 0 until topics.length()) {
            if (hits.size >= limit) break
            val item = topics.optJSONObject(i) ?: continue
            // Related topics nest one level when the answer is a category.
            val entries = item.optJSONArray("Topics") ?: continue
            for (j in 0 until entries.length()) {
                if (hits.size >= limit) break
                val entry = entries.optJSONObject(j) ?: continue
                val url = entry.optString("FirstURL").orEmpty()
                val text = entry.optString("Text").orEmpty()
                if (url.isBlank()) continue
                hits.add(SearchHit(text.substringBefore(" - ").take(60).ifBlank { url }, url, text))
            }
        }
        for (i in 0 until topics.length()) {
            if (hits.size >= limit) break
            val item = topics.optJSONObject(i) ?: continue
            val url = item.optString("FirstURL").orEmpty()
            val text = item.optString("Text").orEmpty()
            if (url.isBlank()) continue
            if (hits.any { it.url == url }) continue
            hits.add(SearchHit(text.substringBefore(" - ").take(60).ifBlank { url }, url, text))
        }
        return hits.take(limit)
    }
}
