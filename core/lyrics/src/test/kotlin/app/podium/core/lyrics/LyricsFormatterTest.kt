package app.podium.core.lyrics

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Lyrics in a shape the screen can follow (D-50): good times kept, bad ones repaired, plain ones paced. */
class LyricsFormatterTest {

    private fun synced(vararg lines: Pair<Long, String>) = Lyrics.Synced(lines.map { (t, s) -> LyricsLine(t, s) })

    @Test
    fun `usable synced lyrics are left exactly as the provider timed them`() {
        val lyrics = synced(12_000L to "first line here", 15_500L to "second line", 19_000L to "", 21_000L to "third line")
        assertEquals(lyrics, LyricsFormatter.forPlayback(lyrics, 200_000))
    }

    @Test
    fun `plain lyrics are paced across the song, labels dropped, and marked as an estimate`() {
        val plain = Lyrics.Plain(listOf("[Verse 1]", "I walked along the shore", "the night was cold", "", "Chorus:", "Oh the coastline", "calls me home"))
        val paced = assertIs<Lyrics.Synced>(LyricsFormatter.forPlayback(plain, 180_000))
        assertTrue(paced.estimated)
        assertEquals(listOf("I walked along the shore", "the night was cold", "", "Oh the coastline", "calls me home"), paced.lines.map { it.text })
        val starts = paced.lines.map { it.startMs }
        assertEquals(starts.sorted(), starts, "in order")
        assertTrue(starts.first() >= 4_000, "a lead-in before the first line")
        assertTrue(starts.last() < 180_000 - 4_000, "an outro after the last")
        assertTrue(starts.zipWithNext().all { (a, b) -> b > a }, "every line gets its own moment")
    }

    @Test
    fun `without the song's length nothing is invented`() {
        val plain = Lyrics.Plain(listOf("one", "two"))
        assertSame(plain, LyricsFormatter.forPlayback(plain, null))
        assertSame(Lyrics.Instrumental, LyricsFormatter.forPlayback(Lyrics.Instrumental, 200_000))
    }

    @Test
    fun `lyrics whose stamps say nothing are paced like plain ones`() {
        // Every line on the same two stamps: the screen would never move.
        val stuck = synced(*(1..12).map { (if (it < 6) 0L else 1_000L) to "line number $it of the song" }.toTypedArray())
        val paced = assertIs<Lyrics.Synced>(LyricsFormatter.forPlayback(stuck, 200_000))
        assertTrue(paced.estimated)
        assertEquals(12, paced.lines.map { it.startMs }.distinct().size)
        assertTrue(paced.lines.last().startMs > 150_000, "spread over the song")
    }

    @Test
    fun `lines sharing one stamp are spread up to the next`() {
        val lyrics = synced(10_000L to "first", 20_000L to "a shared one", 20_000L to "another shared", 30_000L to "after")
        val fixed = assertIs<Lyrics.Synced>(LyricsFormatter.forPlayback(lyrics, 200_000))
        val starts = fixed.lines.map { it.startMs }
        assertEquals(10_000L, starts[0])
        assertEquals(20_000L, starts[1])
        assertTrue(starts[2] in 20_001L until 30_000L, "between its stamp and the next: ${starts[2]}")
        assertEquals(30_000L, starts[3])
        assertTrue(fixed.estimated)
    }

    @Test
    fun `stamps running past the song are fitted into it`() {
        val lyrics = synced(10_000L to "one", 150_000L to "two", 300_000L to "three", 330_000L to "four", 360_000L to "five", 390_000L to "six")
        val fitted = assertIs<Lyrics.Synced>(LyricsFormatter.forPlayback(lyrics, 200_000))
        assertTrue(fitted.lines.last().startMs < 200_000)
        assertTrue(fitted.estimated)
    }

    @Test
    fun `a line too long for the display is split at its phrases, within its own time`() {
        val long = "I have been walking down this road for days, and the sun keeps falling, but I still hear you calling me home tonight"
        val lyrics = synced(10_000L to long, 22_000L to "next")
        val split = assertIs<Lyrics.Synced>(LyricsFormatter.forPlayback(lyrics, 200_000)).lines
        assertTrue(split.size >= 3, split.toString())
        assertEquals(long, split.dropLast(1).joinToString(" ") { it.text })
        assertTrue(split.dropLast(1).all { it.startMs in 10_000L until 22_000L })
        assertTrue(split.dropLast(1).all { WordTiming.words(it).size <= 14 })
    }

    @Test
    fun `labels are recognised, words aren't mistaken for them`() {
        assertTrue(LyricsFormatter.isLabel("[Chorus]"))
        assertTrue(LyricsFormatter.isLabel("(Verse 2)"))
        assertTrue(LyricsFormatter.isLabel("Bridge:"))
        assertFalse(LyricsFormatter.isLabel("(oh oh oh)"))
        assertFalse(LyricsFormatter.isLabel("Chorus of angels sing tonight"))
    }
}
