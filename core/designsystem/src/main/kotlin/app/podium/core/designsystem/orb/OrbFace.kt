package app.podium.core.designsystem.orb

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Carbon's orb (D-107): Podium's own warm ivory, solid — the one white thing on the display. */
val OrbIvory: Color = OrbRenderer.Ivory

/** Its eyes on that ivory: the warm near-black the lyrics' orb keeps in its hollow. */
val OrbIvoryInk: Color = Color(0xFF1B1916)

/**
 * The live orb's face at one frame (D-107): how its body is sitting and what its eyes are doing. The
 * orb is Now Playing's play key, and it is a creature — a mind ([app.podium.feature.nowplaying] has
 * it) moves these every frame from the music, the screen and its own moods.
 *
 * Nearly everything it feels is said with two eyes: how tall and wide they are, which way they lean,
 * whether one is narrower than the other, how they bow, and where they look. The body gives the rest
 * — a lean, a bob, a squash, a roll right over — and a burst of little stars is the one thing it ever
 * throws off itself.
 *
 * Plain floats, so moving the face costs nothing: whoever moves it calls [moved] once, and the orb's
 * draw blocks read [revision] before the floats. Nothing recomposes; only the orb redraws.
 *
 * Everything is a multiple of the orb's diameter or of the eye's resting size, so one face reads the
 * same at any size.
 */
@Stable
class OrbFace {

    /** Bumped once per frame by whoever moves the face; read it in a draw block to redraw. */
    var revision by mutableLongStateOf(0L)
        private set

    /** The face has moved: the orb redraws this frame. */
    fun moved() {
        revision++
    }

    // The body.

    /** Squash and stretch, about its middle: 1 is its resting shape. */
    var scaleX = 1f
    var scaleY = 1f

    /** How far it rides above its place, in [BobTravel] of its diameter (negative: below). */
    var bob = 0f

    /** How far it has rolled forward of its place, in [RollTravel] of its diameter. */
    var shiftX = 0f

    /** Its lean, in degrees (positive: clockwise). A whole turn is a forward roll, eyes and all. */
    var tilt = 0f

    // The eyes.

    /** Where it is looking, −1 to 1 of its reach ([GazeReachX], [GazeReachY]); 0 is straight ahead. */
    var gazeX = 0f
    var gazeY = 0f

    /** The eyes' size, as a multiple of their resting size: wide awake, or squished to a line. */
    var eyeWidth = 1f
    var eyeHeight = 1f

    /**
     * How far each eye bows across its long axis, −1 to 1: a wide eye arching up in the middle is
     * happy, down is glum; a tall eye bows away from the other, which is softer than standing straight.
     */
    var eyeBend = 0f

    /**
     * How far each eye leans, in degrees, mirrored about the middle: above 0 the inner ends drop and
     * it is cross, below 0 they rise and it is hurt.
     */
    var eyeLean = 0f

    /**
     * How differently the two eyes are open, −1 to 1: one narrowed and one wide is how it asks a
     * question. Positive narrows the left eye.
     */
    var eyeSkew = 0f

    /** A burst of little stars off its head, 0 (none) through 1 (gone). */
    var spark = 0f

    companion object {
        /** The eyes sit this far above the body's middle. */
        const val EyeAbove = 0.03f

        /** Half the distance between the eyes, and their resting size, as shares of the diameter. */
        const val EyeGap = 0.165f
        const val EyeWidth = 0.105f
        const val EyeHeight = 0.30f

        /**
         * How far the eyes travel at a full look. Far enough to read across the display at the orb's
         * own size — a glance at the focus lens has to be plain from arm's length — and still well
         * inside the body: an eye's outer edge reaches 0.37 of the diameter from the middle, of 0.5.
         */
        const val GazeReachX = 0.15f
        const val GazeReachY = 0.10f

        /** How far the body rides at a full [bob], and rolls at a full [shiftX]. */
        const val BobTravel = 0.055f
        const val RollTravel = 0.16f

        /** How much of the body's squash reaches the eyes: enough to live with it, not to distort. */
        const val EyeFollow = 0.5f

        /** How much of its height one eye gives up at a full [eyeSkew]. */
        const val SkewReach = 0.5f
    }
}

/**
 * The orb's two eyes, in the ink the theme gives them, over the whole of this [DrawScope] (the orb's
 * own square). The eyes ride the body's lean, roll and bob in full and its squash halfway, so they
 * keep their shape while the body gives.
 *
 * One eye is a line of the thickness of its short side, as long as its long side, with round ends —
 * so it is a capsule standing up at rest, a flat dash shut, and a wide pill squinting — bowed across
 * its length by [OrbFace.eyeBend] and leaned by [OrbFace.eyeLean]. The two descriptions meet exactly
 * where the eye is as wide as it is tall (a dot), so widening and squishing pass through it without a
 * seam.
 */
fun DrawScope.drawOrbEyes(face: OrbFace, ink: Color) {
    val d = size.minDimension
    if (d <= 0f) return
    val middle = Offset(size.width / 2f, size.height / 2f)
    val gapX = d * OrbFace.EyeGap
    val gx = face.gazeX.coerceIn(-1f, 1f) * d * OrbFace.GazeReachX + face.shiftX * d * OrbFace.RollTravel
    val gy = face.gazeY.coerceIn(-1f, 1f) * d * OrbFace.GazeReachY
    val y = middle.y - d * OrbFace.EyeAbove - face.bob * d * OrbFace.BobTravel + gy
    val give = OrbFace.EyeFollow
    val skew = face.eyeSkew.coerceIn(-1f, 1f)
    val bend = face.eyeBend.coerceIn(-1f, 1f)
    scale(1f + (face.scaleX - 1f) * give, 1f + (face.scaleY - 1f) * give, middle) {
        rotate(face.tilt, middle) {
            // The left eye first; `side` is −1 on the left, 1 on the right.
            for (side in -1..1 step 2) {
                val at = Offset(middle.x + side * gapX + gx, y)
                val w = d * OrbFace.EyeWidth * face.eyeWidth.coerceIn(0.04f, 3f)
                val open = face.eyeHeight * (1f + side * skew * OrbFace.SkewReach)
                val h = d * OrbFace.EyeHeight * open.coerceIn(0.02f, 3f)
                val thick = min(w, h)
                val along = (max(w, h) - thick) / 2f
                // A clockwise turn drops the left eye's right-hand end, so the inner ends fall together.
                rotate(-side * face.eyeLean, at) {
                    if (along <= 0.25f) {
                        // As wide as it is tall: a dot, with nothing to bow.
                        drawCircle(ink, thick / 2f, at)
                        return@rotate
                    }
                    // The control point is twice the rise, so the curve's own middle lands on the rise.
                    val rise = bend * along * BendReach * 2f
                    val path = Path()
                    if (h >= w) {
                        path.moveTo(at.x, at.y - along)
                        path.quadraticTo(at.x + side * rise, at.y, at.x, at.y + along)
                    } else {
                        path.moveTo(at.x - along, at.y)
                        path.quadraticTo(at.x, at.y - rise, at.x + along, at.y)
                    }
                    drawPath(path, ink, style = Stroke(width = thick, cap = StrokeCap.Round))
                }
            }
        }
    }
}

/**
 * The little stars it throws off its head when it is beside itself (D-107): a few four-pointed
 * sparkles fanning up out of the orb, growing as they go and fading out. Drawn past the orb's own
 * square, so nothing of it must clip.
 */
fun DrawScope.drawOrbSparks(face: OrbFace, color: Color) {
    val t = face.spark
    if (t <= 0f || t >= 1f) return
    val d = size.minDimension
    if (d <= 0f) return
    val from = Offset(
        size.width / 2f + face.shiftX * d * OrbFace.RollTravel,
        size.height / 2f - face.bob * d * OrbFace.BobTravel,
    )
    // Out quickly, then drifting: the throw eases off. They are brightest halfway and gone by the end.
    val flight = 1f - (1f - t) * (1f - t)
    val show = sin(Math.PI.toFloat() * t)
    for (i in SparkAngles.indices) {
        val a = Math.toRadians(SparkAngles[i].toDouble()).toFloat()
        val reach = d * (0.46f + 0.40f * SparkReach[i]) * flight
        val at = Offset(from.x + cos(a) * reach, from.y + sin(a) * reach)
        drawSpark(at, d * SparkSize[i] * (0.45f + 0.55f * show), color.copy(alpha = color.alpha * show), t * 60f + i * 27f)
    }
}

/** One four-pointed sparkle: four points around [at], its sides drawn in toward the middle. */
private fun DrawScope.drawSpark(at: Offset, radius: Float, color: Color, spin: Float) {
    if (radius <= 0.2f) return
    val inner = radius * 0.24f
    val path = Path()
    for (k in 0 until 4) {
        val tip = Math.toRadians((spin + k * 90f).toDouble()).toFloat()
        val waist = Math.toRadians((spin + k * 90f + 45f).toDouble()).toFloat()
        val next = Math.toRadians((spin + k * 90f + 90f).toDouble()).toFloat()
        if (k == 0) path.moveTo(at.x + cos(tip) * radius, at.y + sin(tip) * radius)
        path.quadraticTo(
            at.x + cos(waist) * inner,
            at.y + sin(waist) * inner,
            at.x + cos(next) * radius,
            at.y + sin(next) * radius,
        )
    }
    path.close()
    drawPath(path, color)
}

/** How far an eye's middle may leave the line between its ends, as a share of its half-length. */
private const val BendReach = 0.40f

/** Where the stars go (degrees, up is −90), how far, and how big — a fan over its head, never even. */
private val SparkAngles = floatArrayOf(-142f, -112f, -86f, -58f, -32f)
private val SparkReach = floatArrayOf(0.55f, 0.82f, 1f, 0.72f, 0.48f)
private val SparkSize = floatArrayOf(0.070f, 0.052f, 0.086f, 0.048f, 0.066f)
