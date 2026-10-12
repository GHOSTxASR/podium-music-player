package app.podium.feature.nowplaying.line

import app.podium.core.lyrics.Lyrics
import app.podium.core.lyrics.LyricsLine
import app.podium.core.lyrics.LyricsWord
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The Continuous line composition as pure logic (LYRICS_ARCHITECTURE.md §12): playback position →
 * cursor → pen and camera, deterministic and reversible, with timing only from the lyrics.
 */
class LineCompositionTest {

    private val lines = listOf(
        LyricsLine(10_000, "When the night is quiet"),
        LyricsLine(13_500, "and everything they say"),
        LyricsLine(17_000, "I wish that I could hold the stars"),
        LyricsLine(21_000, ""),
        LyricsLine(32_000, "Hold on"),
    )

    private fun composition(
        lyrics: Lyrics.Synced = Lyrics.Synced(lines),
        key: String = "test|1",
        duration: Long? = 60_000,
        aspect: Float = 1.5f,
    ) = LineComposition(LineScript.synced(lyrics, duration), LineComposition.seedOf(key), aspect, ::glyphOrBox)

    private fun frame(c: LineComposition, cursor: Double) = LineFrame().also { c.frameAt(cursor, reduced = false, out = it) }

    @Test
    fun `the cursor follows the song forward and never runs backward`() {
        val c = composition()
        var last = -1.0
        for (t in 0L..62_000L step 50) {
            val cursor = c.cursorAt(t)
            assertTrue(cursor >= last, "cursor went back at $t: $cursor < $last")
            last = cursor
        }
        // Each phrase is being written exactly while its line is sung.
        val first = c.sectionOfPhrase(0)
        assertEquals(first.toDouble(), c.cursorAt(10_000), 1e-9)
        assertTrue(c.cursorAt(11_000) > first && c.cursorAt(11_000) < first + 1)
        assertEquals(SectionKind.PHRASE, c.sections[first].kind)
        assertEquals(c.sections.size.toDouble(), c.cursorAt(70_000), "past the end everything is drawn")
    }

    @Test
    fun `the same moment always draws the same frame, however it was reached`() {
        val a = composition()
        val b = composition() // built again from the same inputs
        for (t in listOf(0L, 5_000L, 10_400L, 12_000L, 15_800L, 24_000L, 33_000L, 50_000L)) {
            val cursor = a.cursorAt(t)
            assertEquals(cursor, b.cursorAt(t))
            val once = frame(a, cursor)
            // Visit elsewhere (forward and back) and return: identical.
            frame(a, a.cursorAt(t + 7_000))
            frame(a, a.cursorAt((t - 3_000).coerceAtLeast(0)))
            assertEquals(once, frame(a, cursor), "frame at $t after seeking around")
            assertEquals(once, frame(b, b.cursorAt(t)), "frame at $t in a rebuilt composition")
        }
    }

    @Test
    fun `rewinding returns the pen along the same route`() {
        val c = composition()
        val s = c.sectionOfPhrase(1)
        val path = c.geometry(s).path
        // Positions of the pen while the phrase is written, forward then backward: the same points.
        val times = (13_500L until c.script.phrases[1].endMs step 100).toList()
        val forward = times.map { frame(c, c.cursorAt(it)).let { f -> f.penX to f.penY } }
        val backward = times.reversed().map { frame(c, c.cursorAt(it)).let { f -> f.penX to f.penY } }.reversed()
        assertEquals(forward, backward)
        // And they are points on the prepared route.
        for ((x, y) in forward) assertTrue(onPath(path, x, y), "pen ($x, $y) is on the route")
        // The revealed ink only grows with time and shrinks with a rewind.
        val taus = times.map { frame(c, c.cursorAt(it)).tau }
        assertEquals(taus.sorted(), taus)
    }

    @Test
    fun `word times drive the pen through each word`() {
        // Provider word times: each word is reached exactly at its time.
        val timed = LyricsLine(
            10_000, "hold the line",
            endMs = 12_000,
            words = listOf(LyricsWord(10_000, "hold"), LyricsWord(11_000, "the"), LyricsWord(11_500, "line")),
        )
        val c = composition(Lyrics.Synced(listOf(timed, LyricsLine(20_000, "again"))))
        val phrase = c.script.phrases[0]
        assertFalse(phrase.estimated)
        val s = c.sectionOfPhrase(0)
        val g = c.geometry(s)
        for (w in 0 until 3) {
            val f = frame(c, c.cursorAt(phrase.wordStarts[w]))
            assertEquals(s, f.section)
            assertEquals(g.wordTaus[w], f.tau, 1e-3f, "word $w begins at its time")
        }
        // The line ends on the provider's end time.
        assertEquals((s + 1).toDouble(), c.cursorAt(12_000), 1e-9)
    }

    @Test
    fun `line-only timing is marked estimated and stays inside the line's real window`() {
        val c = composition()
        val phrase = c.script.phrases[0]
        assertTrue(phrase.estimated, "words paced inside a line-timed lyric are an estimate")
        assertEquals(10_000L, phrase.wordStarts.first(), "the first word is exactly on the line's time")
        assertTrue(phrase.endMs <= 13_500, "never past the next line")
        assertTrue(phrase.wordStarts.toList() == phrase.wordStarts.sorted())
    }

    @Test
    fun `instrumental gaps are drawn as stable motifs and lead into the next phrase`() {
        val c = composition()
        val hold = c.sectionOfPhrase(3)
        val gap = c.sections[hold - 1]
        assertEquals(SectionKind.BETWEEN, gap.kind)
        assertTrue(gap.hasMotifs, "a ten-second gap draws instrumental line art")
        // The line art runs straight into the first letter of the next phrase.
        val end = c.geometry(hold - 1).path
        val start = c.geometry(hold).path
        assertEquals(end.xs[end.count - 1], start.xs[0], 1e-4f)
        assertEquals(end.ys[end.count - 1], start.ys[0], 1e-4f)
        // The same song draws the same motifs; another song draws others.
        val again = composition().geometry(hold - 1).path
        assertTrue(end.xs.contentEquals(again.xs) && end.ys.contentEquals(again.ys))
        val other = composition(key = "test|2").geometry(hold - 1).path
        assertFalse(end.xs.contentEquals(other.xs) && end.ys.contentEquals(other.ys))
    }

    @Test
    fun `every section continues from where the last one left off`() {
        val c = composition()
        for (i in 1 until c.sections.size) {
            val a = c.geometry(i - 1).path
            val b = c.geometry(i).path
            val gap = hypot(a.xs[a.count - 1] - b.xs[0], a.ys[a.count - 1] - b.ys[0])
            assertTrue(gap < 1e-3f, "section $i starts where ${i - 1} ended (gap $gap)")
        }
    }

    @Test
    fun `phrases flow down and to the right`() {
        val c = composition()
        val anchors = c.script.phrases.indices.map { c.sections[c.sectionOfPhrase(it)].let { s -> s.fromX to s.fromY } }
        for (i in 1 until anchors.size) {
            assertTrue(anchors[i].first > anchors[i - 1].first, "phrase $i is right of the last")
            assertTrue(anchors[i].second > anchors[i - 1].second, "phrase $i is below the last")
        }
    }

    @Test
    fun `the pen is always on the display`() {
        val c = composition()
        for (t in 0L..62_000L step 250) {
            val f = frame(c, c.cursorAt(t))
            assertTrue(abs(f.penX - f.cameraX) <= c.viewWidth / 2, "pen inside horizontally at $t")
            assertTrue(abs(f.penY - f.cameraY) <= c.viewHeight / 2, "pen inside vertically at $t")
        }
    }

    @Test
    fun `reduced motion moves the camera in a few cuts`() {
        val c = composition()
        val gap = c.sectionOfPhrase(3) - 1
        val positions = (0..100).map { i ->
            LineFrame().also { c.frameAt(gap + i / 100.0, reduced = true, out = it) }.let { it.cameraX to it.cameraY }
        }
        val moves = positions.zipWithNext().count { (a, b) -> a != b }
        val smooth = (0..100).map { i -> frame(c, gap + i / 100.0).let { it.cameraX to it.cameraY } }.zipWithNext().count { (a, b) -> a != b }
        assertTrue(moves < smooth / 3, "reduced motion: $moves camera moves against $smooth")
    }

    @Test
    fun `reading a line shows it written, camera on it`() {
        val c = composition()
        val cursor = c.cursorAfterLine(1)
        val f = frame(c, cursor)
        val phrase = c.sections[c.sectionOfPhrase(1)]
        assertEquals(phrase.fromX, f.cameraX, 1e-3f)
        assertEquals(phrase.fromY, f.cameraY, 1e-3f)
        assertEquals(1, c.lineAt(cursor))
        // A gap line reads as the phrase before it.
        assertEquals(c.cursorAfterLine(2), c.cursorAfterLine(3))
    }

    @Test
    fun `a song without words is one instrumental composition across its length`() {
        val c = LineComposition(LineScript.instrumental(120_000), LineComposition.seedOf("x|y"), 1.5f, ::glyphOrBox)
        assertEquals(1, c.sections.size)
        assertEquals(SectionKind.CODA, c.sections[0].kind)
        assertTrue(c.sections[0].hasMotifs)
        // It keeps drawing right up to the end of the song.
        val early = frame(c, c.cursorAt(30_000))
        val late = frame(c, c.cursorAt(110_000))
        assertTrue(late.tau > early.tau)
        assertTrue(late.penX > early.penX, "the drawing travels on across the paper")
    }

    @Test
    fun `memory stays bounded however long the song`() {
        val many = (0 until 120).map { LyricsLine(10_000L + it * 3_000L, "line number $it of a long song") }
        val c = composition(Lyrics.Synced(many), duration = 10_000L + 121 * 3_000L)
        for (t in 0L..400_000L step 400) frame(c, c.cursorAt(t))
        assertTrue(c.cachedSections <= LineComposition.CACHE_SECTIONS, "kept ${c.cachedSections}")
    }

    @Test
    fun `phrases sung back to back still leave the line a moment to connect`() {
        val tight = listOf(LyricsLine(1_000, "one two three", endMs = 3_000), LyricsLine(3_000, "four five"))
        val script = LineScript.synced(Lyrics.Synced(tight), 10_000)
        assertTrue(script.phrases[1].startMs - script.phrases[0].endMs >= LineScript.MIN_CONNECTOR_MS)
        assertEquals(3_000L, script.phrases[1].startMs, "the next phrase still starts on time")
    }

    @Test
    fun `a new composition for a new song keeps nothing of the last`() {
        val a = composition(key = "song|a")
        val other = Lyrics.Synced(listOf(LyricsLine(5_000, "something else entirely")))
        val b = composition(other, key = "song|b")
        assertEquals(1, b.script.phrases.size)
        assertEquals("something else entirely", b.script.phrases[0].text)
        assertNotEquals(a.seed, b.seed)
        // Its frames come from its own sections only.
        val f = frame(b, b.cursorAt(5_500))
        assertEquals(b.sectionOfPhrase(0), f.section)
        assertTrue(onPath(b.geometry(f.section).path, f.penX, f.penY))
    }

    private fun onPath(path: InkPath, x: Float, y: Float): Boolean {
        for (i in 0 until path.count - 1) {
            val ax = path.xs[i]
            val ay = path.ys[i]
            val bx = path.xs[i + 1]
            val by = path.ys[i + 1]
            val dx = bx - ax
            val dy = by - ay
            val l2 = dx * dx + dy * dy
            val t = if (l2 == 0f) 0f else (((x - ax) * dx + (y - ay) * dy) / l2).coerceIn(0f, 1f)
            if (hypot(ax + dx * t - x, ay + dy * t - y) < 1e-3f) return true
        }
        return false
    }
}

/** The hand, or a small box for anything it doesn't write (tests have no system font). */
internal fun glyphOrBox(cp: Int): Glyph? = LineHand.glyph(cp)
    ?: Glyph.traced(listOf(floatArrayOf(0f, 0f, 0.4f, 0f, 0.4f, -0.5f, 0f, -0.5f, 0f, 0f)), 0.45f)
