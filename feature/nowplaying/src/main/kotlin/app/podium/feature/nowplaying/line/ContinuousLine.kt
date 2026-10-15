package app.podium.feature.nowplaying.line

import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.feature.nowplaying.LyricClock
import app.podium.feature.nowplaying.LyricMotionProbe
import app.podium.feature.nowplaying.PulseFollower
import app.podium.player.api.AudioPulse
import app.podium.player.api.PlaybackController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Now Playing ▸ Lyrics in the Continuous line theme (LYRICS_ARCHITECTURE.md §12, D-81): one ivory
 * line writes the lyrics by hand on a black sheet, flows from each phrase's last letter into the
 * next, and draws instrumental line art between them — a single drawing the display follows.
 *
 * - The drawing is a function of the playback position ([LineComposition.cursorAt]); a seek or a
 *   rewind makes the pen retrace its route to the new place ([LinePlayhead]), never a new drawing.
 * - [following]: synced lyrics follow the music; otherwise (reading with the Wheel, or [script]
 *   untimed) the drawing shows [readingLine] written.
 * - The composition is prepared off the main thread for each song and display shape; each frame
 *   only maps the position to a cursor and draws prepared segments ([LinePainter]).
 * - Time comes from a [LyricClock] (D-82): the display's own frame times, leaning onto the player's
 *   position, so the pen moves the same distance every frame instead of by the uneven steps the raw
 *   position takes. [advancing]: the music is actually playing.
 * - The drawing swells very slightly with the bass ([pulse], D-82), and the line's glow brightens
 *   with it; not with reduced motion.
 * - Music never depends on this: it reads the position, nothing else.
 */
@Composable
internal fun ContinuousLine(
    controller: PlaybackController,
    script: LineScript,
    trackKey: String,
    following: Boolean,
    readingLine: Int,
    advancing: Boolean,
    pulse: AudioPulse,
    description: String,
    modifier: Modifier = Modifier,
) {
    val reduced = PodiumTheme.motion.reduced
    val density = LocalDensity.current.density
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .semantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        val aspect = if (constraints.maxWidth > 0) constraints.maxHeight.toFloat() / constraints.maxWidth else 1f
        // Rounded so a resize by a pixel or two doesn't rebuild the drawing.
        val shape = (aspect * 50f).toInt() / 50f
        val outlines = remember { OutlineGlyphs() }
        val composition by produceState<LineComposition?>(null, script, trackKey, shape) {
            value = null
            value = withContext(Dispatchers.Default) {
                LineComposition(script, LineComposition.seedOf(trackKey), shape) { cp -> LineHand.glyph(cp) ?: outlines.glyph(cp) }
            }
        }
        val current = composition ?: return@BoxWithConstraints
        val painter = remember(density) { LinePainter(density) }
        val frame = remember(current) { LineFrame() }
        val playhead = remember(current) { LinePlayhead() }
        val clock = remember(current) { LyricClock() }
        val follower = remember(current) { PulseFollower() }
        val probe = remember { LyricMotionProbe() }
        var tick by remember { mutableLongStateOf(0L) }
        val isAdvancing by rememberUpdatedState(advancing)
        val isReduced by rememberUpdatedState(reduced)
        val livePulse by rememberUpdatedState(pulse)
        LaunchedEffect(current, following, readingLine) {
            var lastNanos = -1L
            var prepared = -1
            while (isActive) {
                val nanos = withFrameNanos { it }
                val now = nanos / 1_000_000
                val moving = isAdvancing
                val raw = controller.positionMs()
                val position = clock.update(nanos, raw, moving)
                val target = if (following && script.timed) current.cursorAt(position) else current.cursorAfterLine(readingLine)
                // A seek is what the clock couldn't explain by time passing.
                playhead.update(target, now, clock.jumped && lastNanos >= 0, isReduced)
                current.frameAt(playhead.shown, isReduced, frame)
                val dt = if (lastNanos < 0) 0f else (nanos - lastNanos) / 1e6f
                lastNanos = nanos
                val bass = if (moving) livePulse.bassAt(position.toLong() + PulseFollower.LEAD_MS) else -1f
                frame.swell = if (isReduced) 0f else follower.update(bass, dt)
                probe.record(nanos, raw, position, moving, clock.jumped, bass, frame.swell)
                tick++
                // Prepare the next sections' routes and their paths before the pen reaches them (once per section).
                if (frame.section != prepared) {
                    prepared = frame.section
                    val next = prepared + 1..minOf(prepared + 2, current.sections.lastIndex)
                    launch(Dispatchers.Default) { for (i in next) painter.prepare(current.geometry(i).path) }
                }
                // Paused and still: look for a seek a few times a second instead of every frame.
                if (!isAdvancing && !playhead.travelling && follower.atRest) delay(PAUSED_POLL_MS)
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION")
            tick // read in the draw phase: a new frame redraws without recomposing
            drawRect(LineInk.Paper)
            drawIntoCanvas { painter.draw(it.nativeCanvas, size.width, size.height, current, frame, reduced) }
        }
    }
}

/**
 * Characters the hand doesn't write (another script, a symbol), traced from the system font's
 * outline so the pen still draws them as line (§12.2). Slanted like the hand, sized to its
 * x-height, cached; thread-safe (the composition is prepared off the main thread).
 */
internal class OutlineGlyphs {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
        textSize = TEXT_SIZE
    }
    private val path = Path()
    private val cache = HashMap<Int, Glyph?>()

    fun glyph(codePoint: Int): Glyph? = synchronized(this) {
        if (cache.containsKey(codePoint)) return cache[codePoint]
        build(codePoint).also { cache[codePoint] = it }
    }

    private fun build(codePoint: Int): Glyph? {
        if (Character.isWhitespace(codePoint) || Character.getType(codePoint) == Character.CONTROL.toInt()) return null
        val text = String(Character.toChars(codePoint))
        path.reset()
        paint.getTextPath(text, 0, text.length, 0f, 0f, path)
        val advance = paint.measureText(text) / 100f
        val points = path.approximate(0.6f)
        val contours = ArrayList<FloatArray>()
        val current = FloatList(64)
        var lastFraction = -1f
        var i = 0
        while (i + 2 < points.size) {
            val fraction = points[i]
            // Two points with the same fraction: the path moves to a new contour.
            if (fraction == lastFraction && current.size > 0) {
                if (current.size >= 4) contours += current.toArray()
                current.clear()
            }
            val x = points[i + 1]
            val y = points[i + 2]
            current.add((x - y * SLANT) / 100f, y / 100f)
            lastFraction = fraction
            i += 3
        }
        if (current.size >= 4) contours += current.toArray()
        return Glyph.traced(contours, advance.coerceAtLeast(0.2f))
    }

    private companion object {
        /** Sized so the system font's x-height meets the hand's (half an em). */
        const val TEXT_SIZE = 92f
        const val SLANT = 0.16f
    }
}

private const val PAUSED_POLL_MS = 250L
