package app.podium.player.api

/**
 * How the music sounds at a moment of the song (D-82, D-83), for the lyrics to move with it. Measured
 * in Podium's own player from the audio it plays, stamped with the song position each piece of audio
 * belongs to, so a reading lines up with what is heard (the player decodes ahead).
 *
 * Engine-neutral: the UI asks by playback position and never sees the player. Nothing is measured
 * unless something [listen]s, so the player does no analysis while no screen shows it.
 */
interface AudioPulse {
    /**
     * The bass level (RMS of the audio below about 120 Hz, 0 silence to 1 full scale) around
     * [positionMs] of the song playing now, or a negative number when nothing was measured there:
     * music another app plays, a position not yet decoded, audio Podium can't read.
     */
    fun bassAt(positionMs: Long): Float

    /**
     * The levels around [positionMs] (RMS, 0–1): [out] gets the whole signal at [WHOLE], the low end
     * (below about 120 Hz) at [LOW] and the top (above about 2.5 kHz) at [HIGH]. False, with [out]
     * untouched, when nothing was measured there.
     */
    fun levelsAt(positionMs: Long, out: FloatArray): Boolean {
        val bass = bassAt(positionMs)
        if (bass < 0f) return false
        out[WHOLE] = bass
        out[LOW] = bass
        out[HIGH] = 0f
        return true
    }

    /** Starts measuring until the returned handle is closed (several listeners may overlap). */
    fun listen(): AutoCloseable = AutoCloseable { }

    companion object {
        const val WHOLE = 0
        const val LOW = 1
        const val HIGH = 2

        /** Nothing measured, ever: lyrics stay still. */
        val None: AudioPulse = object : AudioPulse {
            override fun bassAt(positionMs: Long): Float = -1f
        }
    }
}
