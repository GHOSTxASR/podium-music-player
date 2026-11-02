package app.podium.feature.nowplaying

import androidx.compose.ui.geometry.Offset
import app.podium.core.designsystem.orb.OrbFace
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * What the live orb is feeling (D-107): everything that moves Now Playing's orb, one frame at a time.
 * It takes the music as [OrbDrive] has already smoothed it, what the screen is doing, and the time
 * since the last frame, and writes a whole [OrbFace]. Nearly all of it is said with two eyes.
 *
 * **A mood, and the things it does in it.** A mood is a held expression — the orb can sit in one
 * without moving at all — and every few seconds it does one short thing from the handful that mood
 * allows (a blink, a peer, a stamp, a sigh, a nod). Moods cross into each other over
 * [MOOD_BLEND_MS], so a feeling arrives rather than cuts.
 *
 * **Stopped, it works through the whole story**, by how long the music has been off:
 * 1. **Calm** — it settles, blinks, looks about.
 * 2. **Alert** — "!": it sits up, eyes wide. Something has happened.
 * 3. **Question** — "?": head on one side, one eye narrowed, looking up and away.
 * 4. **Curious** — it leans in, peering about for what went wrong.
 * 5. **Annoyed** — cross: the inner ends of its eyes drop, it squats and stamps.
 * 6. **Panic** — beside itself, and making sure you know: wide eyes, wobbling, hopping, never still.
 * 7. **Sad** — it gives up: eyes bowed down, body sagging, looking at the floor, sighing.
 * 8. **Doze** — it falls asleep: eyes all but shut, breathing, nodding, now and then jerking awake.
 *
 * **Playing, it dances**, and every so often it gets carried away: it crouches, rolls right forward
 * over itself, lands with a squash and a few stars off its head, and stays delighted for a moment
 * (**Excited**), then goes back to dancing. The bass squashes the body and squishes the eyes; a slow
 * beat of its own bobs and leans it, faster the louder the music; a loud passage arches the eyes up
 * happily and a quiet one lets them stand again.
 *
 * **It also watches the screen.** A song change sweeps its eyes the way the covers travel ([watch]);
 * while the Wheel's focus lens is on the action tray the eyes rest on it and glide with it on the
 * lens's own spring; poked, it screws its eyes up; starting and stopping, it stretches up or settles
 * down once.
 *
 * Reduced motion: nothing moves at all. The eyes stay open and snap to whatever they are following;
 * the orb's shape still changes between playing and stopped (that is information, not decoration).
 *
 * Pure — time is passed in and the choices come from a seeded [Random] — so all of it is tested
 * without a screen. [atRest] with [millisUntilMove] let the caller stop asking for frames while the
 * orb is holding still, which is most of the time the music is stopped.
 */
internal class OrbMind(seed: Long = 20_261_031L) {

    /** What the orb is feeling. The last two are the music's; the rest belong to a stopped player. */
    internal enum class Mood { Calm, Alert, Question, Curious, Annoyed, Panic, Sad, Doze, Dance, Excited }

    /** One short thing the orb does while it is in a mood. */
    private enum class Beat { Rest, Blink, DoubleBlink, SlowBlink, Glance, Peer, Pop, Shake, Stamp, Sigh, Nod, Hop }

    private val rng = Random(seed)

    private var beat = Beat.Rest
    private var beatMs = 0f
    private var beatLength = REST_CALM_MAX

    /** Which way it has just decided to look or lean. */
    private var glance = 1f

    /**
     * The side a mood is actually leaning to, easing after [glance]. A held posture cannot change
     * sides in a frame: the questioning head has to swing over, not jump.
     */
    private var leaning = 1f

    /** How long the music has been stopped, and how long it has been playing. */
    private var stoppedMs = 0f
    private var playedMs = 0f

    /** Its own slow beat while the music plays, which runs faster the louder the music. */
    private var danceMs = 0f

    /**
     * How happy the music is making it, 0–1, with its own slow follow. A mood is not a measurement:
     * taken straight from the energy it would flick on and off in three frames at the edge of a loud
     * passage, so it builds with the passage and lets go a moment after it.
     */
    private var joy = 0f

    /** The skip watch, or negative while it is not watching anything. */
    private var watchMs = -1f
    private var watchDir = 1f

    /** The one impulse of starting or stopping, decaying; its sign says which. */
    private var wake = 0f
    private var wakeSign = 0f

    /** The burst of stars, or negative when there is none. */
    private var sparkMs = -1f

    /** How far into getting carried away it is, or negative; and when the next one is due. */
    private var exciteMs = -1f
    private var excitedFrom = EXCITE_FIRST_MS
    private var rolled = false

    private var playingNow = false
    private var started = false

    private var mood = Mood.Calm
    private var leaving = Mood.Calm
    private var blend = 1f

    // The gaze's own spring, the focus lens's: the eyes glide where the lens glides.
    private var gazeX = 0f
    private var gazeVx = 0f
    private var gazeY = 0f
    private var gazeVy = 0f

    private var moving = true
    private var restless = 0f

    private val here = Posture()
    private val there = Posture()

    /** What the orb is feeling now. */
    val feeling: Mood get() = mood

    /** Nothing is moving: the caller may stop asking for frames for [millisUntilMove]. */
    val atRest: Boolean get() = !moving

    /**
     * How long the caller may wait between frames while the orb is moving: 0 while it needs every
     * frame, or a pace for a movement too slow to need them all — a sleeping orb breathes once every
     * few seconds, and a fifth of the frames draw that just as well.
     */
    fun pacingMs(): Long = if (!playingNow && mood == Mood.Doze && beat == Beat.Rest && blend >= 1f) DOZE_PACE_MS else 0L

    /** How long until the orb does something again. Only meaningful while [atRest]. */
    fun millisUntilMove(): Long {
        val nextBeat = (beatLength - beatMs).coerceAtLeast(0f)
        return minOf(nextBeat, untilNextMood(stoppedMs)).coerceAtLeast(0f).toLong()
    }

    /**
     * The song changed: the eyes go with the covers. [forward] for the next song (the covers travel
     * right to left, so the eyes do), false for the one before.
     */
    fun watch(forward: Boolean) {
        watchMs = 0f
        watchDir = if (forward) 1f else -1f
    }

    /** Back to a face at rest (a test, or the orb leaving the screen). */
    fun reset() {
        beat = Beat.Rest
        beatMs = 0f
        beatLength = REST_CALM_MAX
        leaning = 1f
        stoppedMs = 0f
        playedMs = 0f
        danceMs = 0f
        joy = 0f
        watchMs = -1f
        wake = 0f
        sparkMs = -1f
        exciteMs = -1f
        excitedFrom = EXCITE_FIRST_MS
        mood = Mood.Calm
        leaving = Mood.Calm
        blend = 1f
        gazeX = 0f
        gazeVx = 0f
        gazeY = 0f
        gazeVy = 0f
        moving = true
    }

    /**
     * Moves the orb on by [dtMs] and writes the whole of [out]: every field is set every frame, so
     * nothing of the last frame is left behind.
     *
     * [energy], [low], [high] and [glint] are [OrbDrive]'s, 0–1. [look] is what the eyes should rest
     * on, in the face's own −1 to 1 reach (the focus lens), or null to let the orb look where it
     * likes. [pressed]: a finger is on it. [reduced]: the phone asks for no animation.
     */
    fun update(
        dtMs: Float,
        playing: Boolean,
        energy: Float,
        low: Float,
        high: Float,
        glint: Float,
        look: Offset?,
        pressed: Boolean,
        reduced: Boolean,
        out: OrbFace,
    ) {
        val full = dtMs.coerceAtLeast(0f)
        val step = full.coerceAtMost(MAX_STEP_MS)
        if (playing != playingNow || !started) {
            if (started) {
                wake = 1f
                wakeSign = if (playing) 1f else -1f
            }
            playingNow = playing
            started = true
            stoppedMs = 0f
            playedMs = 0f
            exciteMs = -1f
            sparkMs = -1f
            excitedFrom = EXCITE_FIRST_MS
            mood = if (playing) Mood.Dance else Mood.Calm
            leaving = mood
            blend = 1f
            // Stopping, it blinks once as it settles; starting, it just dances.
            begin(if (playing) Beat.Rest else Beat.Blink, if (playing) restFor(Mood.Dance) else BLINK_MS)
        }
        if (playing) playedMs += full else stoppedMs += full
        wake *= exp(-step / WAKE_MS)
        if (watchMs >= 0f) {
            watchMs += full
            if (watchMs > WATCH_MS) watchMs = -1f
        }
        if (sparkMs >= 0f) {
            sparkMs += full
            if (sparkMs > SPARK_MS) sparkMs = -1f
        }

        out.scaleX = 1f
        out.scaleY = 1f
        out.bob = 0f
        out.shiftX = 0f
        out.tilt = 0f
        out.spark = 0f
        out.eyeWidth = 1f
        out.eyeHeight = 1f
        out.eyeBend = 0f
        out.eyeLean = 0f
        out.eyeSkew = 0f

        if (reduced) {
            // Still. The eyes look where they are told, at once; everything else stays at rest.
            gazeX = look?.x ?: 0f
            gazeY = look?.y ?: 0f
            gazeVx = 0f
            gazeVy = 0f
            out.gazeX = gazeX.coerceIn(-1f, 1f)
            out.gazeY = gazeY.coerceIn(-1f, 1f)
            if (pressed) squeeze(out)
            moving = false
            restless = 0f
            beatMs = 0f
            beatLength = REDUCED_REST_MS
            out.moved()
            return
        }

        // Its mood follows the music, slowly, whether or not anything is playing.
        val wanted = if (playing) smooth(JOY_FROM, JOY_TO, energy) else 0f
        joy += (wanted - joy) * (1f - exp(-step / if (wanted > joy) JOY_ATTACK_MS else JOY_RELEASE_MS))

        // Getting carried away, now and then, once the music has had a while to work on it.
        if (playing) {
            if (exciteMs >= 0f) {
                exciteMs += full
                if (exciteMs > EXCITE_MS) {
                    exciteMs = -1f
                    excitedFrom = playedMs + range(EXCITE_AGAIN_MIN, EXCITE_AGAIN_MAX)
                }
            } else if (playedMs >= excitedFrom && joy > EXCITE_JOY) {
                exciteMs = 0f
                rolled = false
            }
        }

        // Which mood it is in, and the one it is still coming out of.
        val next = if (playing) {
            if (exciteMs >= 0f) Mood.Excited else Mood.Dance
        } else {
            moodFor(stoppedMs)
        }
        if (next != mood) {
            leaving = mood
            mood = next
            blend = 0f
            glance = if (rng.nextBoolean()) 1f else -1f
            begin(entryBeat(next), lengthOf(entryBeat(next), next))
        }
        if (blend < 1f) blend = (blend + full / MOOD_BLEND_MS).coerceAtMost(1f)

        // The dance, and the wander that goes with it.
        var wanderX = 0f
        var wanderY = 0f
        if (playing) {
            danceMs += step * (1f + DANCE_ENERGY * energy)
            val pulse = TWO_PI * danceMs / DANCE_PERIOD_MS
            val sway = sin(pulse)
            // The eye is a dot exactly where it is as wide as it is tall, so the mood crosses that
            // point briskly: calm eyes standing up, or a happy arch, and little time looking blank.
            val shape = smooth(JOY_SHAPE_FROM, JOY_SHAPE_TO, joy)
            out.scaleY = 1f - 0.15f * low - 0.022f * sway
            out.scaleX = 1f + 0.12f * low + 0.016f * sway
            out.bob = 0.55f * low + 0.26f * (0.5f + 0.5f * sway) * (0.3f + 0.7f * energy)
            out.tilt = sin(pulse * 0.5f) * (1.1f + 2.6f * shape)
            out.eyeHeight = (1f - 0.34f * low) * (1f - JOY_SQUINT * shape) * (1f + 0.05f * high)
            out.eyeWidth = (1f + 0.17f * low) * (1f + JOY_WIDEN * shape) * (1f + 0.06f * glint)
            out.eyeBend = JOY_BEND * shape
            wanderX = sin(danceMs / WANDER_X_MS) * WANDER
            wanderY = sin(danceMs / WANDER_Y_MS + 1.7f) * WANDER * 0.6f
            if (exciteMs >= 0f) carriedAway(out)
        }

        // The mood it is holding, crossing out of the last one. The side it leans to eases after the
        // side it has chosen, so changing its mind swings the head over instead of flipping it.
        leaning += (glance - leaning) * (1f - exp(-step / LEAN_MS))
        // Once it is as good as there, it is there: a face at rest must not creep by a thousandth.
        if (abs(glance - leaning) < LEAN_REST) leaning = glance
        postureOf(leaving, leaning, stoppedMs, here)
        postureOf(mood, leaning, stoppedMs, there)
        here.towards(there, blend)
        here.applyTo(out)
        restless = here.restless

        // One thing at a time: the beat it is in now.
        beatMs += full
        if (beatMs >= beatLength) nextBeat()
        var beatGazeX = 0f
        var beatGazeY = 0f
        val t = if (beatLength <= 0f) 1f else (beatMs / beatLength).coerceIn(0f, 1f)
        when (beat) {
            Beat.Rest -> Unit
            Beat.Blink -> out.eyeHeight *= 1f - 0.94f * bell(t)
            Beat.DoubleBlink -> out.eyeHeight *= 1f - 0.94f * twice(t)
            Beat.SlowBlink -> out.eyeHeight *= 1f - 0.92f * plateau(t, 0.45f)
            Beat.Glance -> {
                val held = plateau(t, 0.4f)
                beatGazeX = glance * GLANCE_REACH * held
                out.tilt += glance * 1.6f * held
            }
            Beat.Peer -> {
                val held = plateau(t, 0.5f)
                beatGazeX = glance * 0.75f * held
                out.scaleX *= 1f + 0.03f * held
                out.scaleY *= 1f + 0.03f * held
                out.tilt += glance * 4f * held
                out.eyeHeight *= 1f + 0.10f * held
            }
            Beat.Pop -> {
                val b = bell(t)
                out.bob += 0.75f * b
                out.scaleY *= 1f + 0.10f * b
                out.scaleX *= 1f - 0.07f * b
                out.eyeHeight *= 1f + 0.30f * b
            }
            Beat.Shake -> {
                val b = bell(t)
                val wiggle = sin(TWO_PI * SHAKE_TURNS * t)
                out.tilt += wiggle * SHAKE_TILT * b
                out.bob += 0.18f * b
                beatGazeX = wiggle * 0.3f * b
            }
            Beat.Stamp -> {
                val b = bell(t)
                out.scaleY *= 1f - 0.12f * b
                out.scaleX *= 1f + 0.09f * b
                out.bob -= 0.5f * b
                out.eyeHeight *= 1f - 0.25f * b
            }
            Beat.Sigh -> {
                // It swells, lets it all go, sinks — and comes back up by the end, or the beat would
                // leave it somewhere and drop it.
                val b = bell(t)
                val sag = if (t > SIGH_SAG) bell((t - SIGH_SAG) / (1f - SIGH_SAG)) else 0f
                out.scaleX *= 1f + 0.055f * b
                out.scaleY *= 1f + 0.045f * b
                out.bob += 0.22f * b - 0.30f * sag
                out.eyeHeight *= 1f - 0.55f * b
                beatGazeY = 0.25f * sag
            }
            Beat.Nod -> {
                // Its head goes down, and down, and then it catches itself and its eyes come open.
                if (t < NOD_FALL) {
                    val k = ease(t / NOD_FALL)
                    out.tilt += 7f * k
                    out.bob -= 0.55f * k
                } else {
                    val u = (t - NOD_FALL) / (1f - NOD_FALL)
                    out.tilt += 7f * (1f - ease(u))
                    out.bob -= 0.55f * (1f - ease(u))
                    // Its eyes come open as it catches itself, and close again as it settles back.
                    out.eyeHeight *= 1f + 5.5f * bell(u)
                }
            }
            Beat.Hop -> {
                val fade = 1f - ease(t)
                out.bob += abs(sin(TWO_PI * t)) * 0.7f * fade
                out.scaleY *= 1f - 0.05f * fade * (1f - abs(sin(TWO_PI * t)))
            }
        }

        // Starting or stopping: one stretch up, or one settle down.
        if (wake > WAKE_REST) {
            val k = wake * wake
            out.scaleY += wakeSign * 0.14f * k
            out.scaleX -= wakeSign * 0.09f * k
            out.bob += wakeSign * 0.5f * k
            out.eyeHeight *= 1f + wakeSign * 0.22f * k
        }

        if (sparkMs >= 0f) out.spark = (sparkMs / SPARK_MS).coerceIn(0f, 1f)
        if (pressed) squeeze(out)

        // Where the eyes are going: the covers if they are travelling, else the lens, else the mood.
        var targetX: Float
        var targetY: Float
        if (watchMs >= 0f) {
            val w = (watchMs / WATCH_MS).coerceIn(0f, 1f)
            // Right across to left for the next song (the way the covers go), and the head turns with them.
            targetX = watchDir * cos(PI_F * ease(w)) * WATCH_REACH
            targetY = WATCH_LIFT
            out.tilt += watchDir * WATCH_TILT * bell(w)
            out.eyeHeight *= 1f + 0.12f * bell(w)
            // Watching the covers, it stops squinting: it is paying attention, not enjoying itself.
            out.eyeBend *= 1f - bell(w)
        } else if (look != null) {
            targetX = look.x
            targetY = look.y
        } else {
            targetX = wanderX + here.gazeX
            targetY = wanderY + here.gazeY
        }
        targetX = (targetX + beatGazeX).coerceIn(-1f, 1f)
        targetY = (targetY + beatGazeY).coerceIn(-1f, 1f)

        // The lens's own spring, in small steps, so it looks the same at any frame rate.
        var left = step
        val w = TWO_PI * GAZE_HZ
        while (left > 0f) {
            val h = minOf(left, SUBSTEP_MS) / 1000f
            gazeVx += (w * w * (targetX - gazeX) - 2f * GAZE_DAMP * w * gazeVx) * h
            gazeX += gazeVx * h
            gazeVy += (w * w * (targetY - gazeY) - 2f * GAZE_DAMP * w * gazeVy) * h
            gazeY += gazeVy * h
            left -= SUBSTEP_MS
        }
        // Arrived, to within a thousandth of the orb and barely moving: sit down on it exactly, so a
        // held look is held and not forever approached.
        val settledGaze = abs(targetX - gazeX) < GAZE_REST && abs(targetY - gazeY) < GAZE_REST &&
            abs(gazeVx) < GAZE_VELOCITY_REST && abs(gazeVy) < GAZE_VELOCITY_REST
        if (settledGaze) {
            gazeX = targetX
            gazeY = targetY
            gazeVx = 0f
            gazeVy = 0f
        }
        out.gazeX = gazeX.coerceIn(-1f, 1f)
        out.gazeY = gazeY.coerceIn(-1f, 1f)

        // Frames are only worth asking for while something is actually moving.
        moving = playing || watchMs >= 0f || sparkMs >= 0f || wake > WAKE_REST || beat != Beat.Rest ||
            !settledGaze || pressed || restless > 0.01f || blend < 1f || abs(glance - leaning) > LEAN_REST
        out.moved()
    }

    /**
     * Carried away: it crouches, rolls right over forward, lands with a squash and a few stars off its
     * head, and stays delighted for the rest of it. The eyes go round with the body — that is what
     * says it rolled rather than spun on the spot.
     */
    private fun carriedAway(out: OrbFace) {
        val e = exciteMs
        val landed = TUMBLE_AT + TUMBLE_MS
        when {
            e < TUMBLE_AT -> {
                // Winding up.
                val k = ease(e / TUMBLE_AT)
                out.scaleY *= 1f - 0.10f * k
                out.scaleX *= 1f + 0.07f * k
                out.bob -= 0.35f * k
            }
            e < landed -> {
                val u = ((e - TUMBLE_AT) / TUMBLE_MS).coerceIn(0f, 1f)
                out.tilt += 360f * ease(u)
                out.bob += 1.15f * bell(u)
                out.shiftX += bell(u)
                out.eyeHeight *= 1f - 0.45f * bell(u)
                out.eyeWidth *= 1f + 0.25f * bell(u)
            }
            else -> {
                if (!rolled) {
                    rolled = true
                    sparkMs = 0f
                }
                val since = e - landed
                if (since < LAND_MS) {
                    // It comes down hard and springs back.
                    val k = 1f - ease(since / LAND_MS)
                    out.scaleY *= 1f - 0.16f * k
                    out.scaleX *= 1f + 0.12f * k
                    out.bob -= 0.3f * k
                }
                // Delighted: the widest, happiest eyes it has.
                val glee = smooth(0f, GLEE_IN_MS, since)
                out.eyeWidth *= 1f + 0.55f * glee
                out.eyeHeight *= 1f - 0.45f * glee
                out.eyeBend = max(out.eyeBend, 0.95f * glee)
                out.bob += 0.45f * glee * abs(sin(TWO_PI * since / GLEE_BOUNCE_MS))
            }
        }
    }

    /** Eyes screwed up under a finger. */
    private fun squeeze(out: OrbFace) {
        out.eyeHeight *= 0.46f
        out.eyeWidth *= 1.1f
    }

    private fun begin(next: Beat, length: Float) {
        beat = next
        beatMs = 0f
        beatLength = length
    }

    /** The next thing it does: a rest, then one of the things this mood allows. */
    private fun nextBeat() {
        if (beat != Beat.Rest) {
            begin(Beat.Rest, restFor(mood))
            return
        }
        glance = if (rng.nextBoolean()) 1f else -1f
        val table = beatsFor(mood)
        var roll = rng.nextFloat() * table.sumOf { it.second.toDouble() }.toFloat()
        var chosen = table.first().first
        for ((candidate, weight) in table) {
            chosen = candidate
            roll -= weight
            if (roll <= 0f) break
        }
        begin(chosen, lengthOf(chosen, mood))
    }

    private fun range(from: Float, to: Float): Float = from + rng.nextFloat() * (to - from)

    /** A mood's held expression, so two of them can be crossed while one gives way to the other. */
    private class Posture {
        var eyeH = 1f
        var eyeW = 1f
        var eyeLean = 0f
        var eyeBend = 0f
        var eyeSkew = 0f
        var scaleX = 1f
        var scaleY = 1f
        var bob = 0f
        var tilt = 0f
        var gazeX = 0f
        var gazeY = 0f

        /** How much this mood never holds still: the caller must keep asking for frames. */
        var restless = 0f

        fun reset() {
            eyeH = 1f
            eyeW = 1f
            eyeLean = 0f
            eyeBend = 0f
            eyeSkew = 0f
            scaleX = 1f
            scaleY = 1f
            bob = 0f
            tilt = 0f
            gazeX = 0f
            gazeY = 0f
            restless = 0f
        }

        fun towards(other: Posture, k: Float) {
            if (k >= 1f) {
                // Arrived: take the mood's own posture exactly. Interpolating all the way there lands
                // a float's width off it, which is not movement but would keep a held face redrawing.
                eyeH = other.eyeH
                eyeW = other.eyeW
                eyeLean = other.eyeLean
                eyeBend = other.eyeBend
                eyeSkew = other.eyeSkew
                scaleX = other.scaleX
                scaleY = other.scaleY
                bob = other.bob
                tilt = other.tilt
                gazeX = other.gazeX
                gazeY = other.gazeY
                restless = other.restless
                return
            }
            eyeH += (other.eyeH - eyeH) * k
            eyeW += (other.eyeW - eyeW) * k
            eyeLean += (other.eyeLean - eyeLean) * k
            eyeBend += (other.eyeBend - eyeBend) * k
            eyeSkew += (other.eyeSkew - eyeSkew) * k
            scaleX += (other.scaleX - scaleX) * k
            scaleY += (other.scaleY - scaleY) * k
            bob += (other.bob - bob) * k
            tilt += (other.tilt - tilt) * k
            gazeX += (other.gazeX - gazeX) * k
            gazeY += (other.gazeY - gazeY) * k
            restless = max(restless * (1f - k), other.restless * k)
        }

        fun applyTo(out: OrbFace) {
            out.eyeHeight *= eyeH
            out.eyeWidth *= eyeW
            out.eyeLean += eyeLean
            out.eyeBend += eyeBend
            out.eyeSkew += eyeSkew
            out.scaleX *= scaleX
            out.scaleY *= scaleY
            out.bob += bob
            out.tilt += tilt
        }
    }

    private fun postureOf(m: Mood, side: Float, clock: Float, into: Posture) {
        into.reset()
        when (m) {
            Mood.Calm, Mood.Dance, Mood.Excited -> Unit
            Mood.Alert -> {
                // "!" — it sits up, eyes wide open.
                into.eyeH = 1.26f
                into.eyeW = 1.06f
                into.bob = 0.24f
                into.scaleY = 1.02f
                into.scaleX = 0.985f
            }
            Mood.Question -> {
                // "?" — head on one side, one eye narrowed, looking up and away.
                into.eyeSkew = 0.7f * side
                into.tilt = 10f * side
                into.eyeH = 1.06f
                into.gazeX = 0.35f * side
                into.gazeY = -0.45f
            }
            Mood.Curious -> {
                // It leans in to look: a little bigger, eyes round and open.
                into.scaleX = 1.04f
                into.scaleY = 1.04f
                into.eyeH = 1.12f
                into.eyeW = 1.04f
                into.bob = -0.1f
            }
            Mood.Annoyed -> {
                // Cross: wide eyes with their inner ends dropped, and a squat body.
                into.eyeLean = 18f
                into.eyeH = 0.28f
                into.eyeW = 1.85f
                into.scaleY = 0.965f
                into.scaleX = 1.04f
                into.bob = -0.12f
            }
            Mood.Panic -> {
                // Beside itself, and making sure you know. It never stops moving.
                into.eyeH = 1.34f
                into.eyeW = 1.12f
                into.tilt = sin(clock / PANIC_WOBBLE_MS) * PANIC_TILT
                into.bob = 0.18f + 0.22f * sin(clock / PANIC_HOP_MS)
                into.scaleX = 1f + 0.025f * sin(clock / PANIC_HOP_MS + 1.1f)
                into.scaleY = 1f - 0.025f * sin(clock / PANIC_HOP_MS + 1.1f)
                into.gazeX = 0.5f * sin(clock / PANIC_DART_MS)
                into.restless = 1f
            }
            Mood.Sad -> {
                // Hurt: wide eyes bowed down, their inner ends up, sagging, looking at the floor.
                into.eyeLean = -12f
                into.eyeH = 0.24f
                into.eyeW = 1.70f
                into.eyeBend = -0.7f
                into.scaleY = 0.95f
                into.scaleX = 1.05f
                into.bob = -0.3f
                into.gazeY = 0.55f
            }
            Mood.Doze -> {
                // Asleep, breathing: its eyes are all but shut.
                val breath = sin(clock / DOZE_BREATH_MS)
                into.eyeH = 0.12f
                into.eyeW = 1.15f
                into.scaleY = 1f + 0.016f * breath
                into.scaleX = 1f - 0.012f * breath
                into.bob = -0.14f + 0.05f * breath
                into.restless = 1f
            }
        }
    }

    /** What a mood does the moment it arrives. */
    private fun entryBeat(m: Mood): Beat = when (m) {
        Mood.Alert -> Beat.Pop
        Mood.Question -> Beat.Blink
        Mood.Curious -> Beat.Peer
        Mood.Annoyed -> Beat.Stamp
        Mood.Panic -> Beat.Shake
        Mood.Sad -> Beat.Sigh
        Mood.Doze -> Beat.SlowBlink
        Mood.Calm, Mood.Dance, Mood.Excited -> Beat.Rest
    }

    /** The things a mood allows, and how likely each is. */
    private fun beatsFor(m: Mood): Array<Pair<Beat, Float>> = when (m) {
        Mood.Calm -> arrayOf(Beat.Blink to 0.40f, Beat.DoubleBlink to 0.12f, Beat.Glance to 0.32f, Beat.SlowBlink to 0.06f, Beat.Rest to 0.10f)
        Mood.Alert -> arrayOf(Beat.Pop to 0.32f, Beat.Glance to 0.30f, Beat.Blink to 0.26f, Beat.Shake to 0.12f)
        Mood.Question -> arrayOf(Beat.Blink to 0.26f, Beat.Peer to 0.30f, Beat.Glance to 0.30f, Beat.SlowBlink to 0.14f)
        Mood.Curious -> arrayOf(Beat.Peer to 0.44f, Beat.Blink to 0.22f, Beat.Glance to 0.22f, Beat.Pop to 0.12f)
        Mood.Annoyed -> arrayOf(Beat.Stamp to 0.40f, Beat.Shake to 0.22f, Beat.Glance to 0.20f, Beat.Blink to 0.18f)
        Mood.Panic -> arrayOf(Beat.Shake to 0.46f, Beat.Hop to 0.32f, Beat.Pop to 0.22f)
        Mood.Sad -> arrayOf(Beat.Sigh to 0.40f, Beat.SlowBlink to 0.36f, Beat.Glance to 0.24f)
        Mood.Doze -> arrayOf(Beat.Nod to 0.55f, Beat.SlowBlink to 0.45f)
        Mood.Dance, Mood.Excited -> arrayOf(Beat.Blink to 1f)
    }

    private fun lengthOf(b: Beat, m: Mood): Float = when (b) {
        Beat.Rest -> restFor(m)
        Beat.Blink -> BLINK_MS
        Beat.DoubleBlink -> DOUBLE_BLINK_MS
        Beat.SlowBlink -> if (m == Mood.Doze) DOZE_BLINK_MS else SLOW_BLINK_MS
        Beat.Glance -> GLANCE_MS
        Beat.Peer -> PEER_MS
        Beat.Pop -> POP_MS
        Beat.Shake -> SHAKE_MS
        Beat.Stamp -> STAMP_MS
        Beat.Sigh -> SIGH_MS
        Beat.Nod -> NOD_MS
        Beat.Hop -> HOP_MS
    }

    /** How long a mood holds still between two things. */
    private fun restFor(m: Mood): Float = when (m) {
        Mood.Calm -> range(REST_CALM_MIN, REST_CALM_MAX)
        Mood.Alert -> range(700f, 1_600f)
        Mood.Question -> range(1_100f, 2_400f)
        Mood.Curious -> range(800f, 1_900f)
        Mood.Annoyed -> range(700f, 1_800f)
        Mood.Panic -> range(120f, 420f)
        Mood.Sad -> range(1_600f, 3_600f)
        Mood.Doze -> range(2_400f, 5_200f)
        Mood.Dance, Mood.Excited -> range(PLAY_REST_MIN_MS, PLAY_REST_MAX_MS)
    }

    private companion object {
        const val MAX_STEP_MS = 100f
        const val SUBSTEP_MS = 4f
        val PI_F = Math.PI.toFloat()
        val TWO_PI = 2f * PI_F

        /** A feeling arrives over this long rather than cutting in. */
        const val MOOD_BLEND_MS = 600f

        /** And it changes which side it is leaning to over this long. */
        const val LEAN_MS = 260f
        const val LEAN_REST = 0.01f

        /**
         * The story a stopped orb works through, by how long the music has been off. Past the last
         * one it is asleep.
         */
        val LADDER = floatArrayOf(6_000f, 13_000f, 23_000f, 38_000f, 52_000f, 72_000f, 100_000f)
        val RUNGS = arrayOf(Mood.Calm, Mood.Alert, Mood.Question, Mood.Curious, Mood.Annoyed, Mood.Panic, Mood.Sad, Mood.Doze)

        fun moodFor(stopped: Float): Mood {
            for (i in LADDER.indices) if (stopped < LADDER[i]) return RUNGS[i]
            return Mood.Doze
        }

        fun untilNextMood(stopped: Float): Float {
            for (edge in LADDER) if (stopped < edge) return edge - stopped
            return Float.MAX_VALUE
        }

        /** The eyes glide on the focus lens's own spring (1400 / 0.86, animation-system.md), as Hz. */
        const val GAZE_HZ = 5.95f
        const val GAZE_DAMP = 0.86f
        const val GAZE_REST = 0.004f
        const val GAZE_VELOCITY_REST = 0.02f

        /** The orb's own beat while the music plays, and how much the music hurries it. */
        const val DANCE_PERIOD_MS = 2_600f
        const val DANCE_ENERGY = 0.85f

        /**
         * Between these energies the eyes squint up into a happy arch. At its fullest the eye is well
         * wider than it is tall, which is what lets the bow read as an arch rather than a dot.
         */
        const val JOY_FROM = 0.34f
        const val JOY_TO = 0.82f
        const val JOY_SQUINT = 0.72f
        const val JOY_WIDEN = 1.0f
        const val JOY_BEND = 1f

        /** The band of mood the eye's shape crosses in: calm below it, happy above it. */
        const val JOY_SHAPE_FROM = 0.42f
        const val JOY_SHAPE_TO = 0.78f

        /** A mood builds over about half a second and lets go over twice that. */
        const val JOY_ATTACK_MS = 500f
        const val JOY_RELEASE_MS = 1_100f

        /** How far it looks about while it dances, and how slowly (the two never line up). */
        const val WANDER = 0.3f
        const val WANDER_X_MS = 1_900f
        const val WANDER_Y_MS = 2_700f

        /** Starting or stopping: one impulse, gone in about a third of a second. */
        const val WAKE_MS = 170f
        const val WAKE_REST = 0.01f

        /** The covers' travel is 360 ms (D-78); the eyes take a little longer to arrive with them. */
        const val WATCH_MS = 520f
        const val WATCH_REACH = 0.95f
        const val WATCH_TILT = 3.5f

        /** The covers pass a little above the orb, so it watches them slightly up. */
        const val WATCH_LIFT = -0.12f

        /** Getting carried away: the first time, how long it lasts, and how long until the next. */
        const val EXCITE_FIRST_MS = 22_000f
        const val EXCITE_MS = 2_800f
        const val EXCITE_AGAIN_MIN = 42_000f
        const val EXCITE_AGAIN_MAX = 78_000f

        /** It only rolls over for music with some life in it. */
        const val EXCITE_JOY = 0.25f

        /** The roll: a crouch, a whole turn forward, and the landing. */
        const val TUMBLE_AT = 220f
        const val TUMBLE_MS = 820f
        const val LAND_MS = 260f

        /** How long the delight takes to arrive once it has landed, and how fast it bounces in it. */
        const val GLEE_IN_MS = 340f
        const val GLEE_BOUNCE_MS = 760f

        /** The stars are in the air this long. */
        const val SPARK_MS = 760f

        const val BLINK_MS = 150f
        const val DOUBLE_BLINK_MS = 430f
        const val SLOW_BLINK_MS = 900f
        const val DOZE_BLINK_MS = 1_600f
        const val GLANCE_MS = 1_300f
        const val GLANCE_REACH = 0.8f
        const val PEER_MS = 1_500f
        const val POP_MS = 420f
        const val SHAKE_MS = 700f
        const val SHAKE_TURNS = 3.5f
        const val SHAKE_TILT = 7f
        const val STAMP_MS = 380f
        const val SIGH_MS = 1_600f
        const val NOD_MS = 1_500f
        const val NOD_FALL = 0.72f
        const val HOP_MS = 520f

        /** Panic's own clocks: a fast wobble, a bouncing, and eyes darting about. */
        const val PANIC_WOBBLE_MS = 42f
        const val PANIC_TILT = 8f
        const val PANIC_HOP_MS = 95f
        const val PANIC_DART_MS = 210f

        /** Asleep, it breathes once every few seconds, and a fifth of the frames show it. */
        const val DOZE_BREATH_MS = 620f
        const val DOZE_PACE_MS = 50L

        /** How far into a sigh it starts to sink. */
        const val SIGH_SAG = 0.45f

        /** How long it is still between two things, calm and while the music plays. */
        const val REST_CALM_MIN = 1_700f
        const val REST_CALM_MAX = 4_300f
        const val PLAY_REST_MIN_MS = 4_500f
        const val PLAY_REST_MAX_MS = 9_000f

        /** With reduced motion it never does anything; the caller still looks in now and then. */
        const val REDUCED_REST_MS = 60_000f

        /** 0 before [from], 1 after [to], smooth between. */
        fun smooth(from: Float, to: Float, value: Float): Float {
            val x = ((value - from) / (to - from)).coerceIn(0f, 1f)
            return x * x * (3f - 2f * x)
        }

        fun ease(t: Float): Float {
            val x = t.coerceIn(0f, 1f)
            return x * x * (3f - 2f * x)
        }

        /** Out and back once over the whole of [t]. */
        fun bell(t: Float): Float = sin(PI_F * t.coerceIn(0f, 1f))

        /** Out and back twice, with a moment between. */
        fun twice(t: Float): Float {
            val x = t.coerceIn(0f, 1f)
            return when {
                x < 0.42f -> bell(x / 0.42f)
                x > 0.58f -> bell((x - 0.58f) / 0.42f)
                else -> 0f
            }
        }

        /** Out, held for [hold] of the time, and back. */
        fun plateau(t: Float, hold: Float): Float {
            val x = t.coerceIn(0f, 1f)
            val ramp = ((1f - hold) / 2f).coerceAtLeast(0.001f)
            return when {
                x < ramp -> ease(x / ramp)
                x > 1f - ramp -> ease((1f - x) / ramp)
                else -> 1f
            }
        }
    }
}
