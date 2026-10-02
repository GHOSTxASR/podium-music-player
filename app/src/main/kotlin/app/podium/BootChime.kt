package app.podium

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool

/**
 * The power-on chime (res/raw/podium_boot.ogg — an original, generated sound). It's a UI sound:
 * it plays on the system-sounds volume and stays quiet when the phone is on silent or vibrate.
 * Loaded at startup so it's ready by the first boot.
 */
class BootChime(context: Context) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val pool = SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    @Volatile private var loaded = false
    @Volatile private var pending = false
    private val soundId: Int

    init {
        pool.setOnLoadCompleteListener { _, id, status ->
            if (status != 0) return@setOnLoadCompleteListener
            loaded = true
            if (pending) {
                pending = false
                pool.play(id, 1f, 1f, 1, 0, 1f)
            }
        }
        soundId = pool.load(context, R.raw.podium_boot, 1)
    }

    fun play() {
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        if (loaded) pool.play(soundId, 1f, 1f, 1, 0, 1f) else pending = true
    }
}
