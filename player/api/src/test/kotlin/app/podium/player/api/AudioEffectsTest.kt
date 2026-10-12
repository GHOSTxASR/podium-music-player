package app.podium.player.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Equaliser settings (D-76) and crossfade's rules (D-77). */
class AudioEffectsTest {

    @Test
    fun `every preset has a gain for each band, within range`() {
        EqualizerPreset.entries.forEach { preset ->
            assertEquals(EqualizerSettings.BANDS_HZ.size, preset.gainsDb.size, preset.name)
            assertTrue(preset.gainsDb.all { it in -EqualizerSettings.MAX_GAIN_DB..EqualizerSettings.MAX_GAIN_DB }, preset.name)
        }
    }

    @Test
    fun `moving a band by hand makes the curve custom, and back to a preset's names it again`() {
        val bass = EqualizerSettings(enabled = true).withPreset(EqualizerPreset.BASS)
        val moved = bass.withGain(9, 3f)
        assertEquals(EqualizerPreset.CUSTOM, moved.preset)
        assertEquals(3f, moved.gainsDb[9])
        assertEquals(EqualizerPreset.BASS, moved.withGain(9, 0f).preset)
        assertEquals(EqualizerSettings.MAX_GAIN_DB, moved.withGain(0, 40f).gainsDb[0], "held within range")
    }

    @Test
    fun `an equaliser that is off or flat changes nothing`() {
        assertFalse(EqualizerSettings().isAudible)
        assertFalse(EqualizerSettings(enabled = true).isAudible)
        assertFalse(EqualizerSettings(enabled = false).withPreset(EqualizerPreset.ROCK).isAudible)
        assertTrue(EqualizerSettings(enabled = true).withPreset(EqualizerPreset.ROCK).isAudible)
    }

    private val song = CrossfadeRules.Song(albumId = "local|a", trackNumber = 3, durationMs = 200_000)

    @Test
    fun `songs crossfade only when it's on, there's a next one, and it isn't the same song again`() {
        val other = CrossfadeRules.Song("local|b", 1, 180_000)
        assertTrue(CrossfadeRules.applies(6, song, other, repeatOne = false))
        assertFalse(CrossfadeRules.applies(0, song, other, repeatOne = false))
        assertFalse(CrossfadeRules.applies(6, song, null, repeatOne = false))
        assertFalse(CrossfadeRules.applies(6, song, other, repeatOne = true))
    }

    @Test
    fun `an album's next track runs straight on, as mastered`() {
        assertFalse(CrossfadeRules.applies(6, song, song.copy(trackNumber = 4), repeatOne = false))
        assertTrue(CrossfadeRules.applies(6, song, song.copy(trackNumber = 7), repeatOne = false), "a jump within the album fades")
        assertTrue(CrossfadeRules.applies(6, song.copy(albumId = null), song.copy(albumId = null, trackNumber = 4), repeatOne = false))
    }

    @Test
    fun `songs too short for the overlap don't crossfade`() {
        val short = CrossfadeRules.Song("x|1", 1, 20_000)
        assertFalse(CrossfadeRules.applies(8, song, short, repeatOne = false))
        assertFalse(CrossfadeRules.applies(8, short, song, repeatOne = false))
        assertFalse(CrossfadeRules.applies(8, song, CrossfadeRules.Song("x|1", 1, null), repeatOne = false), "unknown length")
    }

    @Test
    fun `the fade keeps the loudness steady and the catch-up speed stays gentle`() {
        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { p ->
            val power = CrossfadeRules.fadeOut(p) * CrossfadeRules.fadeOut(p) + CrossfadeRules.fadeIn(p) * CrossfadeRules.fadeIn(p)
            assertEquals(1f, power, 1e-4f)
        }
        assertEquals(1f, CrossfadeRules.fadeOut(0f))
        assertEquals(1f, CrossfadeRules.fadeIn(1f), 1e-6f)
        assertEquals(0.9f, CrossfadeRules.catchUpSpeed(100), 1e-6f)
        assertEquals(1.15f, CrossfadeRules.catchUpSpeed(-900))
        assertEquals(0.85f, CrossfadeRules.catchUpSpeed(900))
    }
}
