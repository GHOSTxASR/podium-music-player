package app.podium.feature.nowplaying

import app.podium.core.designsystem.orb.OrbFrame
import app.podium.player.api.AudioPulse
import kotlin.math.exp

/**
 * How the music moves the orb (D-83): the three measured levels ([AudioPulse.levelsAt]) turned into
 * a few calm values for [OrbFrame]. Each level is judged against the song's own recent loudest
 * (rising quickly, falling over seconds), so quiet and loud masters move alike, and smoothed with an
 * attack and a release, so no measurement ever makes a visual jump:
 *
 * - **energy** (the whole signal) brightens the ring and speeds its drift and turn a little;
 * - **low** (the bass) swells it, through the same spring as the lyrics' swell ([PulseFollower]);
 * - **high** (the top) ripples its surface;
 * - **glint** (the whole signal rising above its own last moments: a hit) lights the travelling
 *   highlight toward gold.
 *
 * The orb's own time and turn advance only while the music plays; paused, or with nothing measured,
 * every value settles to rest and nothing stale keeps it moving.
 *
 * Pure (time is passed in), so it is tested without a screen.
 */
internal class OrbDrive {
    private val swell = PulseFollower()
    private var energy = 0f
    private var high = 0f
    private var glint = 0f
    private var refWhole = 0f
    private var refHigh = 0f
    private var refHit = 0f
    private var average = 0f
    private var time = 0f
    private var rotation = 0f

    /** Whether everything has settled (nothing left to draw differently next frame while paused). */
    val atRest: Boolean get() = swell.atRest && energy < REST && high < REST && glint < REST

    /** Forgets the song (a new one): references and motion start afresh; the orb's time carries on. */
    fun reset() {
        swell.reset()
        energy = 0f
        high = 0f
        glint = 0f
        refWhole = 0f
        refHigh = 0f
        refHit = 0f
        average = 0f
    }

    /**
     * Moves on by [dtMs]. [levels] is the reading at the playback position, or null when nothing was
     * measured; [advancing]: the music is playing. Writes the result into [out].
     */
    fun update(levels: FloatArray?, dtMs: Float, advancing: Boolean, out: OrbFrame) {
        val dt = dtMs.coerceIn(0f, MAX_STEP_MS)
        val reading = if (advancing) levels else null
        var energyTarget = 0f
        var highTarget = 0f
        var glintTarget = 0f
        if (reading != null) {
            val w = reading[AudioPulse.WHOLE]
            val h = reading[AudioPulse.HIGH]
            refWhole = follow(refWhole, w, dt)
            refHigh = follow(refHigh, h, dt)
            average += (w - average) * (1f - exp(-dt / AVERAGE_MS))
            val hit = (w - average).coerceAtLeast(0f)
            refHit = follow(refHit, hit, dt)
            energyTarget = (w / maxOf(refWhole, WHOLE_FLOOR)).coerceIn(0f, 1f)
            highTarget = (h / maxOf(refHigh, HIGH_FLOOR)).coerceIn(0f, 1f)
            val r = hit / maxOf(refHit, HIT_FLOOR)
            glintTarget = ((r - 0.35f) / 0.65f).coerceIn(0f, 1f)
        }
        energy = smooth(energy, energyTarget, dt, ENERGY_ATTACK_MS, ENERGY_RELEASE_MS)
        high = smooth(high, highTarget, dt, HIGH_ATTACK_MS, HIGH_RELEASE_MS)
        glint = smooth(glint, glintTarget, dt, GLINT_ATTACK_MS, GLINT_RELEASE_MS)
        val low = swell.update(if (reading != null) reading[AudioPulse.LOW] else -1f, dt)
        if (advancing) {
            val s = dt / 1000f
            time += s * (DRIFT + DRIFT_ENERGY * energy)
            rotation += s * (TURN + TURN_ENERGY * energy)
            if (rotation > TWO_PI) rotation -= TWO_PI
            if (time > TIME_WRAP) time -= TIME_WRAP
        }
        out.time = time
        out.rotation = rotation
        out.energy = energy
        out.low = low
        out.high = high
        out.glint = glint
    }

    /** A reference that rises with the loudest quickly and lets go over seconds. */
    private fun follow(reference: Float, level: Float, dt: Float): Float =
        if (level > reference) reference + (level - reference) * (1f - exp(-dt / REF_RISE_MS)) else reference * exp(-dt / REF_FALL_MS)

    private fun smooth(value: Float, target: Float, dt: Float, attack: Float, release: Float): Float {
        val tau = if (target > value) attack else release
        return value + (target - value) * (1f - exp(-dt / tau))
    }

    companion object {
        private const val MAX_STEP_MS = 100f
        private const val REST = 0.002f
        private const val REF_RISE_MS = 150f
        private const val REF_FALL_MS = 4_000f
        private const val AVERAGE_MS = 250f
        private const val WHOLE_FLOOR = 0.03f
        private const val HIGH_FLOOR = 0.004f
        private const val HIT_FLOOR = 0.015f
        private const val ENERGY_ATTACK_MS = 90f
        private const val ENERGY_RELEASE_MS = 450f
        private const val HIGH_ATTACK_MS = 70f
        private const val HIGH_RELEASE_MS = 350f
        private const val GLINT_ATTACK_MS = 45f
        private const val GLINT_RELEASE_MS = 380f

        /** The orb's slow time (noise drift) and turn, per second, at rest and added at full energy. */
        const val DRIFT = 0.30f
        const val DRIFT_ENERGY = 0.35f
        const val TURN = 0.10f
        const val TURN_ENERGY = 0.22f

        private const val TWO_PI = (2 * Math.PI).toFloat()

        /** The time wraps long before a float loses its smoothness. */
        private const val TIME_WRAP = 10_000f
    }
}
