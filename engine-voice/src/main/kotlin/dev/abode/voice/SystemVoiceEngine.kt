package dev.abode.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Tier 0: the system recogniser.
 *
 * Must be driven from the main thread. Create it when the keyboard becomes
 * visible and keep it; creating one per mic tap costs several hundred
 * milliseconds before the user sees anything at all.
 *
 * Partial results are emitted as they arrive so the caller can write them into
 * the composing region. Editing a draft beats waiting for a block of text.
 */
class SystemVoiceEngine(private val context: Context) : VoiceEngine {

    override val tier: VoiceTier = VoiceTier.SYSTEM

    private val _events = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<VoiceEvent> = _events.asSharedFlow()

    private var recognizer: SpeechRecognizer? = null
    private var pendingLocale: Locale? = null
    private var preferOffline = true

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _events.tryEmit(VoiceEvent.Ready)
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            // Speech sits roughly between -2 and 10 dB; map that onto 0..1.
            _events.tryEmit(VoiceEvent.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)))
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onError(error: Int) {
            // A recogniser that errored can be left in a state where every later
            // startListening is refused, so it is discarded rather than reused.
            release()

            // 12 and 13 mean there is no on-device model for this language.
            // Retrying once without the offline preference is the difference
            // between "Recognition failed (12)" and a transcript.
            val locale = pendingLocale
            if ((error == ERROR_LANGUAGE_NOT_SUPPORTED || error == ERROR_LANGUAGE_UNAVAILABLE) &&
                preferOffline && locale != null
            ) {
                preferOffline = false
                start(locale)
                return
            }

            _events.tryEmit(VoiceEvent.Failed(error, describe(error)))
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            _events.tryEmit(VoiceEvent.Final(text))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotEmpty()) _events.tryEmit(VoiceEvent.Partial(text))
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    /**
     * Google's own recognition service, when it is installed.
     *
     * Asking for it by component rather than taking whatever the system defaults
     * to is the difference between dictating with Google and dictating with an
     * OEM service that may not have a model for the language at all. If the
     * component is absent this falls back to the default recogniser.
     */
    private fun createRecognizer(): SpeechRecognizer {
        val google = ComponentName(GOOGLE_APP, GOOGLE_RECOGNISER)
        val present = runCatching {
            context.packageManager.resolveService(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).setComponent(google),
                0,
            ) != null
        }.getOrDefault(false)

        if (present) {
            runCatching { SpeechRecognizer.createSpeechRecognizer(context, google) }
                .onSuccess { return it }
        }
        return SpeechRecognizer.createSpeechRecognizer(context)
    }

    override fun isAvailable(): Boolean =
        runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    override fun start(locale: Locale) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            _events.tryEmit(VoiceEvent.PermissionRequired)
            return
        }

        if (!isAvailable()) {
            _events.tryEmit(VoiceEvent.Failed(ERROR_UNAVAILABLE, "No system recogniser on this device"))
            return
        }

        val active = recognizer ?: createRecognizer().also {
            it.setRecognitionListener(listener)
            recognizer = it
        }

        pendingLocale = locale
        active.startListening(buildIntent(locale, preferOffline = preferOffline))
    }

    override fun stop() {
        recognizer?.stopListening()
    }

    override fun cancel() {
        recognizer?.cancel()
    }

    override fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun buildIntent(locale: Locale, preferOffline: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Ask for offline first; the recogniser ignores it where unsupported.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
        }

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        SpeechRecognizer.ERROR_CLIENT -> "Client side error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied"
        SpeechRecognizer.ERROR_NETWORK -> "Network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognised"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recogniser busy"
        SpeechRecognizer.ERROR_SERVER -> "Server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
        11 -> "Server disconnected"
        // 12 is what a device returns when the recogniser has no offline model
        // for the language. Retrying without the offline preference hands the
        // request to Google's online recogniser, which does have one.
        12 -> "No offline model for this language"
        13 -> "Language unavailable"
        14 -> "Cannot check language support"
        else -> "Recognition failed ($error)"
    }

    private companion object {
        const val ERROR_UNAVAILABLE = -100
        const val GOOGLE_APP = "com.google.android.googlequicksearchbox"
        const val GOOGLE_RECOGNISER =
            "com.google.android.voicesearch.serviceapi.GoogleRecognitionService"
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}
