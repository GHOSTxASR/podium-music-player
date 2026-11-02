package app.podium.core.designsystem.orb

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.podium.core.designsystem.glass.GlassMaterial
import app.podium.core.designsystem.glass.glass
import app.podium.core.designsystem.theme.DisplayStyle
import app.podium.core.designsystem.theme.PodiumTheme
import app.podium.core.designsystem.tonal.ContinuousShape
import app.podium.core.designsystem.tonal.LocalAlbumTone
import app.podium.core.designsystem.tonal.SurfaceLevel
import app.podium.core.designsystem.tonal.tonalSurface

/**
 * The live orb (D-107): Now Playing's play key, as a creature. A body in the theme's own material
 * with two eyes, moved every frame by an [OrbFace] — it dances with the music, watches the covers go
 * by when the song changes, follows the Wheel's focus lens, and goes through its whole range of
 * feeling while the music is stopped.
 *
 * It carries no play or pause glyph: its shape says which it is. Playing, it is a perfect round orb;
 * stopped, it settles into a wider pebble ([SettledCorner] of its diameter at every corner) — the
 * shape changes on a recomposition, so the glass's own lens and rim follow it, and everything else
 * (the squash, the bob, the roll, the lean, the eyes, the stars) is read in the draw phase and
 * recomposes nothing. The title bar's play light says the state in words for anyone who wants it
 * plainly.
 *
 * Each theme paints the same body:
 * - **Carbon** (`TONAL`): solid ivory white, lit from above like every tonal surface.
 * - **Glass**: a thicker frost than a key's ([GlassMaterial.Orb]), so it reads as a body and not a
 *   pane, catching the cover's light in its rim.
 * - **Bone** (`INDUSTRIAL`): drawn, not filled — a hairline and its ink eyes, as the instrument draws
 *   everything.
 *
 * Reduced motion: the shape still changes, instantly, and the face holds still (the mind keeps it so).
 */
@Composable
fun LiveOrb(
    diameter: Dp,
    settled: Boolean,
    face: OrbFace,
    pressed: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = PodiumTheme.colors
    val tone = LocalAlbumTone.current
    val reduced = PodiumTheme.motion.reduced
    val corner by animateDpAsState(
        if (settled) diameter * SettledCorner else diameter / 2f,
        if (reduced) tween(0) else spring(dampingRatio = 0.74f, stiffness = 420f),
        label = "orbShape",
    )
    val shape = orbShape(corner)
    val ink = if (colors.isTonal) OrbIvoryInk else colors.labelPrimary
    // The stars have to be seen against the display, not against the body: Carbon's ivory would
    // swallow its own ink, so there they are the album's one accent.
    val sparks = if (colors.isTonal) tone.accent else colors.labelPrimary
    Box(modifier.size(diameter)) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    @Suppress("UNUSED_EXPRESSION") face.revision
                    scaleX = face.scaleX
                    scaleY = face.scaleY
                    translationX = face.shiftX * diameter.toPx() * OrbFace.RollTravel
                    translationY = -face.bob * diameter.toPx() * OrbFace.BobTravel
                    rotationZ = face.tilt
                }
                .orbBody(shape, pressed),
        )
        Canvas(Modifier.matchParentSize()) {
            @Suppress("UNUSED_EXPRESSION") face.revision
            drawOrbSparks(face, sparks)
            drawOrbEyes(face, ink)
        }
    }
}

/** The body in the display's material. */
@Composable
private fun Modifier.orbBody(shape: Shape, pressed: () -> Boolean): Modifier {
    val colors = PodiumTheme.colors
    return when (colors.style) {
        DisplayStyle.TONAL -> tonalSurface(shape, SurfaceLevel.High, elevation = 6.dp, pressed = pressed, fill = { OrbIvory })
        DisplayStyle.GLASS -> glass(GlassMaterial.Orb, shape, pressed = pressed())
        DisplayStyle.INDUSTRIAL -> border(1.dp, colors.labelSecondary, shape)
    }
}

/** Continuous corners everywhere but the instrument, which keeps its own (D-103). A corner of half the diameter is a circle. */
@Composable
private fun orbShape(corner: Dp): Shape =
    if (PodiumTheme.colors.isIndustrial) RoundedCornerShape(corner) else ContinuousShape(corner)

/** Stopped, every corner is this share of the diameter: round enough to be the same creature, square enough to have sat down. */
private const val SettledCorner = 0.34f
