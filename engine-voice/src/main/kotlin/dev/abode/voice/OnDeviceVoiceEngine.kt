package dev.abode.voice

import java.util.Locale
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Tier 1: a local Whisper-class model, downloaded only on capable devices.
 *
 * NOT IMPLEMENTED. This is the placeholder the download infrastructure and the
 * whisper.cpp / LiteRT-LM JNI binding will sit behind, so the caller never
 * learns which tier answered.
 *
 * Design notes that must survive implementation:
 *  - The model is never shipped in the bundle. It is downloaded on demand,
 *    checksum-verified and stored in noBackupFilesDir.
 *  - Expect roughly 2-4x slower than real time for a quantised small model on a
 *    mid-range phone. Design the UX as "speak, stop, watch it fill in", not as
 *    live streaming.
 *  - Free the model from memory as soon as the keyboard hides.
 */
class OnDeviceVoiceEngine(private val modelPath: String?) : VoiceEngine {

    override val tier: VoiceTier = VoiceTier.ON_DEVICE_MODEL

    private val _events = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<VoiceEvent> = _events.asSharedFlow()

    override fun isAvailable(): Boolean = modelPath != null

    override fun start(locale: Locale) {
        _events.tryEmit(VoiceEvent.Failed(-1, "On-device model tier is not implemented yet"))
    }

    override fun stop() = Unit

    override fun cancel() = Unit

    override fun release() = Unit
}
