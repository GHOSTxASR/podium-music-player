package app.podium.feature.nowplaying.line

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.lyrics.Lyrics
import app.podium.core.lyrics.LyricsLine
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.lang.management.ManagementFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the Continuous line costs (LYRICS_ARCHITECTURE.md §12.7), measured on the build machine's JVM
 * — not the phone; the numbers are printed (test report's standard output) and the assertions only
 * hold the shape of the work: preparing is done once per song, a frame allocates nothing in the
 * mapping, and a frame's drawing is a bounded number of calls however long the song.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LinePerformanceTest {

    private fun song(lines: Int, gapEvery: Int = 8): Lyrics.Synced {
        val out = ArrayList<LyricsLine>()
        var t = 12_000L
        for (i in 0 until lines) {
            out += LyricsLine(t, PHRASES[i % PHRASES.size])
            t += if (i % gapEvery == gapEvery - 1) 16_000L else 3_400L
        }
        return Lyrics.Synced(out)
    }

    @Test
    fun `preparing a song is a one-off, and a frame's mapping allocates nothing`() {
        val typical = song(48)
        val duration = typical.lines.last().startMs + 20_000
        // Warm up the JIT (and the glyph cache) the way a second song would find it.
        repeat(3) { LineComposition(LineScript.synced(typical, duration), it.toLong(), 1.57f, ::glyphOrBox) }
        var start = System.nanoTime()
        val c = LineComposition(LineScript.synced(typical, duration), 42L, 1.57f, ::glyphOrBox)
        val prepareMs = (System.nanoTime() - start) / 1e6
        start = System.nanoTime()
        var points = 0
        for (i in c.sections.indices) points += c.sections[i].recipe().path.count
        val rebuildMs = (System.nanoTime() - start) / 1e6 / c.sections.size

        val frame = LineFrame()
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val id = Thread.currentThread().id
        // Warm: every section's route is prepared once (as the screen does ahead of the pen).
        for (t in 0L..duration step 40) c.frameAt(c.cursorAt(t), false, frame)
        // Measure a stretch of playback inside the cache window, frame by frame.
        val from = 60_000L
        val frames = 600
        for (k in 0 until frames) c.frameAt(c.cursorAt(from + k * 8L), false, frame)
        val before = threads.getThreadAllocatedBytes(id)
        start = System.nanoTime()
        for (k in 0 until frames) c.frameAt(c.cursorAt(from + k * 8L), false, frame)
        val perFrameUs = (System.nanoTime() - start) / 1e3 / frames
        val allocated = threads.getThreadAllocatedBytes(id) - before
        println(
            "LINE PERF: ${typical.lines.size} lines, ${c.sections.size} sections, $points points in all; " +
                "prepare %.1f ms once; rebuild a section %.2f ms; map a frame %.2f µs; allocated $allocated bytes over $frames frames"
                    .format(prepareMs, rebuildMs, perFrameUs),
        )
        assertEquals(0L, allocated, "mapping playback to a frame allocates nothing")
        assertTrue(c.cachedSections <= LineComposition.CACHE_SECTIONS)
    }

    @Test
    fun `a frame draws a bounded number of prepared segments, whatever the song's length`() {
        val painter = LinePainter(density = 2.625f)
        val bitmap = Bitmap.createBitmap(1013, 1594, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val frame = LineFrame()
        var maxCalls = 0
        var maxSegments = 0
        var maxGlow = 0
        for (lines in listOf(12, 48, 160)) {
            val lyrics = song(lines)
            val duration = lyrics.lines.last().startMs + 20_000
            val c = LineComposition(LineScript.synced(lyrics, duration), 7L, 1.57f, ::glyphOrBox)
            var calls = 0
            var segments = 0
            var glow = 0
            var samples = 0
            for (t in 0L..duration step 1_000) {
                c.frameAt(c.cursorAt(t), false, frame)
                painter.draw(canvas, 1013f, 1594f, c, frame, reduced = false)
                calls = maxOf(calls, painter.lastLineCalls)
                segments = maxOf(segments, painter.lastSegments)
                glow = maxOf(glow, painter.lastGlowSegments)
                samples++
            }
            println("LINE DRAW: $lines lines over $samples frames: at most $calls line calls, $segments line segments and $glow glow segments (all layers) in a frame")
            maxCalls = maxOf(maxCalls, calls)
            maxSegments = maxOf(maxSegments, segments)
            maxGlow = maxOf(maxGlow, glow)
        }
        // The busiest frame of a 160-line song costs no more than one of a 12-line song's would allow.
        assertTrue(maxCalls < 400, "line calls per frame: $maxCalls")
        assertTrue(maxSegments < 20_000, "segments per frame: $maxSegments")
        // The glow (D-82): each of its layers draws well under half the line's segments.
        assertTrue(maxGlow / LinePainter.GLOW_LAYERS < maxSegments / 2, "glow segments per frame: $maxGlow")
    }

    private companion object {
        val PHRASES = listOf(
            "When the night is quiet",
            "and everything they say",
            "I wish that I could hold the stars",
            "Hold on",
            "and everything they say is a little bit too long to fit on one small screen",
            "Over the water, under the bridge",
            "Call me when the morning comes",
        )
    }
}
