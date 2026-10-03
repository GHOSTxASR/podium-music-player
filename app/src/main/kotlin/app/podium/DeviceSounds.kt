package app.podium

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.SystemClock
import app.podium.core.interaction.Clicker

/**
 * The device's own sounds (D-26, D-32): the startup chord (res/raw/podium_boot.ogg) and the
 * Wheel's clicks (podium_click.ogg per detent, podium_press.ogg per press) — all original,
 * synthesized for Podium. They're UI sounds: system-sounds volume, quiet on silent or vibrate.
 * Loaded at startup so they're ready by the first boot.
 */
class DeviceSounds(context: Context) : Clicker {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    private val loaded = HashSet<Int>()
    private var pendingChord = false
    private val chord: Int
    private val click: Int
    private val press: Int
    private var lastTickAt = 0L

    init {
        pool.setOnLoadCompleteListener { _, id, status ->
            if (status != 0) return@setOnLoadCompleteListener
            synchronized(loaded) { loaded += id }
            if (id == chord && pendingChord) {
                pendingChord = false
                play(chord, CHORD_VOLUME)
            }
        }
        chord = pool.load(context, R.raw.podium_boot, 1)
        click = pool.load(context, R.raw.podium_click, 1)
        press = pool.load(context, R.raw.podium_press, 1)
    }

    fun playChord() {
        if (!audible()) return
        if (isLoaded(chord)) play(chord, CHORD_VOLUME) else pendingChord = true
    }

    /** A detent. Rate-limited: a fast spin is a purr, not a pile-up. */
    override fun tick() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTickAt < MIN_TICK_INTERVAL_MS) return
        lastTickAt = now
        if (audible() && isLoaded(click)) play(click, CLICK_VOLUME)
    }

    override fun press() {
        if (audible() && isLoaded(press)) play(press, PRESS_VOLUME)
    }

    private fun audible() = audio.ringerMode == AudioManager.RINGER_MODE_NORMAL

    private fun isLoaded(id: Int) = synchronized(loaded) { id in loaded }

    private fun play(id: Int, volume: Float) {
        pool.play(id, volume, volume, 1, 0, 1f)
    }

    private companion object {
        const val CHORD_VOLUME = 1f
        const val CLICK_VOLUME = 0.45f
        const val PRESS_VOLUME = 0.55f
        const val MIN_TICK_INTERVAL_MS = 24L
    }
}
