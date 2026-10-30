package dev.abode.voice

import java.util.Locale
import kotlinx.coroutines.flow.SharedFlow

/**
 * Which recogniser is doing the work. The keyboard always prefers the cheapest
 * tier that can do the job and falls forward on failure.
 */
enum class VoiceTier {
    /** System SpeechRecognizer, on-device recogniser where available. Zero download. */
    SYSTEM,

    /** A local model (Whisper-class) downloaded only on capable devices. */
    ON_DEVICE_MODEL,

    /** Streaming cloud recognition. Accuracy tier for Hinglish and long dictation. */
    CLOUD,
}

sealed interface VoiceEvent {
    data object Ready : VoiceEvent
    data object PermissionRequired : VoiceEvent
    data class Partial(val text: String) : VoiceEvent
    data class Final(val text: String) : VoiceEvent
    data class Failed(val code: Int, val message: String) : VoiceEvent

    /** Microphone level, 0..1, for the visualiser. */
    data class Level(val value: Float) : VoiceEvent
}

interface VoiceEngine {
    val tier: VoiceTier
    val events: SharedFlow<VoiceEvent>

    fun isAvailable(): Boolean

    fun start(locale: Locale)

    fun stop()

    fun cancel()

    fun release()
}
