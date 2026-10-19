package app.podium.player.api

/**
 * Settings ▸ Equaliser and Settings ▸ Crossfade (D-76, D-77, D-89): how Podium's own player shapes
 * the sound. All off by default; off leaves the audio exactly as decoded.
 */
data class AudioEffects(
    val equalizer: EqualizerSettings = EqualizerSettings(),
    /** Seconds the end of one song and the start of the next overlap; 0 is off. */
    val crossfadeSeconds: Int = 0,
    /** Where the music seems to be (D-89), after the equaliser; works with it on or off. */
    val sound: SoundEffect = SoundEffect.NONE,
) {
    companion object {
        /** The crossfade lengths offered, in seconds (0 is off). */
        val CROSSFADE_CHOICES = listOf(0, 2, 4, 6, 8, 12)
    }
}

/**
 * The equaliser (D-76): ten bands an octave apart, each boosted or cut by up to [MAX_GAIN_DB].
 * A [preset] sets every band; changing a band by hand makes it [EqualizerPreset.CUSTOM].
 */
data class EqualizerSettings(
    val enabled: Boolean = false,
    val preset: EqualizerPreset = EqualizerPreset.FLAT,
    /** dB per band of [BANDS_HZ], each within ±[MAX_GAIN_DB]. */
    val gainsDb: List<Float> = EqualizerPreset.FLAT.gainsDb,
) {
    init {
        require(gainsDb.size == BANDS_HZ.size) { "one gain per band" }
    }

    /** Whether it changes the sound at all. */
    val isAudible: Boolean get() = enabled && gainsDb.any { it != 0f }

    fun withPreset(preset: EqualizerPreset): EqualizerSettings =
        if (preset == EqualizerPreset.CUSTOM) copy(preset = preset) else copy(preset = preset, gainsDb = preset.gainsDb)

    /** One band moved by hand: the result is a custom curve. */
    fun withGain(band: Int, gainDb: Float): EqualizerSettings {
        val gains = gainsDb.toMutableList().also { it[band] = gainDb.coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB) }
        return copy(gainsDb = gains, preset = EqualizerPreset.matching(gains) ?: EqualizerPreset.CUSTOM)
    }

    companion object {
        /** Centre frequencies of the bands, in Hz. */
        val BANDS_HZ = listOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)
        const val MAX_GAIN_DB = 12f
        const val STEP_DB = 1f
    }
}

/** One way of placing the music (D-89), one at a time. Labels are sentence case (copy rules). */
enum class SoundEffect(val label: String) {
    NONE("None"),
    CAR("Car stereo"),
    HALL("3D hall"),
    ORBIT("8D"),
}

/** Ready-made curves (dB per band, 31 Hz to 16 kHz). Names are sentence case (copy rules). */
enum class EqualizerPreset(val label: String, val gainsDb: List<Float>) {
    FLAT("Flat", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
    BASS("Bass boost", listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f)),
    BASS_CUT("Less bass", listOf(-6f, -5f, -4f, -2f, 0f, 0f, 0f, 0f, 0f, 0f)),
    TREBLE("Treble boost", listOf(0f, 0f, 0f, 0f, 0f, 0f, 2f, 4f, 5f, 6f)),
    VOCAL("Vocal", listOf(-2f, -2f, -1f, 1f, 3f, 4f, 3f, 1f, 0f, -1f)),
    ROCK("Rock", listOf(4f, 3f, 2f, 0f, -1f, -1f, 1f, 3f, 4f, 4f)),
    POP("Pop", listOf(-1f, 1f, 2f, 3f, 2f, 0f, -1f, -1f, 1f, 2f)),
    JAZZ("Jazz", listOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f)),
    CLASSICAL("Classical", listOf(4f, 3f, 2f, 1f, -1f, -1f, 0f, 2f, 3f, 4f)),
    ELECTRONIC("Electronic", listOf(5f, 4f, 1f, 0f, -2f, 1f, 0f, 1f, 4f, 5f)),
    HIP_HOP("Hip-hop", listOf(5f, 4f, 1f, 3f, -1f, -1f, 1f, -1f, 2f, 3f)),
    ACOUSTIC("Acoustic", listOf(3f, 3f, 2f, 1f, 2f, 2f, 3f, 3f, 2f, 1f)),
    QUIET("Late night", listOf(3f, 2f, 1f, 0f, 0f, 0f, 0f, 1f, 2f, 2f)),
    CUSTOM("Custom", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f));

    companion object {
        /** The preset with exactly these gains, if there is one. */
        fun matching(gains: List<Float>): EqualizerPreset? = entries.firstOrNull { it != CUSTOM && it.gainsDb == gains }
    }
}

/**
 * When to crossfade (D-77), apart from the player so it can be tested. Never across songs that
 * run into each other on an album (gapless), never into the same song again, and never on songs
 * too short for the overlap.
 */
object CrossfadeRules {

    /** A song in the queue, as the rules need it. */
    data class Song(val albumId: String?, val trackNumber: Int?, val durationMs: Long?)

    fun applies(seconds: Int, current: Song, next: Song?, repeatOne: Boolean): Boolean {
        if (seconds <= 0 || next == null || repeatOne) return false
        val overlap = seconds * 1_000L
        // Each song must be long enough to give up the overlap and still be itself.
        if ((current.durationMs ?: 0) < overlap * 3 || (next.durationMs ?: 0) < overlap * 3) return false
        return !gapless(current, next)
    }

    /** The next track of the same album: they're mastered to run into each other. */
    fun gapless(current: Song, next: Song): Boolean {
        val album = current.albumId ?: return false
        if (album != next.albumId) return false
        val a = current.trackNumber ?: return false
        val b = next.trackNumber ?: return false
        return b == a + 1
    }

    /** Equal-power curves: the outgoing song's volume [p] of the way through the fade… */
    fun fadeOut(p: Float): Float = kotlin.math.cos(p.coerceIn(0f, 1f) * (Math.PI.toFloat() / 2))

    /** …and the incoming song's. Together they keep the loudness steady. */
    fun fadeIn(p: Float): Float = kotlin.math.sin(p.coerceIn(0f, 1f) * (Math.PI.toFloat() / 2))

    /** Two players are close enough to swap the same song between them unheard. */
    const val IN_STEP_MS = 25L

    /**
     * The speed for the silent second player while it catches up with the first, playing the same
     * song: [driftMs] ahead (positive) or behind. Silent, so the change in pitch is never heard.
     */
    fun catchUpSpeed(driftMs: Long): Float = (1f - driftMs / 1_000f).coerceIn(0.85f, 1.15f)
}
