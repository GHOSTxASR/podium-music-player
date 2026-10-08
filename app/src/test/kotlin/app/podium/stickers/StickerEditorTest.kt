package app.podium.stickers

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Arranging a sticker (D-55, D-65): the sticker is drawn where the finger is on the frame it moves —
 * from the editor's live placement, before anything is stored — and the store is written once, when
 * the finger lifts, after which the live placement is let go.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StickerEditorTest {
    @get:Rule
    val compose = createComposeRule()

    private val store = StickerStore(Files.createTempDirectory("stickers").toFile(), io = { it.run() })

    @Test
    fun `a dragged sticker follows the finger at once and is kept when it lifts`() {
        val art = disc()
        val sticker = store.add(art, art, StickerBorder.WHITE, 0.5f)
        val placed = store.place(sticker.id, x = 0.5f, y = 0.5f)
        val editor = StickerEditorState().apply { selected = placed.id }
        compose.setContent {
            Box(Modifier.size(400.dp).testTag("object")) {
                StickerLayer(store, editor = editor)
                StickerEditor(store, editor)
            }
        }
        // The images decode off the main thread before a sticker can be taken hold of.
        compose.waitUntil(5_000) {
            Thread.sleep(20)
            compose.onNodeWithTag("object").performTouchInput { down(center) }
            val held = editor.live != null || run {
                compose.onNodeWithTag("object").performTouchInput { moveBy(Offset(1f, 0f)) }
                editor.live != null
            }
            if (!held) compose.onNodeWithTag("object").performTouchInput { up() }
            held
        }

        compose.onNodeWithTag("object").performTouchInput { moveBy(Offset(80f, 0f)) }
        val live = assertNotNull(editor.live, "the moved placement is drawn at once")
        assertTrue(live.x > 0.55f, "it moved with the finger: $live")
        assertEquals(0.5f, store.placements.value.single().x, "nothing is written while the finger is down")

        compose.onNodeWithTag("object").performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(live.x, store.placements.value.single().x, 0.0001f)
        assertNull(editor.live, "once stored, the layers draw from the store again")
    }

    private fun disc(): Bitmap {
        val b = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(b).drawCircle(60f, 60f, 50f, android.graphics.Paint().apply { color = 0xFF46A758.toInt(); isAntiAlias = true })
        return b
    }
}
