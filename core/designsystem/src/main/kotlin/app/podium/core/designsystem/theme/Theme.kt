package app.podium.core.designsystem.theme

import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.podium.core.designsystem.glass.GlassTier
import app.podium.core.designsystem.glass.LocalGlassTier
import app.podium.core.designsystem.type.DefaultType
import app.podium.core.designsystem.type.PodiumType

/** Motion tokens (animation-system.md §2). */
@Immutable
data class PodiumMotion(val reduced: Boolean) {
    fun <T> focus() = spring<T>(stiffness = 1400f, dampingRatio = 0.86f)
    fun <T> focusFast() = spring<T>(stiffness = 2400f, dampingRatio = Spring.DampingRatioNoBouncy)
    fun <T> press() = spring<T>(stiffness = 2000f, dampingRatio = 0.7f)
    fun <T> navigate() = spring<T>(stiffness = 380f, dampingRatio = 0.92f)
    fun <T> sheet() = spring<T>(stiffness = 300f, dampingRatio = 0.88f)
    fun <T> boundary() = spring<T>(stiffness = 3000f, dampingRatio = 0.5f)
    fun navigateOffset() = spring(stiffness = 380f, dampingRatio = 0.92f, visibilityThreshold = IntOffset(1, 1))

    fun <T> fadeFast() = tween<T>(durationMillis = if (reduced) 0 else 120, easing = Standard)
    fun <T> fadeStandard() = tween<T>(durationMillis = if (reduced) 150 else 220, easing = Standard)
    fun <T> atmosphere() = tween<T>(durationMillis = if (reduced) 200 else 900, easing = EmphasizedDecelerate)

    companion object {
        val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    }
}

/** Spacing scale (dp) and component metrics (design-system.md §4). */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    val gutter = 16.dp
    val menuRow = 52.dp
    val trackRow = 60.dp
    val titleBar = 52.dp
    val miniPlayer = 56.dp
}

val LocalPodiumColors = staticCompositionLocalOf { DarkColors }
val LocalPodiumType = staticCompositionLocalOf { DefaultType }
val LocalPodiumMotion = staticCompositionLocalOf { PodiumMotion(reduced = false) }

object PodiumTheme {
    val colors: PodiumColors @Composable get() = LocalPodiumColors.current
    val type: PodiumType @Composable get() = LocalPodiumType.current
    val motion: PodiumMotion @Composable get() = LocalPodiumMotion.current
}

/** Glass fidelity this device can render (ADR-007): refraction needs API 33, blur API 31. */
fun deviceGlassTier(): GlassTier = when {
    Build.VERSION.SDK_INT >= 33 -> GlassTier.Full
    Build.VERSION.SDK_INT >= 31 -> GlassTier.Blur
    else -> GlassTier.Solid
}

@Composable
fun PodiumTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    glassTier: GlassTier = deviceGlassTier(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // "Remove animations" (animator duration scale 0) means reduced motion (animation-system.md §5).
    val reducedMotion = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    CompositionLocalProvider(
        LocalPodiumColors provides if (darkTheme) DarkColors else LightColors,
        LocalPodiumType provides DefaultType,
        LocalPodiumMotion provides PodiumMotion(reducedMotion),
        LocalGlassTier provides glassTier,
        content = content,
    )
}
