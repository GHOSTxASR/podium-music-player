package app.podium.feature.nowplaying

import androidx.compose.ui.geometry.Offset
import app.podium.core.designsystem.orb.OrbFace
import app.podium.core.designsystem.orb.OrbFrame
import app.podium.player.api.AudioPulse
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the live orb does (D-107): it dances while the music plays and now and then rolls right over
 * with delight; stopped, it works through its whole story — alert, questioning, curious, cross, beside
 * itself, sad, asleep — watches the covers go by on a skip, follows the focus lens, feels a press, and
 * does none of it at all with reduced motion.
 */
class OrbMindTest {

    /** One frame of the face, kept (the real one is reused every frame). */
    private class Look(
        val scaleX: Float,
        val scaleY: Float,
        val bob: Float,
        val shiftX: Float,
        val tilt: Float,
        val spark: Float,
        val gazeX: Float,
        val gazeY: Float,
        val eyeWidth: Float,
        val eyeHeight: Float,
        val eyeBend: Float,
        val eyeLean: Float,
        val eyeSkew: Float,
        val mood: OrbMind.Mood,
        val atRest: Boolean,
        val untilMove: Long,
    ) {
        /** What this frame draws differently from [other], or null when it draws exactly the same. */
        fun differs(other: Look): String? {
            val fields = listOf<Triple<String, Float, Float>>(
                Triple("scaleX", scaleX, other.scaleX),
                Triple("scaleY", scaleY, other.scaleY),
                Triple("bob", bob, other.bob),
                Triple("shiftX", shiftX, other.shiftX),
                Triple("tilt", tilt, other.tilt),
                Triple("spark", spark, other.spark),
                Triple("gazeX", gazeX, other.gazeX),
                Triple("gazeY", gazeY, other.gazeY),
                Triple("eyeWidth", eyeWidth, other.eyeWidth),
                Triple("eyeHeight", eyeHeight, other.eyeHeight),
                Triple("eyeBend", eyeBend, other.eyeBend),
                Triple("eyeLean", eyeLean, other.eyeLean),
                Triple("eyeSkew", eyeSkew, other.eyeSkew),
            )
            return fields.firstOrNull { it.second != it.third }?.let { "${it.first} ${it.third} -> ${it.second}" }
        }
    }

    /**
     * A song as the meter hears it (the shape [OrbDriveTest] uses): a bass kick every 500 ms over a
     * steady body, with bright hats on the off-beat, read in the meter's own 10 ms steps.
     */
    private fun song(ms: Double, out: FloatArray): FloatArray {
        val at = ms - ms % 10.0
        val kick = exp(-(at % 500.0) / 90.0).toFloat()
        val hat = exp(-((at + 250.0) % 500.0) / 40.0).toFloat()
        out[AudioPulse.WHOLE] = 0.12f + 0.2f * kick + 0.04f * hat
        out[AudioPulse.LOW] = 0.04f + 0.3f * kick
        out[AudioPulse.HIGH] = 0.004f + 0.03f * hat
        return out
    }

    private class Run(val frames: List<Look>, val mind: OrbMind)

    /**
     * Steps the mind for [seconds]. With [levels] it runs the real chain — measured levels through
     * [OrbDrive] into the mind — which is how the screen does it; without, the four values are given
     * straight, to put one mapping under the light.
     */
    private fun run(
        seconds: Double,
        playing: Boolean,
        levels: ((Double, FloatArray) -> FloatArray)? = null,
        energy: Float = 0f,
        low: Float = 0f,
        high: Float = 0f,
        glint: Float = 0f,
        look: Offset? = null,
        pressed: Boolean = false,
        reduced: Boolean = false,
        frameMs: Float = 1000f / 90,
        mind: OrbMind = OrbMind(),
        face: OrbFace = OrbFace(),
        drive: OrbDrive = OrbDrive(),
        onFrame: (Double, OrbMind) -> Unit = { _, _ -> },
    ): Run {
        val frames = ArrayList<Look>()
        val buf = FloatArray(3)
        val frame = OrbFrame()
        var t = 0.0
        while (t < seconds * 1000) {
            onFrame(t, mind)
            if (levels != null) {
                drive.update(if (playing) levels(t, buf) else null, frameMs, playing, frame)
                mind.update(frameMs, playing, frame.energy, frame.low, frame.high, frame.glint, look, pressed, reduced, face)
            } else {
                val on = if (playing) 1f else 0f
                mind.update(frameMs, playing, energy * on, low * on, high * on, glint * on, look, pressed, reduced, face)
            }
            frames += Look(
                face.scaleX, face.scaleY, face.bob, face.shiftX, face.tilt, face.spark,
                face.gazeX, face.gazeY, face.eyeWidth, face.eyeHeight, face.eyeBend, face.eyeLean, face.eyeSkew,
                mind.feeling, mind.atRest, mind.millisUntilMove(),
            )
            t += frameMs
        }
        return Run(frames, mind)
    }

    private fun List<Look>.spread(of: (Look) -> Float): Float = maxOf(of) - minOf(of)

    private fun List<Look>.inMood(m: OrbMind.Mood) = filter { it.mood == m }

    @Test
    fun `playing, it dances - the bass squashes it and squishes its eyes`() {
        val frames = run(8.0, playing = true, levels = ::song).frames.drop(180).inMood(OrbMind.Mood.Dance)
        assertTrue(frames.spread { it.scaleY } > 0.08f, "the body gives with the bass: ${frames.spread { it.scaleY }}")
        assertTrue(frames.spread { it.scaleX } > 0.06f, "and spreads as it does")
        assertTrue(frames.spread { it.bob } > 0.2f, "it bobs")
        assertTrue(frames.spread { it.tilt } > 1f, "it leans")
        assertTrue(frames.spread { it.eyeHeight } > 0.1f, "the eyes squish on the beat")
        assertTrue(frames.spread { it.gazeX } > 0.2f, "it looks about while it dances")
        assertTrue(frames.all { it.eyeBend > 0.35f }, "the eyes stay arched up through a loud song")
        assertTrue(frames.all { it.eyeWidth > 1.1f }, "and widened")
    }

    @Test
    fun `a quiet song lets its eyes stand up again`() {
        val quiet = run(6.0, playing = true, energy = 0.1f, low = 0.05f).frames.drop(90)
        assertTrue(quiet.all { it.eyeBend < 0.05f }, "no happy squint in a quiet passage: ${quiet.maxOf { it.eyeBend }}")
        assertTrue(quiet.all { it.eyeHeight > 0.7f || it.eyeHeight < 0.2f }, "the eyes are open, bar a blink")
    }

    @Test
    fun `nothing the music does ever makes the face jump`() {
        val frames = run(18.0, playing = true, levels = ::song, frameMs = 1000f / 120).frames
        fun worst(pick: (Look) -> Float): Float {
            var worst = 0f
            for (i in 1 until frames.size) worst = maxOf(worst, abs(pick(frames[i]) - pick(frames[i - 1])))
            return worst
        }
        // The body and the gaze are continuous: the music only ever pushes them.
        val smooth = mapOf<String, (Look) -> Float>(
            "scaleX" to { it.scaleX }, "scaleY" to { it.scaleY }, "bob" to { it.bob },
            "gazeX" to { it.gazeX }, "gazeY" to { it.gazeY }, "eyeWidth" to { it.eyeWidth },
        )
        for ((name, pick) in smooth) assertTrue(worst(pick) < 0.06f, "largest step in a frame of $name: ${worst(pick)}")
        // A blink is meant to be quick, but it is still a movement and not a cut.
        assertTrue(worst { it.eyeHeight } < 0.25f, "a blink closes over frames, not in one: ${worst { it.eyeHeight }}")
    }

    @Test
    fun `stopped, it works through the whole story in order`() {
        val frames = run(140.0, playing = false).frames
        val order = frames.map { it.mood }.distinct()
        assertEquals(
            listOf(
                OrbMind.Mood.Calm, OrbMind.Mood.Alert, OrbMind.Mood.Question, OrbMind.Mood.Curious,
                OrbMind.Mood.Annoyed, OrbMind.Mood.Panic, OrbMind.Mood.Sad, OrbMind.Mood.Doze,
            ),
            order,
            "the moods arrive in order and none is skipped",
        )
        assertTrue(frames.last().mood == OrbMind.Mood.Doze, "and it ends up asleep")
    }

    @Test
    fun `each mood looks like what it is`() {
        val frames = run(140.0, playing = false).frames
        // Alert: it sits up with its eyes wide open.
        val alert = frames.inMood(OrbMind.Mood.Alert)
        assertTrue(alert.any { it.eyeHeight > 1.2f }, "! wide eyes: ${alert.maxOf { it.eyeHeight }}")
        assertTrue(alert.any { it.bob > 0.2f }, "and it sits up")
        // Question: one eye narrowed, head on one side, looking up.
        val question = frames.inMood(OrbMind.Mood.Question)
        assertTrue(question.any { abs(it.eyeSkew) > 0.5f }, "? one eye narrowed: ${question.maxOf { abs(it.eyeSkew) }}")
        assertTrue(question.any { abs(it.tilt) > 7f }, "head on one side")
        assertTrue(question.any { it.gazeY < -0.3f }, "looking up and away")
        // Curious: it leans in to look.
        val curious = frames.inMood(OrbMind.Mood.Curious)
        assertTrue(curious.any { it.scaleX > 1.03f && it.scaleY > 1.03f }, "it comes closer")
        // Annoyed: the inner ends of its eyes drop.
        val annoyed = frames.inMood(OrbMind.Mood.Annoyed)
        assertTrue(annoyed.any { it.eyeLean > 12f }, "cross eyes: ${annoyed.maxOf { it.eyeLean }}")
        assertTrue(annoyed.any { it.eyeWidth > 1.3f && it.eyeHeight < 0.6f }, "narrowed and wide")
        // Panic: wide eyes, and it never stops moving.
        val panic = frames.inMood(OrbMind.Mood.Panic)
        assertTrue(panic.any { it.eyeHeight > 1.25f }, "eyes out on stalks")
        assertTrue(panic.spread { it.tilt } > 10f, "wobbling: ${panic.spread { it.tilt }}")
        assertTrue(panic.spread { it.bob } > 0.3f, "and hopping")
        assertTrue(panic.none { it.atRest }, "it does not hold still for a moment")
        // Sad: eyes bowed down, sagging, looking at the floor.
        val sad = frames.inMood(OrbMind.Mood.Sad)
        assertTrue(sad.any { it.eyeBend < -0.4f }, "eyes bowed down: ${sad.minOf { it.eyeBend }}")
        assertTrue(sad.any { it.eyeLean < -8f }, "their inner ends up")
        assertTrue(sad.any { it.gazeY > 0.4f }, "looking down")
        assertTrue(sad.any { it.scaleY < 0.97f }, "and sagging")
        // Doze: all but shut, breathing, and nodding off.
        val doze = frames.inMood(OrbMind.Mood.Doze)
        assertTrue(doze.any { it.eyeHeight < 0.2f }, "eyes all but shut: ${doze.minOf { it.eyeHeight }}")
        assertTrue(doze.spread { it.scaleY } > 0.01f, "breathing")
        assertTrue(doze.any { it.tilt > 4f }, "its head nods forward")
        assertTrue(doze.any { it.eyeHeight > 0.5f }, "and now and then it catches itself")
    }

    @Test
    fun `a feeling arrives rather than cuts`() {
        val frames = run(140.0, playing = false, frameMs = 1000f / 120).frames
        var worst = 0f
        var where = ""
        for (i in 1 until frames.size) {
            for ((name, pick) in mapOf<String, (Look) -> Float>(
                "eyeHeight" to { it.eyeHeight }, "eyeWidth" to { it.eyeWidth },
                "eyeLean" to { f -> f.eyeLean / 20f }, "eyeBend" to { it.eyeBend }, "eyeSkew" to { it.eyeSkew },
                "scaleY" to { it.scaleY },
            )) {
                val step = abs(pick(frames[i]) - pick(frames[i - 1]))
                if (step > worst) {
                    worst = step
                    where = name
                }
            }
        }
        assertTrue(worst < 0.25f, "largest step in a frame was $worst, in $where")
    }

    @Test
    fun `stopped and holding a feeling, it asks for no frames`() {
        val frames = run(140.0, playing = false).frames
        val resting = frames.filter { it.atRest }
        // Half the story is spent in moods that hold still between one thing and the next; the other
        // two never do, and are meant not to (it is making a scene, and then it is breathing).
        assertTrue(resting.size > frames.size / 5, "still much of the time: ${resting.size} of ${frames.size}")
        for (m in listOf(OrbMind.Mood.Calm, OrbMind.Mood.Alert, OrbMind.Mood.Question, OrbMind.Mood.Curious, OrbMind.Mood.Annoyed, OrbMind.Mood.Sad)) {
            assertTrue(frames.inMood(m).any { it.atRest }, "$m holds still between things")
        }
        // At rest means nothing changes: the mood is held, not animated.
        for (i in 1 until frames.size) {
            if (frames[i].atRest && frames[i - 1].atRest && frames[i].mood == frames[i - 1].mood) {
                assertEquals(null, frames[i].differs(frames[i - 1]), "a resting frame moved at ${frames[i].mood}")
            }
        }
        assertTrue(resting.maxOf { it.untilMove } > 1_000, "it can hold still for a second or more at a time")
    }

    @Test
    fun `playing, it gets carried away and rolls right over, with stars`() {
        val frames = run(30.0, playing = true, levels = ::song).frames
        val excited = frames.inMood(OrbMind.Mood.Excited)
        assertTrue(excited.isNotEmpty(), "it gets carried away once the music has worked on it a while")
        // A whole turn forward, off its place and back.
        assertTrue(excited.maxOf { it.tilt } > 330f, "it turns right over forward: ${excited.maxOf { it.tilt }}")
        assertTrue(excited.minOf { it.tilt } < 20f, "starting and ending the right way up")
        assertTrue(excited.maxOf { it.shiftX } > 0.8f, "rolling forward as it goes")
        assertTrue(excited.maxOf { it.bob } > 1f, "and up off its place")
        // Stars off its head as it lands, and gone again.
        assertTrue(excited.any { it.spark > 0.1f }, "stars")
        assertTrue(frames.last().spark == 0f, "which do not hang about")
        // And then it is delighted.
        assertTrue(excited.maxOf { it.eyeBend } > 0.9f, "the happiest eyes it has: ${excited.maxOf { it.eyeBend }}")
        // It goes back to dancing afterwards.
        assertTrue(frames.last().mood == OrbMind.Mood.Dance, "then back to the dance")
    }

    @Test
    fun `it does not roll over to music with no life in it`() {
        val frames = run(40.0, playing = true, energy = 0.05f, low = 0.02f).frames
        assertTrue(frames.none { it.mood == OrbMind.Mood.Excited }, "nothing to get carried away about")
        assertTrue(frames.all { it.spark == 0f }, "and no stars")
    }

    @Test
    fun `a skip sweeps its eyes the way the covers travel`() {
        for (forward in listOf(true, false)) {
            val mind = OrbMind()
            run(1.0, playing = false, mind = mind)
            val frames = run(0.9, playing = false, mind = mind, onFrame = { t, m -> if (t == 0.0) m.watch(forward) }).frames
            val first = if (forward) frames.maxByOrNull { it.gazeX } else frames.minByOrNull { it.gazeX }
            val then = if (forward) frames.minByOrNull { it.gazeX } else frames.maxByOrNull { it.gazeX }
            val side = if (forward) 1f else -1f
            assertTrue(first!!.gazeX * side > 0.4f, "it catches the cover on the ${if (forward) "right" else "left"}: ${first.gazeX}")
            assertTrue(then!!.gazeX * side < -0.3f, "and follows it across: ${then.gazeX}")
            assertTrue(frames.indexOf(first) < frames.indexOf(then), "in that order")
            assertTrue(frames.any { abs(it.tilt) > 1f }, "its head turns with them")
            val after = run(1.5, playing = false, mind = mind).frames.last()
            assertTrue(abs(after.gazeX) < 0.9f, "it does not stare after them: ${after.gazeX}")
        }
    }

    @Test
    fun `its eyes rest on the focus lens, wherever it is`() {
        for (focus in 0..6) {
            val look = Offset(lensGaze(focus, 7), LensGaze)
            val end = run(1.2, playing = false, look = look).frames.last()
            assertTrue(abs(end.gazeX - look.x) < 0.05f, "lens at slot $focus (${look.x}): looking at ${end.gazeX}")
            assertTrue(abs(end.gazeY - LensGaze) < 0.05f, "and down at the tray: ${end.gazeY}")
            assertTrue(end.atRest, "and then it holds there")
        }
        assertEquals(0f, lensGaze(3, 7), "the middle action is straight ahead")
        assertTrue(lensGaze(0, 7) < -0.8f && lensGaze(6, 7) > 0.8f, "the ends are a full look either way")
    }

    @Test
    fun `the lens wins over whatever it was feeling`() {
        // Beside itself, it still looks where the listener is working.
        val mind = OrbMind()
        run(60.0, playing = false, mind = mind)
        val look = Offset(lensGaze(6, 7), LensGaze)
        val held = run(1.5, playing = false, mind = mind, look = look).frames.last()
        assertEquals(OrbMind.Mood.Panic, held.mood, "still beside itself")
        assertTrue(abs(held.gazeX - look.x) < 0.2f, "but looking at the lens: ${held.gazeX}")
    }

    @Test
    fun `poked, it screws its eyes up`() {
        val open = run(1.5, playing = false).frames.last()
        val held = run(1.5, playing = false, pressed = true).frames.last()
        assertTrue(held.eyeHeight < open.eyeHeight * 0.6f, "${open.eyeHeight} → ${held.eyeHeight}")
        assertTrue(held.eyeWidth > open.eyeWidth, "and squeezes them wide")
    }

    @Test
    fun `starting and stopping is one movement, and then it is gone`() {
        val mind = OrbMind()
        val face = OrbFace()
        // Over silence, so the impulse is all there is to see (a kick squashes it at the same moment).
        run(2.0, playing = false, mind = mind, face = face)
        val starting = run(0.5, playing = true, mind = mind, face = face).frames
        assertTrue(starting.first().scaleY > 1.05f, "it stretches up as the music starts: ${starting.first().scaleY}")
        val settling = run(0.5, playing = false, mind = mind, face = face).frames
        assertTrue(settling.first().scaleY < 0.95f, "and settles down as it stops: ${settling.first().scaleY}")
        val later = run(2.0, playing = false, mind = mind, face = face).frames.last()
        assertTrue(abs(later.scaleY - 1f) < 0.001f, "the impulse is gone: ${later.scaleY}")
    }

    @Test
    fun `stopping starts the story again`() {
        val mind = OrbMind()
        val face = OrbFace()
        run(60.0, playing = false, mind = mind, face = face)
        assertEquals(OrbMind.Mood.Panic, run(0.1, playing = false, mind = mind, face = face).frames.last().mood)
        run(1.0, playing = true, mind = mind, face = face)
        val again = run(1.0, playing = false, mind = mind, face = face).frames.last()
        assertEquals(OrbMind.Mood.Calm, again.mood, "it is not still cross about the last time")
    }

    @Test
    fun `reduced motion - nothing moves at all`() {
        val look = Offset(lensGaze(6, 7), LensGaze)
        val frames = run(140.0, playing = true, levels = ::song, reduced = true, look = look).frames
        for (f in frames) {
            assertEquals(1f, f.scaleX)
            assertEquals(1f, f.scaleY)
            assertEquals(0f, f.bob)
            assertEquals(0f, f.shiftX)
            assertEquals(0f, f.tilt)
            assertEquals(0f, f.spark, "no stars")
            assertEquals(1f, f.eyeWidth)
            assertEquals(1f, f.eyeHeight, "the eyes never blink")
            assertEquals(0f, f.eyeBend)
            assertEquals(0f, f.eyeLean)
            assertEquals(0f, f.eyeSkew)
            assertTrue(f.atRest, "and it asks for no frames")
        }
        // The eyes still go where the lens is — at once, with no glide.
        assertTrue(abs(frames.first().gazeX - look.x) < 0.001f, "the gaze snaps: ${frames.first().gazeX}")
        assertEquals(LensGaze, frames.first().gazeY)
        // A skip is not watched either.
        val mind = OrbMind()
        mind.watch(forward = true)
        val still = run(0.6, playing = false, reduced = true, mind = mind).frames
        assertTrue(still.all { abs(it.gazeX) < 0.001f }, "no sweep")
    }

    @Test
    fun `playing, it always has something to draw`() {
        assertTrue(run(4.0, playing = true, levels = ::song).frames.none { it.atRest }, "the dance never rests")
    }
}
