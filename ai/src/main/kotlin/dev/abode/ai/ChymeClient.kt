package dev.abode.ai

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Anything that stopped a Chyme request, with a message fit to show on the card. */
class ChymeException(message: String) : Exception(message)

/**
 * One request, one finished answer.
 *
 * Deliberately not streaming. The result lands on a small card above the keys
 * and is inserted when the user takes it; a half-written sentence arriving a
 * token at a time on a 40 dp strip is worse than a short wait.
 *
 * `HttpURLConnection` rather than OkHttp or Ktor: ADR-002 keeps the IME process
 * graph lean, and one POST with a JSON body does not need a client library.
 */
object ChymeClient {

    suspend fun complete(config: ChymeConfig, prompt: String): String =
        withContext(Dispatchers.IO) {
            if (!config.isConfigured) {
                throw ChymeException("No key, endpoint or model set")
            }
            val raw = post(config, body(config, prompt))
            val text = when (config.provider.wire) {
                ChymeWire.OPENAI -> JSONObject(raw)
                    .optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    .orEmpty()

                ChymeWire.ANTHROPIC -> JSONObject(raw)
                    .optJSONArray("content")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    .orEmpty()
            }.trim()

            if (text.isEmpty()) {
                // An error object that came back with a 200 is the common case
                // for a wrong model name, and "no text" alone would hide it.
                throw ChymeException("The endpoint returned no text: ${raw.take(200)}")
            }
            text
        }

    /**
     * The models the endpoint offers.
     *
     * Both wire formats answer on /models, which is what makes a model picker
     * possible without a hardcoded list that goes stale: OpenAI-compatible
     * endpoints return {data: [{id}]} and Anthropic returns {data: [{id}]} too.
     */
    suspend fun models(config: ChymeConfig): List<String> = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank()) throw ChymeException("No key set")
        // The version segment is not always /v1/: Gemini's OpenAI-compatible path
        // is /v1beta/openai/chat/completions, and a fixed "/v1/" lookup sent its
        // model request to /v1beta/openai/v1/models, which does not exist.
        val base = modelsBase(config.endpoint)
        val url = runCatching { URL("$base/v1/models") }.getOrElse {
            throw ChymeException("That endpoint is not a URL: ${config.endpoint}")
        }
        val connection = runCatching { url.openConnection() as HttpURLConnection }.getOrElse {
            throw ChymeException("Could not open the endpoint")
        }
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            when (config.provider.wire) {
                ChymeWire.OPENAI -> connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                ChymeWire.ANTHROPIC -> {
                    connection.setRequestProperty("x-api-key", config.apiKey)
                    connection.setRequestProperty("anthropic-version", "2023-06-01")
                }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw ChymeException("HTTP $code: ${text.take(160)}")
            val data = JSONObject(text).optJSONArray("data") ?: return@withContext emptyList()
            val ids = ArrayList<String>(data.length())
            for (i in 0 until data.length()) {
                val id = data.optJSONObject(i)?.optString("id").orEmpty()
                if (id.isNotBlank()) ids.add(id)
            }
            ids.sorted()
        } catch (e: IOException) {
            throw ChymeException(e.message ?: "Network error")
        } finally {
            connection.disconnect()
        }
    }

    /** The base URL a /models request hangs off, whatever the endpoint's version. */
    private fun modelsBase(endpoint: String): String {
        val trimmed = endpoint.trim().trimEnd('/')
        val version = Regex("/(v\\d+[a-z0-9]*)/[^/]+$").find(trimmed)
        return if (version != null) {
            trimmed.substring(0, version.range.first) + "/" + version.groupValues[1] + "/models"
        } else {
            trimmed.substringBeforeLast('/') + "/v1/models"
        }
    }

    private fun body(config: ChymeConfig, prompt: String): String {
        val messages = JSONArray().put(
            JSONObject().put("role", "user").put("content", prompt),
        )
        val json = JSONObject()
            .put("model", config.model)
            .put("messages", messages)
        if (config.provider.wire == ChymeWire.ANTHROPIC) {
            // Required by the Messages API; without it the request is rejected.
            json.put("max_tokens", 1024)
        }
        return json.toString()
    }

    private fun post(config: ChymeConfig, body: String): String {
        val url = runCatching { URL(config.endpoint.trim()) }.getOrElse {
            throw ChymeException("That endpoint is not a URL: ${config.endpoint}")
        }
        val connection = runCatching { url.openConnection() as HttpURLConnection }.getOrElse {
            throw ChymeException("Could not open the endpoint")
        }
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            when (config.provider.wire) {
                ChymeWire.OPENAI ->
                    connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")

                ChymeWire.ANTHROPIC -> {
                    connection.setRequestProperty("x-api-key", config.apiKey)
                    connection.setRequestProperty("anthropic-version", "2023-06-01")
                }
            }

            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw ChymeException("HTTP $code: ${text.take(200)}")
            }
            return text
        } catch (e: IOException) {
            throw ChymeException(e.message ?: "Network error")
        } finally {
            connection.disconnect()
        }
    }
}
