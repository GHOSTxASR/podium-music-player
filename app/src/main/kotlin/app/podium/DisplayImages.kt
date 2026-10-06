package app.podium

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.asImageBitmap
import app.podium.core.designsystem.theme.DisplayImage
import app.podium.feature.settings.BackgroundImageStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The virtual display's background picture (D-41, PODIUM_CUSTOMIZATION.md §4): the listener picks it
 * with the system photo picker, Podium keeps only its URI (with persisted read access) and decodes
 * it here, downsampled to about the display's size, off the main thread. When the picture can't be
 * read any more (deleted, access revoked), the display falls back to its solid colour and Settings
 * says so — nothing crashes, nothing is copied.
 */
class DisplayImages(private val context: Context, private val scope: CoroutineScope) {

    data class State(val uri: String?, val image: DisplayImage?, val status: BackgroundImageStatus)

    private val _state = MutableStateFlow(State(null, null, BackgroundImageStatus.NONE))
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    /** Shows the picture at [uri] (or none). Cheap to call repeatedly with the same URI. */
    fun show(uri: String?) {
        if (uri == _state.value.uri && (_state.value.status != BackgroundImageStatus.UNAVAILABLE || uri == null)) return
        job?.cancel()
        if (uri == null) {
            _state.value = State(null, null, BackgroundImageStatus.NONE)
            return
        }
        _state.value = State(uri, null, _state.value.status.takeIf { _state.value.uri == uri } ?: BackgroundImageStatus.NONE)
        job = scope.launch {
            val image = withContext(Dispatchers.IO) { decode(Uri.parse(uri)) }
            _state.value = State(uri, image, if (image != null) BackgroundImageStatus.READY else BackgroundImageStatus.UNAVAILABLE)
        }
    }

    /**
     * Keeps read access to a picture the listener just chose, and lets go of the one it replaces.
     * @return false when the system wouldn't grant lasting access (the picture then shows until the
     * app restarts, and Settings says when it's gone).
     */
    fun adopt(chosen: Uri, previous: String?): Boolean {
        val kept = runCatching {
            context.contentResolver.takePersistableUriPermission(chosen, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
        if (previous != null && previous != chosen.toString()) {
            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(previous), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        return kept
    }

    private fun decode(uri: Uri): DisplayImage? = try {
        val resolver = context.contentResolver
        // The size first. With inJustDecodeBounds the decoder always answers null and only fills in
        // the bounds — that null is not a failure (taking it for one made every picture "Unavailable").
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val probe = resolver.openInputStream(uri) ?: return null
        probe.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / (sample * 2) >= MAX_EDGE_PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        val bitmap = upright(decoded, orientationOf(uri))
        DisplayImage(bitmap.asImageBitmap(), averageLuminance(bitmap))
    } catch (e: Exception) {
        // Deleted, moved, access revoked, not a picture: the display shows the solid colour.
        Log.i(TAG, "Background picture unavailable (${e.javaClass.simpleName})")
        null
    }

    /** The photo's EXIF orientation (camera pictures are often stored sideways with a tag saying so). */
    private fun orientationOf(uri: Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

    /** [bitmap] turned the way [orientation] says it was taken. */
    private fun upright(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (turned !== bitmap) bitmap.recycle()
        return turned
    }

    private fun averageLuminance(bitmap: Bitmap): Float {
        val small = Bitmap.createScaledBitmap(bitmap, 16, 16, true)
        var sum = 0.0
        for (y in 0 until 16) for (x in 0 until 16) {
            val c = small.getPixel(x, y)
            fun lin(v: Int): Double {
                val s = v / 255.0
                return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
            }
            sum += 0.2126 * lin((c shr 16) and 0xFF) + 0.7152 * lin((c shr 8) and 0xFF) + 0.0722 * lin(c and 0xFF)
        }
        if (small !== bitmap) small.recycle()
        return (sum / 256).toFloat()
    }

    private companion object {
        const val TAG = "PodiumDisplayImage"

        /** About a large phone display's long edge: sharp, and a few MB at most. */
        const val MAX_EDGE_PX = 1600
    }
}
