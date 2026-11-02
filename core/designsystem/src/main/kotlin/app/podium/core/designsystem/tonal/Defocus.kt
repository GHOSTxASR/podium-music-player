package app.podium.core.designsystem.tonal

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Carbon (D-101): while a menu or a notice is open, what lies behind it goes out of focus — the
 * display softens all around the panel, as a lens focusing on what's in front, and the panel itself
 * stays solid and sharp. It is the content that blurs, never the menu: no glass.
 *
 * [amount] (0–1) is read while drawing, so opening and closing animate without recomposing. At 0
 * there is no effect at all (nothing is blurred, nothing costs). Android 12 and later; before that
 * the menu's dim alone does the work.
 */
fun Modifier.defocus(amount: () -> Float): Modifier =
    if (Build.VERSION.SDK_INT < 31) this else graphicsLayer {
        val a = amount()
        renderEffect = if (a > 0.01f) {
            val r = DefocusRadius.toPx() * a
            BlurEffect(r, r, TileMode.Clamp)
        } else null
    }

/** Far enough that nothing behind a menu can be read, near enough that it's still the same screen. */
private val DefocusRadius = 14.dp

/** Whether [defocus] can blur on this phone; without it, overlays keep a deeper dim instead. */
@get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
val canDefocus: Boolean get() = Build.VERSION.SDK_INT >= 31
