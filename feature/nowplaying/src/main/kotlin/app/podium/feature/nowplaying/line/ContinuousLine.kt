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
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.player.api.PlaybackController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

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
 * - Music never depends on this: it reads the position, nothing else.
 */
@Composable
internal fun ContinuousLine(
    controller: PlaybackController,
    script: LineScript,
    trackKey: String,
    following: Boolean,
    readingLine: Int,
    playing: Boolean,
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
        var tick by remember { mutableLongStateOf(0L) }
        val isPlaying by rememberUpdatedState(playing)
        val isReduced by rememberUpdatedState(reduced)
        LaunchedEffect(current, following, readingLine) {
            var lastPosition = -1L
            var lastNow = 0L
            var prepared = -1
            while (isActive) {
                val now = withFrameMillis { it }
                val position = controller.positionMs()
                // A seek: the position moved unlike the clock (beyond a little jitter).
                val expected = if (isPlaying) now - lastNow else 0L
                val jumped = lastPosition >= 0 && abs((position - lastPosition) - expected) > SEEK_MS
                lastPosition = position
                lastNow = now
                val target = if (following && script.timed) current.cursorAt(position) else current.cursorAfterLine(readingLine)
                playhead.update(target, now, jumped, isReduced)
                current.frameAt(playhead.shown, isReduced, frame)
                tick++
                // Prepare the next sections' routes before the pen reaches them (once per section).
                if (frame.section != prepared) {
                    prepared = frame.section
                    val next = prepared + 1..minOf(prepared + 2, current.sections.lastIndex)
                    launch(Dispatchers.Default) { for (i in next) current.geometry(i) }
                }
                // Paused and still: look for a seek a few times a second instead of every frame.
                if (!isPlaying && !playhead.travelling) delay(PAUSED_POLL_MS)
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

private const val SEEK_MS = 600L
private const val PAUSED_POLL_MS = 250L
