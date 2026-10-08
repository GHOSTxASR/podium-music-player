package app.podium.stickers

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A sticker's pixels (D-55): the cut, the border that follows the outline, the brush, the history. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StickerArtTest {
    /** A mask with a centred disc of [r] (fraction of the side) marked as subject. */
    private fun disc(size: Int, r: Float): FloatArray = FloatArray(size * size) { i ->
        val x = (i % size + 0.5f) / size - 0.5f
        val y = (i / size + 0.5f) / size - 0.5f
        if (x * x + y * y <= r * r) 1f else 0f
    }

    private fun solid(w: Int, h: Int, color: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test
    fun `the cut keeps the subject, trimmed, and leaves the rest clear`() {
        val picture = solid(200, 100, Color.GREEN)
        val alpha = StickerArt.alphaFrom(disc(64, 0.25f), 64, 200, 100)
        val cut = assertNotNull(StickerArt.cutout(picture, alpha))
        // The disc spans half the mask: half the picture's width and height, trimmed to it.
        assertTrue(cut.width in 95..106, "width ${cut.width}")
        assertTrue(cut.height in 46..54, "height ${cut.height}")
        assertEquals(0, Color.alpha(cut.getPixel(0, 0)), "a corner of the trimmed box is outside the disc")
        assertEquals(255, Color.alpha(cut.getPixel(cut.width / 2, cut.height / 2)))
    }

    @Test
    fun `nothing kept means no sticker`() {
        val alpha = StickerArt.alphaFrom(FloatArray(32 * 32), 32, 50, 50)
        assertNull(StickerArt.cutout(solid(50, 50, Color.RED), alpha))
    }

    @Test
    fun `the border follows the outline at the chosen thickness, in the chosen colour`() {
        val cut = StickerArt.cutout(solid(200, 200, Color.RED), StickerArt.alphaFrom(disc(64, 0.3f), 64, 200, 200))!!
        val thin = StickerArt.compose(cut, StickerBorder.WHITE, 0.2f)
        val thick = StickerArt.compose(cut, StickerBorder.WHITE, 1f)
        val none = StickerArt.compose(cut, StickerBorder.NONE, 1f)
        assertTrue(thick.width > thin.width && thin.width > none.width, "padding grows with the border")
        // Just outside the disc, on the horizontal through its centre: white for a border, clear (bar shadow) without.
        fun justOutside(b: Bitmap) = b.getPixel(b.width / 2 + cut.width / 2 + 3, b.height / 2)
        val w = justOutside(thick)
        assertTrue(Color.red(w) > 240 && Color.green(w) > 240 && Color.alpha(w) > 240, "white border: ${Integer.toHexString(w)}")
        assertTrue(Color.alpha(justOutside(none)) < 80, "no border, just the faint shadow")
        val black = StickerArt.compose(cut, StickerBorder.BLACK, 1f)
        val k = justOutside(black)
        assertTrue(Color.red(k) < 20 && Color.alpha(k) > 240, "black border")
        // The border is round like the subject: the padded corner stays clear.
        assertTrue(Color.alpha(thick.getPixel(2, 2)) < 10)
    }

    @Test
    fun `dilation grows a single point into a disc`() {
        val w = 21
        val alpha = ByteArray(w * w).also { it[10 * w + 10] = 255.toByte() }
        val grown = StickerArt.dilate(alpha, w, w, 5f)
        fun a(x: Int, y: Int) = grown[y * w + x].toInt() and 0xFF
        assertEquals(255, a(14, 10))
        assertEquals(255, a(10, 6))
        assertTrue(a(15, 10) in 1..254, "a soft one-pixel rim at the radius")
        assertEquals(0, a(17, 10))
        assertTrue(a(14, 14) < 255, "the diagonal is shorter than the axis reach: a disc, not a square")
    }

    @Test
    fun `the brush adds and erases a round patch`() {
        val size = 64
        val mask = FloatArray(size * size)
        assertTrue(StickerArt.brush(mask, size, 0.5f, 0.5f, 0.1f, 0.1f, add = true))
        assertEquals(1f, mask[32 * size + 32])
        assertEquals(0f, mask[32 * size + 50])
        assertFalse(StickerArt.brush(mask, size, 0.5f, 0.5f, 0.1f, 0.1f, add = true), "painting the same again changes nothing")
        StickerArt.brush(mask, size, 0.5f, 0.5f, 0.05f, 0.05f, add = false)
        assertEquals(0f, mask[32 * size + 32])
        assertTrue(mask[32 * size + 35] > 0.5f, "only the smaller patch was erased")
        // On a wide picture the patch is narrower in mask units across, so it stays round on the picture.
        val wide = FloatArray(size * size)
        StickerArt.brush(wide, size, 0.5f, 0.5f, 0.05f, 0.1f, add = true)
        assertEquals(0f, wide[32 * size + 32 + 4])
        assertEquals(1f, wide[(32 + 3) * size + 32])
    }

    @Test
    fun `undo and redo walk the mask's history`() {
        val mask = FloatArray(4)
        val history = MaskHistory(mask)
        assertFalse(history.canUndo)
        history.checkpoint()
        mask[0] = 1f
        history.checkpoint()
        mask[1] = 1f
        assertTrue(history.undo())
        assertEquals(listOf(1f, 0f, 0f, 0f), mask.toList())
        assertTrue(history.canRedo)
        assertTrue(history.redo())
        assertEquals(listOf(1f, 1f, 0f, 0f), mask.toList())
        history.undo()
        history.undo()
        assertEquals(listOf(0f, 0f, 0f, 0f), mask.toList())
        assertFalse(history.undo())
        // A new change forgets what could be redone.
        history.checkpoint()
        mask[3] = 1f
        assertFalse(history.canRedo)
    }

    @Test
    fun `a touch finds the top-most sticker under it, turned`() {
        val box = Size(400f, 800f)
        val low = StickerPlacement("low", "s", x = 0.5f, y = 0.5f, scale = 0.5f, rotation = 0f, z = 1)
        val high = StickerPlacement("high", "s", x = 0.5f, y = 0.5f, scale = 0.25f, rotation = 45f, z = 2)
        val images = mapOf("s" to androidx.compose.ui.graphics.ImageBitmap(100, 50))
        assertEquals("high", hitTest(listOf(low, high), images, Offset(200f, 400f), box, 0f)?.id)
        // Inside the wide one but outside the small turned one.
        assertEquals("low", hitTest(listOf(low, high), images, Offset(110f, 400f), box, 0f)?.id)
        assertNull(hitTest(listOf(low, high), images, Offset(20f, 100f), box, 0f))
        // A 90° turn swaps the reach across and down.
        val turned = StickerPlacement("t", "s", 0.5f, 0.5f, 0.5f, 90f, 1)
        assertTrue(contains(turned, 200f, 100f, Offset(200f, 400f + 90f), box, 0f))
        assertFalse(contains(turned, 200f, 100f, Offset(200f + 90f, 400f), box, 0f))
    }
}
