package app.podium.player.api

/**
 * How hard the music's low end is hitting at a moment of the song (D-82), for the lyrics to move with
 * it. Measured in Podium's own player from the audio it plays, stamped with the song position each
 * piece of audio belongs to, so a reading lines up with what is heard (the player decodes ahead).
 *
 * Engine-neutral: the UI asks by playback position and never sees the player.
 */
interface AudioPulse {
    /**
     * The bass level (RMS of the audio below about 120 Hz, 0 silence to 1 full scale) around
     * [positionMs] of the song playing now, or a negative number when nothing was measured there:
     * music another app plays, a position not yet decoded, audio Podium can't read.
     */
    fun bassAt(positionMs: Long): Float

    companion object {
        /** Nothing measured, ever: lyrics stay still. */
        val None: AudioPulse = object : AudioPulse {
            override fun bassAt(positionMs: Long): Float = -1f
        }
    }
}
