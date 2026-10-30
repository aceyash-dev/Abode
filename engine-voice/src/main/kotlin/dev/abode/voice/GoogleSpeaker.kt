package dev.abode.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Text to speech, through Google's engine where it exists.
 *
 * The keyboard's use for this is narrow and deliberate: reading back the
 * sentence you are about to send, or the message you are replying to, so you can
 * hear the mistake before you commit it. It is not a screen reader — a keyboard
 * that talks over everything is a keyboard people mute.
 *
 * Initialisation is asynchronous and can fail; every entry point tolerates a
 * null engine rather than throwing, because a missing TTS engine must not take
 * the keyboard down with it.
 */
class GoogleSpeaker(private val context: Context) {

    private var engine: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    var speaking: Boolean = false
        private set

    fun prepare() {
        if (engine != null) return

        engine = runCatching { TextToSpeech(context, ::onInitialised, GOOGLE_TTS) }
            .getOrElse { TextToSpeech(context, ::onInitialised) }
    }

    private fun onInitialised(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        val tts = engine ?: return
        if (!ready) return

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                speaking = true
            }

            override fun onDone(utteranceId: String?) {
                speaking = false
            }

            @Deprecated("Deprecated in the platform, still called by some engines")
            override fun onError(utteranceId: String?) {
                speaking = false
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                speaking = false
            }
        })

        runCatching { tts.language = Locale.getDefault() }
        pending?.let { text ->
            pending = null
            speak(text)
        }
    }

    /** Speaks [text], or stops if it is already speaking. */
    fun speak(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        prepare()
        if (!ready) {
            // The engine is still starting; keep the last request, not a queue.
            pending = trimmed
            return
        }

        if (speaking) {
            stop()
            return
        }

        speaking = true
        engine?.speak(trimmed, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    fun stop() {
        speaking = false
        runCatching { engine?.stop() }
    }

    fun release() {
        speaking = false
        pending = null
        runCatching { engine?.shutdown() }
        engine = null
        ready = false
    }

    private companion object {
        /** Google's engine, by package. Falls back to the system default. */
        const val GOOGLE_TTS = "com.google.android.tts"
        const val UTTERANCE_ID = "abode-readback"
    }
}
