package app.podium.stickers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** The sticker's outline: none, or a black or white edge following the cut-out's shape (D-55). */
enum class StickerBorder { NONE, BLACK, WHITE }

/**
 * A sticker the listener made: the cut-out with its border, kept as `<id>.png` (at most
 * [StickerArt.MAX_SIDE] px), and the cut-out alone as `<id>.cut.png` so the border can change later.
 * [thickness] is 0–1 of the border's range.
 */
data class Sticker(
    val id: String,
    val border: StickerBorder,
    val thickness: Float,
    val width: Int,
    val height: Int,
    val createdAt: Long,
)

/**
 * A sticker stuck on the Podium, in the object's own coordinates (PHYSICAL_SPACE.md §6): centre
 * [x],[y] as fractions of the object's width and height, [scale] as the sticker's width over the
 * object's width, [rotation] in degrees, [z] its stacking order (higher is on top).
 */
data class StickerPlacement(
    val id: String,
    val stickerId: String,
    val x: Float,
    val y: Float,
    val scale: Float,
    val rotation: Float,
    val z: Int,
)

/**
 * The listener's stickers and where they're stuck (D-55): images as files in [directory], the
 * rest in `stickers.json` beside them, written atomically off the main thread. Nothing leaves the
 * phone. Images are decoded once at display size and kept in a small memory cache.
 */
class StickerStore(
    private val directory: File,
    private val io: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "podium-stickers").apply { isDaemon = true } },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _stickers = MutableStateFlow<List<Sticker>>(emptyList())
    val stickers: StateFlow<List<Sticker>> = _stickers.asStateFlow()

    private val _placements = MutableStateFlow<List<StickerPlacement>>(emptyList())
    val placements: StateFlow<List<StickerPlacement>> = _placements.asStateFlow()

    private val images = object : LruCache<String, Bitmap>(IMAGE_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    init {
        directory.mkdirs()
        load()
    }

    fun imageFile(id: String) = File(directory, "$id.png")
    fun cutoutFile(id: String) = File(directory, "$id.cut.png")

    /** Keeps a new sticker: [art] is the cut-out with its border, [cutout] the cut-out alone. Blocking (call off the main thread). */
    fun add(art: Bitmap, cutout: Bitmap, border: StickerBorder, thickness: Float): Sticker {
        val id = UUID.randomUUID().toString()
        writePng(imageFile(id), art)
        writePng(cutoutFile(id), cutout)
        val sticker = Sticker(id, border, thickness.coerceIn(0f, 1f), art.width, art.height, now())
        _stickers.value = _stickers.value + sticker
        persist()
        return sticker
    }

    /** Sticks [stickerId] on the Podium: near the top of the body, above everything already there. */
    fun place(stickerId: String, x: Float = 0.5f, y: Float = 0.3f, scale: Float = DEFAULT_SCALE): StickerPlacement {
        val placement = StickerPlacement(
            id = UUID.randomUUID().toString(),
            stickerId = stickerId,
            x = x, y = y, scale = scale, rotation = 0f,
            z = (_placements.value.maxOfOrNull { it.z } ?: 0) + 1,
        )
        _placements.value = _placements.value + placement
        persist()
        return placement
    }

    /** A placement moved, turned or resized; [persistNow] false while a gesture is still going. */
    fun update(placement: StickerPlacement, persistNow: Boolean = true) {
        val clamped = placement.copy(
            x = placement.x.coerceIn(-0.1f, 1.1f),
            y = placement.y.coerceIn(-0.1f, 1.1f),
            scale = placement.scale.coerceIn(MIN_SCALE, MAX_SCALE),
            rotation = ((placement.rotation % 360f) + 360f) % 360f,
        )
        _placements.value = _placements.value.map { if (it.id == clamped.id) clamped else it }
        if (persistNow) persist()
    }

    /** Brings a placement to the top. */
    fun raise(id: String) {
        val top = _placements.value.maxOfOrNull { it.z } ?: 0
        _placements.value = _placements.value.map { if (it.id == id && it.z < top) it.copy(z = top + 1) else it }
        persist()
    }

    fun removePlacement(id: String) {
        _placements.value = _placements.value.filterNot { it.id == id }
        persist()
    }

    /** The sticker and everywhere it's stuck. */
    fun deleteSticker(id: String) {
        _stickers.value = _stickers.value.filterNot { it.id == id }
        _placements.value = _placements.value.filterNot { it.stickerId == id }
        images.snapshot().keys.filter { it.startsWith("$id@") }.forEach(images::remove)
        persist {
            imageFile(id).delete()
            cutoutFile(id).delete()
        }
    }

    /** The sticker's image, decoded at most [maxSide] px on its longest side; cached. Blocking on first use. */
    fun image(id: String, maxSide: Int = StickerArt.MAX_SIDE): Bitmap? {
        val key = "$id@$maxSide"
        images.get(key)?.let { return it }
        val file = imageFile(id)
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        images.put(key, bitmap)
        return bitmap
    }

    // --- Files ---------------------------------------------------------------------------------

    private fun load() {
        val file = File(directory, INDEX)
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText())
            val stickers = root.optJSONArray("stickers") ?: JSONArray()
            _stickers.value = (0 until stickers.length()).mapNotNull { i ->
                val o = stickers.getJSONObject(i)
                val id = o.getString("id")
                if (!imageFile(id).exists()) return@mapNotNull null
                Sticker(
                    id = id,
                    border = runCatching { StickerBorder.valueOf(o.optString("border")) }.getOrDefault(StickerBorder.NONE),
                    thickness = o.optDouble("thickness", 0.5).toFloat(),
                    width = o.optInt("width"),
                    height = o.optInt("height"),
                    createdAt = o.optLong("createdAt"),
                )
            }
            val known = _stickers.value.mapTo(HashSet()) { it.id }
            val placements = root.optJSONArray("placements") ?: JSONArray()
            _placements.value = (0 until placements.length()).mapNotNull { i ->
                val o = placements.getJSONObject(i)
                StickerPlacement(
                    id = o.getString("id"),
                    stickerId = o.getString("stickerId"),
                    x = o.optDouble("x", 0.5).toFloat(),
                    y = o.optDouble("y", 0.3).toFloat(),
                    scale = o.optDouble("scale", DEFAULT_SCALE.toDouble()).toFloat(),
                    rotation = o.optDouble("rotation", 0.0).toFloat(),
                    z = o.optInt("z"),
                ).takeIf { it.stickerId in known }
            }
        } catch (e: Exception) {
            // A damaged index loses arrangements, never the app: start empty (the images stay).
            Log.w(TAG, "sticker index unreadable (${e.javaClass.simpleName})")
        }
    }

    private fun persist(after: () -> Unit = {}) {
        val stickers = _stickers.value
        val placements = _placements.value
        io.execute {
            try {
                val root = JSONObject()
                    .put("version", 1)
                    .put("stickers", JSONArray(stickers.map { s ->
                        JSONObject().put("id", s.id).put("border", s.border.name).put("thickness", s.thickness.toDouble())
                            .put("width", s.width).put("height", s.height).put("createdAt", s.createdAt)
                    }))
                    .put("placements", JSONArray(placements.map { p ->
                        JSONObject().put("id", p.id).put("stickerId", p.stickerId).put("x", p.x.toDouble()).put("y", p.y.toDouble())
                            .put("scale", p.scale.toDouble()).put("rotation", p.rotation.toDouble()).put("z", p.z)
                    }))
                val tmp = File(directory, "$INDEX.tmp")
                tmp.writeText(root.toString())
                if (!tmp.renameTo(File(directory, INDEX))) {
                    File(directory, INDEX).delete()
                    tmp.renameTo(File(directory, INDEX))
                }
                after()
            } catch (e: Exception) {
                Log.w(TAG, "couldn't save stickers (${e.javaClass.simpleName})")
            }
        }
    }

    /** Waits for every write so far (tests, and before the app is turned off). */
    fun flush() {
        val done = java.util.concurrent.CountDownLatch(1)
        io.execute { done.countDown() }
        done.await(2, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun writePng(file: File, bitmap: Bitmap) {
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        private const val TAG = "PodiumStickers"
        private const val INDEX = "stickers.json"
        private const val IMAGE_CACHE_BYTES = 24 * 1024 * 1024
        const val DEFAULT_SCALE = 0.32f
        const val MIN_SCALE = 0.06f
        const val MAX_SCALE = 1.2f
    }
}
