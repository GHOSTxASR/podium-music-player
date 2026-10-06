package app.podium

import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.feature.settings.BackgroundImageStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The display's background picture (D-41): a chosen picture shows, downsampled; a missing one says so. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DisplayImagesTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private fun shown(uri: Uri): DisplayImages.State {
        val images = DisplayImages(context, scope)
        images.show(uri.toString())
        return runBlocking { withTimeout(10_000) { images.state.first { it.status != BackgroundImageStatus.NONE } } }
    }

    @Test
    fun `a chosen picture is decoded, downsampled to about the display's size`() {
        val file = File(context.cacheDir, "background.jpg")
        val picture = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 180, 40)) }
        file.outputStream().use { picture.compress(Bitmap.CompressFormat.JPEG, 90, it) }

        val state = shown(Uri.fromFile(file))

        // It used to be "Unavailable" for every picture: the size probe's null answer was taken for a failure.
        assertEquals(BackgroundImageStatus.READY, state.status)
        val image = assertNotNull(state.image)
        assertEquals(2000, image.bitmap.width)
        assertEquals(1500, image.bitmap.height)
        assertTrue(image.luminance > 0.3f, "a bright picture reads as bright (${image.luminance})")
    }

    @Test
    fun `a photo stored sideways with an orientation tag stands upright`() {
        val file = File(context.cacheDir, "portrait.jpg")
        val picture = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.DKGRAY) }
        file.outputStream().use { picture.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }

        val image = assertNotNull(shown(Uri.fromFile(file)).image)
        assertEquals(1500, image.bitmap.width)
        assertEquals(2000, image.bitmap.height)
    }

    @Test
    fun `a picture that can't be read any more is reported, not shown`() {
        val state = shown(Uri.fromFile(File(context.cacheDir, "gone.jpg")))
        assertEquals(BackgroundImageStatus.UNAVAILABLE, state.status)
        assertNull(state.image)
    }
}
