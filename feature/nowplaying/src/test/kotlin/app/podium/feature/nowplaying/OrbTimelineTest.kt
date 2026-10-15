package app.podium.feature.nowplaying

import app.podium.core.lyrics.Lyrics
import app.podium.core.lyrics.LyricsLine
import app.podium.core.lyrics.LyricsWord
import app.podium.feature.nowplaying.OrbTimeline.Phase
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the orb comes (D-83): only in long stretches without words, by the lyrics' own times and the
 * playback position — never in a short gap, never on a guess, and always the same at the same moment.
 */
class OrbTimelineTest {

    /** A line with its words timed by the provider, ending at [end]. */
    private fun line(start: Long, text: String, end: Long): LyricsLine {
        val words = text.split(' ')
        val step = (end - start) / words.size
        return LyricsLine(start, text, endMs = end, words = words.mapIndexed { i, w -> LyricsWord(start + i * step, w) })
    }

    private val song = Lyrics.Synced(
        listOf(
            line(10_000, "When the night is quiet", 12_000),
            line(15_000, "and everything they say", 17_000), // 3 s gap before: brief
            line(18_500, "hold on", 19_500), // 1.5 s gap: brief
            line(40_000, "over the water", 42_000), // 20.5 s instrumental: the orb
            line(46_000, "under the bridge", 48_000), // 4 s gap: brief
        ),
    )

    private fun at(t: OrbTimeline, ms: Long) = OrbTimeline.State().also { t.at(ms.toDouble(), it) }

    @Test
    fun `short gaps keep the words, a long one brings the orb`() {
        val t = OrbTimeline.of(song, 60_000)
        for (ms in listOf(12_500L, 14_000L, 17_800L, 18_000L, 43_000L, 45_500L)) {
            val s = at(t, ms)
            assertEquals(Phase.BRIEF_GAP, s.phase, "at $ms")
            assertEquals(0f, s.amount)
        }
        for (ms in listOf(11_000L, 16_000L, 41_000L)) assertEquals(Phase.LYRICS, at(t, ms).phase, "at $ms")
        // The instrumental: the last line lingers, the camera pulls back, the orb holds, the camera goes in.
        val enter = 19_500 + OrbTimeline.LINGER_MS
        assertEquals(Phase.SUSTAINED, at(t, enter - 100).phase)
        assertEquals(Phase.ENTERING, at(t, enter + 100).phase)
        assertEquals(Phase.ORB, at(t, 30_000).phase)
        assertEquals(1f, at(t, 30_000).amount)
        assertEquals(Phase.EXITING, at(t, 40_000 - 100).phase)
        assertTrue(at(t, 40_000 - 100).amount in 0f..0.05f, "nearly through the hollow just before the line")
        assertEquals(Phase.LYRICS, at(t, 40_000).phase, "the next line starts exactly on its time")
    }

    @Test
    fun `the camera moves smoothly, out and back, never jumping`() {
        val t = OrbTimeline.of(song, 60_000)
        var last = 0f
        var worst = 0f
        for (ms in 19_000L..40_500L step 8) {
            val a = at(t, ms).amount
            worst = maxOf(worst, kotlin.math.abs(a - last))
            last = a
        }
        // Eased over 1.5–1.8 s: never more than about 1.5 % of the move in an 8 ms frame.
        assertTrue(worst < 0.015f, "largest step per frame: $worst")
    }

    @Test
    fun `the page shows the line to come from the time the orb holds`() {
        val t = OrbTimeline.of(song, 60_000)
        assertFalse(at(t, 21_500).showsNext, "still receding with the last line")
        assertTrue(at(t, 30_000).showsNext)
        assertTrue(at(t, 39_000).showsNext)
        assertFalse(at(t, 40_100).showsNext)
    }

    @Test
    fun `the same moment is always the same state, however it was reached`() {
        val t = OrbTimeline.of(song, 60_000)
        val forward = (0L..60_000L step 250).map { at(t, it).let { s -> s.phase to s.amount } }
        val backward = (60_000L downTo 0L step 250).map { at(t, it).let { s -> s.phase to s.amount } }.reversed()
        assertEquals(forward, backward)
        // Rebuilt from the same lyrics: the same.
        val again = OrbTimeline.of(song, 60_000)
        assertEquals(forward, (0L..60_000L step 250).map { at(again, it).let { s -> s.phase to s.amount } })
    }

    @Test
    fun `a long introduction and the end of the song can bring the orb too`() {
        val t = OrbTimeline.of(song, 90_000)
        assertEquals(Phase.ORB, at(t, 5_000).phase, "a 10 s introduction")
        assertEquals(Phase.EXITING, at(t, 9_500).phase)
        // After the last line, to the end: the orb stays, no camera move back.
        assertEquals(Phase.ORB, at(t, 85_000).phase)
        assertEquals(Phase.ORB, at(t, 89_999).phase)
    }

    @Test
    fun `a line ending on an estimate lingers longer before the camera moves`() {
        // Line times only: the words' pace and end are Podium's estimate.
        val lines = Lyrics.Synced(listOf(LyricsLine(10_000, "over the water"), LyricsLine(40_000, "under the bridge")))
        val t = OrbTimeline.of(lines, 60_000)
        val wordsEnd = app.podium.core.lyrics.WordTiming.end(lines.lines[0], 40_000)
        assertEquals(Phase.SUSTAINED, at(t, wordsEnd + OrbTimeline.LINGER_ESTIMATED_MS - 100).phase)
        assertEquals(Phase.ENTERING, at(t, wordsEnd + OrbTimeline.LINGER_ESTIMATED_MS + 100).phase)
    }

    @Test
    fun `lyrics timed by Podium, a gap with no room, and no words at all`() {
        // Podium's own pacing of plain lyrics: the gaps are guesses, so no orb.
        val paced = Lyrics.Synced(song.lines, estimated = true)
        val never = OrbTimeline.of(paced, 60_000)
        assertFalse(never.any)
        assertEquals(Phase.LYRICS, at(never, 30_000).phase)
        // Past the threshold but no room for the whole move after an estimated end's longer linger: the words stay.
        val tight = Lyrics.Synced(listOf(LyricsLine(10_000, "a b"), LyricsLine(17_260, "c d")))
        val end = app.podium.core.lyrics.WordTiming.end(tight.lines[0], 17_260)
        assertEquals(10_760L, end)
        val noRoom = OrbTimeline.of(tight, null)
        assertTrue((10_000L..17_260L step 100).all { at(noRoom, it).amount == 0f }, "a 6.5 s gap with a long linger and both moves: no orb")
        // Only gap lines: nothing to show, the orb throughout.
        val empty = Lyrics.Synced(listOf(LyricsLine(5_000, ""), LyricsLine(9_000, "")))
        assertTrue(OrbTimeline.of(empty, 60_000).always)
        assertEquals(Phase.ORB, at(OrbTimeline.ALWAYS, 0).phase)
    }

    @Test
    fun `it wakes a screen for every change`() {
        val t = OrbTimeline.of(song, 60_000)
        val enter = 19_500 + OrbTimeline.LINGER_MS
        assertEquals(enter, t.nextChangeMs(19_600))
        assertEquals(enter + OrbTimeline.ENTER_MS, t.nextChangeMs(enter))
        assertEquals(40_000 - OrbTimeline.EXIT_MS, t.nextChangeMs(enter + OrbTimeline.ENTER_MS))
    }

    @Test
    fun `the camera follows its moves exactly, shrugs off a blip and still answers a seek`() {
        val t = OrbTimeline.of(song, 60_000)
        val frame = 1000f / 90
        // Through a whole pull-back and the way back in: the capped camera is the timeline's own.
        var shown = at(t, 19_000).amount
        var ms = 19_000.0
        var worst = 0f
        while (ms < 40_200) {
            ms += frame
            val target = OrbTimeline.State().also { t.at(ms, it) }.amount
            shown = OrbTimeline.follow(shown, target, frame)
            worst = maxOf(worst, kotlin.math.abs(shown - target))
        }
        assertTrue(worst < 1e-4f, "its own moves pass unchanged: $worst")
        // At the orb, the position blips back into the words for two frames (a starting song's report).
        var held = 1f
        repeat(2) { held = OrbTimeline.follow(held, 0f, frame) }
        repeat(3) { held = OrbTimeline.follow(held, 1f, frame) }
        assertTrue(held > 0.94f, "a two-frame blip barely moves it: $held")
        // A real seek back into the words: there within 0.4 s.
        var seek = 1f
        var elapsed = 0f
        while (seek > 0f) {
            seek = OrbTimeline.follow(seek, 0f, frame)
            elapsed += frame
        }
        assertTrue(elapsed <= 400f + frame, "a seek lands in ${elapsed} ms")
    }
}
