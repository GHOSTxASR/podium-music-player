package app.podium.core.designsystem.shell

import android.graphics.Bitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.random.Random

private const val TILE = 128

/**
 * A fixed tile of fine, balanced noise: light and dark specks around zero, so grain adds texture
 * without shifting the finish's colour. Seeded, so the texture never changes between launches.
 */
private val noiseTile: ImageBitmap by lazy {
    val random = Random(26)
    val pixels = IntArray(TILE * TILE) {
        // Sum of uniforms ≈ gaussian; centred on zero.
        val v = (random.nextFloat() + random.nextFloat() + random.nextFloat()) / 1.5f - 1f
        val alpha = (kotlin.math.abs(v) * 255).toInt().coerceIn(0, 255)
        if (v >= 0) (alpha shl 24) or 0xFFFFFF else (alpha shl 24)
    }
    Bitmap.createBitmap(pixels, TILE, TILE, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Overlays grain at [amount] (0–1). Clip before this modifier to keep grain inside a shape. */
fun Modifier.grain(amount: () -> Float): Modifier = drawWithCache {
    val brush = ShaderBrush(ImageShader(noiseTile, TileMode.Repeated, TileMode.Repeated))
    onDrawWithContent {
        drawContent()
        val a = amount()
        if (a > 0f) drawRect(brush, alpha = (a * 0.38f).coerceIn(0f, 1f))
    }
}
