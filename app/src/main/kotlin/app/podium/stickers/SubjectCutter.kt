package app.podium.stickers

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp

/**
 * Finds a picture's subject on the phone (D-55): Google's "magic touch" point-to-mask model
 * (MediaPipe interactive segmenter, Apache-2.0; third_party/MODELS.md) run by the bare LiteRT
 * runtime — no network, no telemetry, the picture never leaves the phone.
 *
 * The model reads 512×512 RGB (0–1) plus a fourth channel marking a point on the subject, and
 * answers each pixel's chance of belonging to it. Podium marks the picture's centre first (most
 * pictures are framed around their subject); a tap marks another point.
 */
class SubjectCutter(private val context: Context) : AutoCloseable {
    private var interpreter: Interpreter? = null
    private val input: ByteBuffer = ByteBuffer.allocateDirect(SIZE * SIZE * 4 * 4).order(ByteOrder.nativeOrder())
    private val output: ByteBuffer = ByteBuffer.allocateDirect(SIZE * SIZE * 4).order(ByteOrder.nativeOrder())

    private fun interpreter(): Interpreter = interpreter ?: Interpreter(
        loadModel(),
        Interpreter.Options().setNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4)),
    ).also { interpreter = it }

    private fun loadModel(): MappedByteBuffer = context.assets.openFd(MODEL).use { fd ->
        FileInputStream(fd.fileDescriptor).use { stream ->
            stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }

    /**
     * The subject's mask for [image] around the point ([x],[y]) (fractions of the picture): a
     * [SIZE]×[SIZE] grid of 0–1. Blocking (a few hundred milliseconds): call off the main thread.
     */
    @Synchronized
    fun mask(image: Bitmap, x: Float, y: Float): FloatArray {
        val scaled = Bitmap.createScaledBitmap(image, SIZE, SIZE, true)
        val pixels = IntArray(SIZE * SIZE)
        scaled.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        if (scaled !== image) scaled.recycle()
        val px = x * SIZE
        val py = y * SIZE
        val r = POINT_RADIUS * SIZE
        input.rewind()
        for (j in 0 until SIZE) for (i in 0 until SIZE) {
            val c = pixels[j * SIZE + i]
            input.putFloat(((c shr 16) and 0xFF) / 255f)
            input.putFloat(((c shr 8) and 0xFF) / 255f)
            input.putFloat((c and 0xFF) / 255f)
            // The point: a small soft disc of 1s on the subject.
            val dx = i + 0.5f - px
            val dy = j + 0.5f - py
            val d2 = dx * dx + dy * dy
            input.putFloat(if (d2 <= r * r) 1f else exp(-(d2 - r * r) / (2f * r * r)).takeIf { it > 0.02f } ?: 0f)
        }
        input.rewind()
        output.rewind()
        interpreter().run(input, output)
        output.rewind()
        val mask = FloatArray(SIZE * SIZE) { output.float }
        // The published model ends in a sigmoid; should a build answer raw scores instead, squash them.
        if (mask.any { it < 0f || it > 1f }) for (i in mask.indices) mask[i] = 1f / (1f + exp(-mask[i]))
        return mask
    }

    override fun close() {
        interpreter?.close()
        interpreter = null
    }

    companion object {
        const val SIZE = 512
        private const val MODEL = "models/magic_touch.tflite"

        /** The marked point's radius, as a fraction of the model's side. */
        private const val POINT_RADIUS = 0.012f
    }
}
