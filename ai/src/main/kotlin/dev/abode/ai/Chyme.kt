package dev.abode.ai

import android.content.Context

/**
 * Chyme — the AI actions, pointed at a key and an endpoint the user owns.
 *
 * There is no Abode server and no bundled key. A provider is a *preset*: it
 * prefills the endpoint and the model, and both stay editable, because the
 * whole point of bringing your own key is that you may be pointing it at a
 * gateway, a proxy, or something running on your own laptop.
 *
 * Two wire formats cover the presets. OpenAI, Gemini and OpenRouter all speak
 * the chat-completions shape; Anthropic has its own. A custom endpoint is
 * assumed to be OpenAI-compatible, which is the de-facto standard.
 */

enum class ChymeWire { OPENAI, ANTHROPIC }

enum class ChymeProvider(
    val label: String,
    val endpoint: String,
    val model: String,
    val wire: ChymeWire,
) {
    OPENAI(
        "OpenAI",
        "https://api.openai.com/v1/chat/completions",
        "gpt-4o-mini",
        ChymeWire.OPENAI,
    ),
    ANTHROPIC(
        "Anthropic",
        "https://api.anthropic.com/v1/messages",
        "claude-3-5-sonnet-latest",
        ChymeWire.ANTHROPIC,
    ),
    GEMINI(
        "Gemini",
        "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
        "gemini-2.0-flash",
        ChymeWire.OPENAI,
    ),
    OPENROUTER(
        "OpenRouter",
        "https://openrouter.ai/api/v1/chat/completions",
        "openai/gpt-4o-mini",
        ChymeWire.OPENAI,
    ),
    CUSTOM(
        "Custom",
        "",
        "",
        ChymeWire.OPENAI,
    ),
    ;

    companion object {
        fun byId(id: String?): ChymeProvider =
            values().firstOrNull { it.name == id } ?: OPENAI
    }
}

data class ChymeConfig(
    val provider: ChymeProvider = ChymeProvider.OPENAI,
    val endpoint: String = ChymeProvider.OPENAI.endpoint,
    val model: String = ChymeProvider.OPENAI.model,
    val apiKey: String = "",
) {
    val isConfigured: Boolean
        get() = apiKey.isNotBlank() && endpoint.isNotBlank() && model.isNotBlank()
}

/**
 * Where the key lives.
 *
 * Plain preferences, not an encrypted store. Encrypting it properly means a
 * keystore-backed key and a recovery path for the day that key is wiped, and a
 * half-done version of that is worse than being honest: this is a personal API
 * key the user can revoke from the provider's dashboard, and the settings
 * screen says so.
 */
class ChymeStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("abode_chyme", Context.MODE_PRIVATE)

    fun load(): ChymeConfig {
        val provider = ChymeProvider.byId(prefs.getString(KEY_PROVIDER, null))
        return ChymeConfig(
            provider = provider,
            endpoint = prefs.getString(KEY_ENDPOINT, null) ?: provider.endpoint,
            model = prefs.getString(KEY_MODEL, null) ?: provider.model,
            apiKey = prefs.getString(KEY_KEY, null) ?: "",
        )
    }

    fun save(config: ChymeConfig) {
        prefs.edit()
            .putString(KEY_PROVIDER, config.provider.name)
            .putString(KEY_ENDPOINT, config.endpoint.trim())
            .putString(KEY_MODEL, config.model.trim())
            .putString(KEY_KEY, config.apiKey.trim())
            .apply()
    }

    private companion object {
        const val KEY_PROVIDER = "provider"
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_MODEL = "model"
        const val KEY_KEY = "api_key"
    }
}

/**
 * What each action asks for.
 *
 * Every prompt says "return only the text": the result is inserted straight
 * into the field, so a model that explains itself puts its explanation in the
 * user's message.
 */
object ChymePrompts {

    /**
     * [language] is the name of the language to translate into — the device's own,
     * supplied by the caller. Translating to a fixed pair meant a Hindi phone got
     * English back and an English phone got Hindi.
     */
    fun forAction(action: AiAction, text: String, language: String = "English"): String = when (action) {
        AiAction.REWRITE_FORMAL ->
            "Rewrite the text below in a more formal register, keeping its meaning. " +
                "Return only the rewritten text.\n\n$text"

        AiAction.REWRITE_CASUAL ->
            "Rewrite the text below in a more casual, conversational register, keeping " +
                "its meaning. Return only the rewritten text.\n\n$text"

        AiAction.SHORTER ->
            "Shorten the text below without losing what it says. Return only the " +
                "shortened text.\n\n$text"

        AiAction.FIX ->
            "Correct the spelling, grammar and punctuation of the text below. Change " +
                "nothing else. Return only the corrected text.\n\n$text"

        AiAction.TRANSLATE ->
            "Translate the text below into $language. If it is already in $language, " +
                "leave it as it is. Return only the translation.\n\n$text"

        AiAction.REPLY ->
            "Write a short, natural reply to the message below. Return only the " +
                "reply.\n\n$text"

        AiAction.WRITE ->
            "Write the text that the note below asks for. If the note is a topic, " +
                "write a short, natural message about it; if it is a half-written " +
                "sentence, finish it. Return only the text to insert.\n\n$text"
    }
}
