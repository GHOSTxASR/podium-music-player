package app.podium.stickers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stickers are kept (D-55): made, stuck on, moved, turned, resized, taken off and deleted — and
 * all of it is there again after a restart (a fresh store over the same folder).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StickerStoreTest {
    private val dir: File = Files.createTempDirectory("stickers").toFile()
    private var clock = 1_000L

    private fun store() = StickerStore(dir, io = { it.run() }, now = { clock++ })

    private fun art(color: Int = Color.RED): Bitmap {
        val b = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
        Canvas(b).drawOval(10f, 10f, 70f, 50f, Paint().apply { this.color = color })
        return b
    }

    @Test
    fun `a sticker is kept with its border and thickness`() {
        val made = store().add(art(), art(), StickerBorder.BLACK, 0.8f)
        assertTrue(store().imageFile(made.id).exists())
        assertTrue(store().cutoutFile(made.id).exists())
        val again = store().stickers.value.single()
        assertEquals(made.id, again.id)
        assertEquals(StickerBorder.BLACK, again.border)
        assertEquals(0.8f, again.thickness, 1e-4f)
        assertEquals(80, again.width)
        assertEquals(60, again.height)
    }

    @Test
    fun `stuck on, moved, turned and resized — still there after a restart`() {
        val s = store()
        val sticker = s.add(art(), art(), StickerBorder.WHITE, 0.4f)
        val placed = s.place(sticker.id)
        s.update(placed.copy(x = 0.7f, y = 0.85f, rotation = 400f, scale = 0.5f))
        val back = store().placements.value.single()
        assertEquals(sticker.id, back.stickerId)
        assertEquals(0.7f, back.x, 1e-4f)
        assertEquals(0.85f, back.y, 1e-4f)
        assertEquals(40f, back.rotation, 1e-3f, "turns are kept within a circle")
        assertEquals(0.5f, back.scale, 1e-4f)
    }

    @Test
    fun `sizes and places stay within bounds`() {
        val s = store()
        val placed = s.place(s.add(art(), art(), StickerBorder.NONE, 0f).id)
        s.update(placed.copy(scale = 40f, x = 9f, y = -9f))
        val p = s.placements.value.single()
        assertEquals(StickerStore.MAX_SCALE, p.scale)
        assertTrue(p.x <= 1.1f && p.y >= -0.1f)
        s.update(p.copy(scale = 0.0001f))
        assertEquals(StickerStore.MIN_SCALE, s.placements.value.single().scale)
    }

    @Test
    fun `several stickers and placements, newest on top, raised on touch`() {
        val s = store()
        val a = s.add(art(Color.RED), art(), StickerBorder.WHITE, 0.5f)
        val b = s.add(art(Color.BLUE), art(), StickerBorder.BLACK, 0.2f)
        val p1 = s.place(a.id)
        val p2 = s.place(b.id)
        val p3 = s.place(a.id)
        assertTrue(p3.z > p2.z && p2.z > p1.z)
        s.raise(p1.id)
        assertEquals(p1.id, s.placements.value.maxBy { it.z }.id)
        val reloaded = store()
        assertEquals(2, reloaded.stickers.value.size)
        assertEquals(3, reloaded.placements.value.size)
        assertEquals(p1.id, reloaded.placements.value.maxBy { it.z }.id)
    }

    @Test
    fun `taking one off leaves the sticker, deleting it takes it off everywhere`() {
        val s = store()
        val a = s.add(art(), art(), StickerBorder.WHITE, 0.5f)
        val b = s.add(art(), art(), StickerBorder.WHITE, 0.5f)
        val pa1 = s.place(a.id)
        s.place(a.id)
        val pb = s.place(b.id)
        s.removePlacement(pa1.id)
        assertEquals(2, s.placements.value.size)
        assertEquals(2, s.stickers.value.size)
        s.deleteSticker(a.id)
        assertEquals(listOf(pb.id), s.placements.value.map { it.id })
        assertFalse(s.imageFile(a.id).exists())
        assertFalse(s.cutoutFile(a.id).exists())
        assertNull(s.image(a.id))
        val reloaded = store()
        assertEquals(listOf(b.id), reloaded.stickers.value.map { it.id })
        assertEquals(listOf(pb.id), reloaded.placements.value.map { it.id })
    }

    @Test
    fun `a damaged index starts empty instead of failing`() {
        store().add(art(), art(), StickerBorder.WHITE, 0.5f)
        File(dir, "stickers.json").writeText("{ not json")
        val s = store()
        assertTrue(s.stickers.value.isEmpty())
        // And it can still be used.
        assertNotNull(s.add(art(), art(), StickerBorder.NONE, 0f))
    }

    @Test
    fun `an image is decoded once, no larger than asked`() {
        val s = store()
        val big = Bitmap.createBitmap(640, 320, Bitmap.Config.ARGB_8888)
        val sticker = s.add(big, big, StickerBorder.NONE, 0f)
        val small = assertNotNull(s.image(sticker.id, maxSide = 200))
        assertTrue(maxOf(small.width, small.height) <= 400, "sampled down: ${small.width}x${small.height}")
        assertTrue(s.image(sticker.id, maxSide = 200) === small, "cached")
    }
}
