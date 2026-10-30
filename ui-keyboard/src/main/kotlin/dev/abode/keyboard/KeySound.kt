package dev.abode.keyboard

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import dev.abode.ui.keyboard.R

/**
 * The key press sound: one short clip, loaded once and played from a pool so
 * fast typing never waits on the decoder. Plays on the sonification stream, so
 * it follows the system's touch-sound setting rather than the media volume.
 */
object KeySound {
    private var pool: SoundPool? = null
    private var clickId = 0
    private var ready = false

    private fun ensure(context: Context) {
        if (pool != null) return
        val p = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
        p.setOnLoadCompleteListener { _, _, status -> ready = status == 0 }
        clickId = p.load(context.applicationContext, R.raw.key_press, 1)
        pool = p
    }

    fun play(context: Context) {
        ensure(context)
        if (ready) pool?.play(clickId, VOLUME, VOLUME, 1, 0, 1f)
    }

    private const val VOLUME = 0.6f
}
