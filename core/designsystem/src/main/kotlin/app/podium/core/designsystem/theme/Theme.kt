package app.podium.core.designsystem.theme

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.podium.core.designsystem.glass.GlassTier
import app.podium.core.designsystem.glass.LocalGlassTier
import app.podium.core.designsystem.type.DefaultType
import app.podium.core.designsystem.type.LocalTypographyPreset
import app.podium.core.designsystem.type.LocalLyricsTypeface
import app.podium.core.designsystem.type.LyricsTypeface
import app.podium.core.designsystem.type.PodiumType
import app.podium.core.designsystem.type.TypographyPreset

/**
 * Podium's motion language (animation-system.md §2, D-65). Seven kinds of motion, each meaning one
 * thing, none borrowing another's curve:
 *
 * - TACTILE: a key under the finger. Down hard, no bounce; up with a whisper of spring.
 * - MECHANICAL: the Wheel's own parts. The material follows the finger; only the detent settles.
 * - FOCUS: the selector moving through a list. Glides a step; locks on during a spin.
 * - SPATIAL: moving along the paper. Leaves on the frame of the press, settles long and soft.
 * - CONTENT: the music changing in place. Short; old content gets out of the way.
 * - DRAMATIC: entering a piece of music. A little slower, with a settle — still interruptible.
 * - PERSONAL: the object, its space and stickers. The hand drives; release keeps its momentum.
 *
 * Interactive motion is springs (they retarget and keep their speed); choreographed moves are tweens
 * on curves that start moving at once. Nothing waits for an animation, and nothing is delayed to
 * look animated.
 */
@Immutable
data class PodiumMotion(val reduced: Boolean) {
    // FOCUS
    fun <T> focus() = spring<T>(stiffness = 1400f, dampingRatio = 0.86f)
    fun <T> focusFast() = spring<T>(stiffness = 2400f, dampingRatio = Spring.DampingRatioNoBouncy)

    /** The mini player and Now Playing rising from it: a sheet, not a column. */
    fun navigateOffset() = spring(stiffness = 380f, dampingRatio = 0.92f, visibilityThreshold = IntOffset(1, 1))

    // SPATIAL
    /** Part of a move along the paper, on [Spatial]. */
    fun <T> spatial(durationMillis: Int = SpatialMillis, delayMillis: Int = 0) = tween<T>(durationMillis, delayMillis, Spatial)

    // CONTENT
    fun <T> contentIn() = tween<T>(durationMillis = if (reduced) 120 else 160, easing = Standard)
    fun <T> contentOut() = tween<T>(durationMillis = 90, easing = Standard)

    fun <T> fadeFast() = tween<T>(durationMillis = if (reduced) 0 else 120, easing = Standard)
    fun <T> fadeStandard() = tween<T>(durationMillis = if (reduced) 150 else 220, easing = Standard)
    fun <T> atmosphere() = tween<T>(durationMillis = if (reduced) 200 else 900, easing = EmphasizedDecelerate)

    companion object {
        val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
        val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

        /**
         * SPATIAL (D-65, after D-60): one curve end to end that leaves on the frame of the press —
         * 14 % of the way after 17 ms, 71 % after 100 ms — and settles with a long, soft finish.
         * D-60's curve set off from rest and sat still for the first five frames.
         */
        val Spatial = CubicBezierEasing(0.2f, 0.7f, 0.2f, 1f)

        /** One move along the paper. */
        const val SpatialMillis = 420

        // TACTILE
        /** A key going down: ~50 ms, no bounce. */
        fun <T> pressIn() = spring<T>(stiffness = 6000f, dampingRatio = 0.9f)

        /** A key coming back up: a settle of a fraction of a percent, ~160 ms. */
        fun <T> pressOut() = spring<T>(stiffness = 1500f, dampingRatio = 0.6f)

        /** Something small arriving under the finger (a context menu): quick, barely any overshoot. */
        fun <T> pop() = spring<T>(stiffness = 1200f, dampingRatio = 0.82f)

        // MECHANICAL
        /** The Wheel's band clicking into its nearest detent once the finger lifts. */
        fun <T> detent() = spring<T>(stiffness = 1800f, dampingRatio = 0.75f)

        // DRAMATIC
        /** Entering a piece of music: the cover settling into its place. ~300 ms. */
        fun <T> dramatic() = spring<T>(stiffness = 220f, dampingRatio = 0.85f)

        // PERSONAL
        /** The object settling into its space once the fingers let go (or without them). ~380 ms. */
        fun <T> settleIn() = spring<T>(stiffness = 200f, dampingRatio = 0.86f)

        /** The object coming back to being the flat Podium: decisive, no overshoot. ~380 ms. */
        fun <T> settleBack() = spring<T>(stiffness = 300f, dampingRatio = 1f)

        /** Rearranging the space (Podium | Settings, the editing pose). ~330 ms. */
        fun <T> rearrange() = spring<T>(stiffness = 220f, dampingRatio = 0.88f)
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

/**
 * The display's visual system (D-29, D-41). Glass follows the system light/dark setting; Carbon and
 * Bone are deliberate fixed looks — matte, monochrome, no glass anywhere on the display. Custom is
 * the same matte instrument on the listener's own background (a colour or a picture), its ink
 * chosen for contrast.
 */
enum class DisplayTheme(val label: String) {
    GLASS("Glass"),
    CARBON("Carbon"),
    BONE("Bone"),
    CUSTOM("Custom"),
    ;

    val isIndustrial: Boolean get() = this != GLASS
}

@Composable
fun PodiumTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    glassTier: GlassTier = deviceGlassTier(),
    displayTheme: DisplayTheme = DisplayTheme.GLASS,
    /** The listener's customization of the display: font, background, contrast (D-41). */
    display: VirtualDisplay = VirtualDisplay(),
    /** The decoded background picture, when [display] has one and it could be read. */
    displayImage: DisplayImage? = null,
    content: @Composable () -> Unit,
) {
    val reducedMotion = rememberReducedMotion()
    val colors = remember(displayTheme, darkTheme, display, displayImage) { DisplayColors.colors(displayTheme, darkTheme, display, displayImage) }
    val surface = remember(displayTheme, display, displayImage) { DisplayColors.surface(displayTheme, display, displayImage) }
    val preset = TypographyPreset.of(display.font)
    CompositionLocalProvider(
        LocalPodiumColors provides colors,
        LocalDisplaySurface provides surface,
        LocalTypographyPreset provides preset,
        LocalLyricsTypeface provides LyricsTypeface.of(display.lyricsFont, preset),
        LocalPodiumType provides preset.type,
        LocalPodiumMotion provides PodiumMotion(reducedMotion),
        LocalGlassTier provides if (displayTheme.isIndustrial) GlassTier.Solid else glassTier,
        content = content,
    )
}

/**
 * "Remove animations" (animator duration scale 0) means reduced motion (animation-system.md §5),
 * followed live: switching it while Podium runs takes effect at once, not at the next launch.
 */
@Composable
private fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    var reduced by remember(resolver) { mutableStateOf(animationsRemoved(resolver)) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = animationsRemoved(resolver)
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}

private fun animationsRemoved(resolver: ContentResolver): Boolean =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
