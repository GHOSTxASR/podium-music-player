package app.podium.core.designsystem.component

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.designsystem.theme.DisplayTheme
import app.podium.core.designsystem.theme.PodiumTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The focused row's scrolling title (Rows.kt). Reported on the device: a long label at the top of a
 * list sat in the middle of its row for about a second, then jumped to the side. Now a long title
 * rests at its start, then eases into the scroll; a title that fits never moves.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScrollingTitleTest {

    @get:Rule
    val compose = createComposeRule()

    private var label by mutableStateOf("")

    private fun setUp() {
        RuntimeEnvironment.setQualifiers("w411dp-h891dp-port-420dpi")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PodiumTheme(displayTheme = DisplayTheme.BONE) {
                Box(Modifier.width(300.dp)) { MenuRow(label, focused = true, showChevron = false) }
            }
        }
    }

    private fun frame(): Bitmap {
        compose.waitForIdle()
        return compose.onRoot().captureToImage().asAndroidBitmap()
    }

    /** Ink per column (dark text on Bone's paper). */
    private fun profile(b: Bitmap): FloatArray = FloatArray(b.width) { x ->
        var ink = 0f
        for (y in 0 until b.height step 2) {
            val c = b.getPixel(x, y)
            ink += 255f - (((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)) / 3f
        }
        ink
    }

    /** How far [now] has moved left of [rest], in pixels (best match of the ink profiles). */
    private fun shift(rest: FloatArray, now: FloatArray): Int = (0..240).minBy { s ->
        var err = 0f
        var n = 0
        for (x in 40 until rest.size - 40) {
            val y = x - s
            if (y in now.indices) { err += abs(now[y] - rest[x]); n++ }
        }
        err / n
    }

    @Test
    fun aLongTitleRestsThenEasesIn() {
        label = "A title far too long to fit on one row of the paper, number two"
        setUp()
        val rest = profile(frame())
        val moves = mutableListOf<Int>()
        repeat(150) {
            compose.mainClock.advanceTimeBy(16)
            moves += shift(rest, profile(frame()))
        }
        val firstMove = moves.indexOfFirst { it > 0 }
        assertTrue(firstMove * 16 >= 1_300, "rests at its start first (moved after ${firstMove * 16} ms)")
        // Easing in: its first tenth of a second covers less than its last tenth before 2.4 s.
        val early = moves[firstMove + 6] - moves[firstMove]
        val later = moves[149] - moves[143]
        assertTrue(early < later, "gathers speed rather than starting at full speed ($early then $later)")
        assertTrue(moves.zipWithNext().all { (a, b) -> b - a in 0..4 }, "moves smoothly, never jumps: $moves")
    }

    @Test
    fun aTitleThatFitsNeverMoves() {
        label = "Albums"
        setUp()
        val first = frame()
        compose.mainClock.advanceTimeBy(4_000)
        assertEquals(0, shift(profile(first), profile(frame())))
        assertTrue(first.sameAs(frame()), "unchanged, and its last letter isn't faded away")
    }
}
