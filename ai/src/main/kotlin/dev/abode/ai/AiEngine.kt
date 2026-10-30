package dev.abode.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

enum class AiAction(val label: String) {
    REWRITE_FORMAL("More formal"),
    REWRITE_CASUAL("More casual"),
    SHORTER("Shorter"),
    FIX("Fix grammar"),
    TRANSLATE("Translate"),
    REPLY("Reply"),

    /** Composes new text from what is in the field, rather than rewriting it. */
    WRITE("Write"),
}

/**
 * One interface, two providers.
 *
 * The on-device provider (ML Kit GenAI over Gemini Nano) only permits inference
 * while the app is the top foreground app, and an IME window does not qualify.
 * That is why every AI action is launched from a small bottom-sheet Activity
 * rather than run inside the service: it satisfies the platform, and it gives
 * the user somewhere to review a draft before it lands in their text field.
 */
interface AiEngine {
    fun capabilities(): Set<AiAction>

    /** Streaming: the sheet renders tokens as they arrive. */
    fun run(action: AiAction, input: String): Flow<String>
}

class NoOpAiEngine : AiEngine {
    override fun capabilities(): Set<AiAction> = emptySet()
    override fun run(action: AiAction, input: String): Flow<String> = emptyFlow()
}
