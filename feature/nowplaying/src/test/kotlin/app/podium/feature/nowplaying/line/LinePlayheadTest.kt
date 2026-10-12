package app.podium.feature.nowplaying.line

import app.podium.core.lyrics.Lyrics
import app.podium.core.lyrics.LyricsLine
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** From the target cursor to the cursor drawn: exact while playing, a retrace on a jump (§12.5). */
class LinePlayheadTest {

    @Test
    fun `while the music plays the drawing is exactly the playback position`() {
        val head = LinePlayhead()
        var now = 0L
        var target = 2.0
        repeat(200) {
            target += 0.004 // a frame of playback
            now += 16
            assertEquals(target, head.update(target, now, jumped = false, reduced = false))
            assertFalse(head.travelling)
        }
    }

    @Test
    fun `a rewind retraces the route back and lands exactly on the target`() {
        val head = LinePlayhead()
        head.reset(7.6)
        var now = 1_000L
        val shown = ArrayList<Double>()
        head.update(3.25, now, jumped = true, reduced = false)
        while (head.travelling) {
            shown += head.shown
            now += 16
            head.update(3.25, now, jumped = false, reduced = false)
        }
        assertEquals(3.25, head.shown)
        // Every step moves back along the composition: never forward, never past the target.
        val all = listOf(7.6) + shown + 3.25
        for (k in 1 until all.size) assertTrue(all[k] <= all[k - 1] && all[k] >= 3.25)
        assertTrue(shown.size in 10..60, "the retrace takes a moment, not an age (${shown.size} frames)")
    }

    @Test
    fun `seeking forward travels forward, pausing holds still, resuming continues without a jump`() {
        val head = LinePlayhead()
        head.reset(1.0)
        var now = 0L
        head.update(4.0, now, jumped = true, reduced = false)
        while (head.travelling) {
            now += 16
            val before = head.shown
            head.update(4.0, now, jumped = false, reduced = false)
            assertTrue(head.shown >= before)
        }
        // Paused: the target doesn't move, neither does the drawing.
        repeat(30) {
            now += 250
            assertEquals(4.0, head.update(4.0, now, jumped = false, reduced = false))
        }
        // Resumed: carries on from exactly there.
        now += 16
        assertEquals(4.004, head.update(4.004, now, jumped = false, reduced = false))
        assertFalse(head.travelling)
    }

    @Test
    fun `seeking to the same moment again and again settles on the same cursor`() {
        val c = LineComposition(
            LineScript.synced(Lyrics.Synced(listOf(LyricsLine(5_000, "once more"), LyricsLine(9_000, "with feeling"))), 30_000),
            LineComposition.seedOf("a|b"), 1.5f, ::glyphOrBox,
        )
        val head = LinePlayhead()
        head.reset(c.cursorAt(0))
        var now = 0L
        val settled = ArrayList<LineFrame>()
        for (seek in listOf(20_000L, 7_000L, 1_000L, 7_000L, 25_000L, 7_000L)) {
            head.update(c.cursorAt(seek), now, jumped = true, reduced = false)
            repeat(80) {
                now += 16
                head.update(c.cursorAt(seek), now, jumped = false, reduced = false)
            }
            if (seek == 7_000L) settled += LineFrame().also { c.frameAt(head.shown, false, it) }
        }
        assertEquals(3, settled.size)
        assertEquals(settled[0], settled[1])
        assertEquals(settled[0], settled[2])
    }

    @Test
    fun `a new song starts fresh, nothing travels over from the last`() {
        val head = LinePlayhead()
        head.reset(12.5)
        // The screen gives a new composition a new playhead; resetting is the same thing.
        head.reset(0.4)
        assertEquals(0.4, head.update(0.4, 0, jumped = false, reduced = false))
        assertFalse(head.travelling)
    }

    @Test
    fun `reduced motion jumps straight to the target`() {
        val head = LinePlayhead()
        head.reset(6.0)
        assertEquals(2.0, head.update(2.0, 0, jumped = true, reduced = true))
        assertFalse(head.travelling)
    }

    @Test
    fun `a large jump of the target counts as a seek even when the caller didn't see one`() {
        val head = LinePlayhead()
        head.reset(1.0)
        head.update(1.0 + LinePlayhead.JUMP_SECTIONS * 3, 0, jumped = false, reduced = false)
        assertTrue(head.travelling)
    }
}
