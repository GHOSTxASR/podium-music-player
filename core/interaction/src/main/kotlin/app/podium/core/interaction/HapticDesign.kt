package app.podium.core.interaction

import kotlin.math.max

/**
 * How Podium answers touch (Settings ▸ Haptics, D-105). [SYSTEM] uses only the phone's own feedback
 * constants, as Podium did before; the others play Podium's own compositions at their strength, on a
 * phone with full control of its motor (elsewhere every profile but [OFF] is the old vibration).
 */
enum class HapticProfile(val label: String) {
    BALANCED("Balanced"),
    TACTILE("More tactile"),
    REDUCED("Reduced"),
    SYSTEM("System default"),
    OFF("Off"),
}

/** How heavy an event feels (micro texture to multi-step contour), for strength and rate limiting. */
enum class HapticCategory { MICRO, LIGHT, MEDIUM, HEAVY, EMPHASIS }

/** The vibrator's composition primitives, by name, so the design stays pure (and testable off-device). */
enum class Primitive(val minSdk: Int) {
    CLICK(30), TICK(30), QUICK_RISE(30), SLOW_RISE(30), QUICK_FALL(30), LOW_TICK(31), THUD(31), SPIN(31),
}

/** One primitive in a composition: [scale] 0–1, starting [delayMs] after the previous one ends. */
data class HapticStep(val primitive: Primitive, val scale: Float, val delayMs: Int = 0)

/** A point on a continuous envelope (Android 16+): [intensity] and [sharpness] 0–1, reached over [durationMs]. */
data class EnvelopePoint(val intensity: Float, val sharpness: Float, val durationMs: Long)

/** The phone's predefined effects (the ladder's third rung). */
enum class PredefinedEffect { TICK, CLICK, HEAVY_CLICK, DOUBLE_CLICK }

/** The phone's own semantic feedback constants Podium uses (mapped by API level on the device). */
enum class SystemFeedback {
    FREQUENT_TICK, SEGMENT_TICK, CLOCK_TICK, KEY, KEYBOARD_TAP, CONTEXT_CLICK, LONG_PRESS, CONFIRM, REJECT,
    TOGGLE_ON, TOGGLE_OFF, THRESHOLD_ACTIVATE, THRESHOLD_DEACTIVATE, DRAG_START, GESTURE_END,
}

/** What to play for an event, after the fallback ladder (§3 of the haptics brief). */
sealed interface HapticPlan {
    /** Tier 1: a continuous envelope. */
    data class Envelope(val initialSharpness: Float, val points: List<EnvelopePoint>) : HapticPlan

    /** Tier 2: a composition of primitives. */
    data class Composition(val steps: List<HapticStep>) : HapticPlan

    /** Tier 3: a predefined effect. */
    data class Predefined(val effect: PredefinedEffect) : HapticPlan

    /** The phone's own feedback constant (honours its touch-feedback setting): the old vibration. */
    data class System(val feedback: SystemFeedback) : HapticPlan

    /** Tier 5: nothing — never a long buzz in place of a crisp tick. */
    data object None : HapticPlan
}

/** The Wheel's click sound, paired with the haptic it belongs to. */
enum class ClickSound { TICK, PRESS }

/**
 * What the phone's vibrator can do, found once at start (HapticEngine): which primitives it plays
 * and for how long, whether it controls amplitude, envelopes (Android 16+) and audio-coupled haptics.
 */
data class HapticCapabilities(
    val sdk: Int,
    val hasVibrator: Boolean,
    val hasAmplitudeControl: Boolean,
    val primitives: Set<Primitive>,
    val primitiveDurationsMs: Map<Primitive, Int> = emptyMap(),
    val envelopes: Boolean = false,
    val audioCoupled: Boolean = false,
) {
    /**
     * Full control of the motor: amplitude, and at least the click and tick primitives. Only then does
     * Podium shape its own haptics; any other phone gets the vibration Podium always had ([HapticDesign.legacy]).
     */
    val fullControl: Boolean get() = hasAmplitudeControl && Primitive.CLICK in primitives && Primitive.TICK in primitives

    fun duration(primitive: Primitive): Int = primitiveDurationsMs[primitive]?.takeIf { it > 0 } ?: EstimatedDurationsMs.getValue(primitive)

    companion object {
        val None = HapticCapabilities(sdk = 29, hasVibrator = false, hasAmplitudeControl = false, primitives = emptySet())

        /** Typical LRA durations, for phones that don't report them (Android 11). */
        private val EstimatedDurationsMs = mapOf(
            Primitive.CLICK to 12, Primitive.TICK to 8, Primitive.LOW_TICK to 12, Primitive.THUD to 30,
            Primitive.SPIN to 60, Primitive.QUICK_RISE to 50, Primitive.SLOW_RISE to 150, Primitive.QUICK_FALL to 40,
        )
    }
}

/**
 * What happened, semantically (§8): the screens say what the event is, the design decides how it
 * feels. Each has a [category] for strength and rate limiting.
 */
sealed interface HapticEvent {
    val category: HapticCategory

    /** The Wheel crossed a detent; [velocity] in detents per second (a full turn is 20). */
    data class WheelDetent(val velocity: Float) : HapticEvent {
        override val category get() = WheelHaptics.categoryOf(velocity)
    }

    /** The top or bottom of a list, the end of a range: something hard to push against. */
    data object WheelBoundary : HapticEvent { override val category = HapticCategory.HEAVY }

    /** The Wheel's centre went down. */
    data object CenterPress : HapticEvent { override val category = HapticCategory.MEDIUM }

    /** One of the Wheel's quarters, or a key on the display, went down. */
    data object ButtonPress : HapticEvent { override val category = HapticCategory.LIGHT }

    /** A key on Podium's keyboard. */
    data object KeyPress : HapticEvent { override val category = HapticCategory.LIGHT }

    /** A hold building toward its long press (a rising pressure, stopped on release). */
    data object HoldBuild : HapticEvent { override val category = HapticCategory.LIGHT }

    /** The hold locked in: a long press. */
    data object HoldLock : HapticEvent { override val category = HapticCategory.EMPHASIS }

    data object MenuOpen : HapticEvent { override val category = HapticCategory.MEDIUM }

    /** Into a screen ([forward]) or back out of one. */
    data class Navigate(val forward: Boolean) : HapticEvent { override val category = HapticCategory.MEDIUM }

    data class Toggle(val on: Boolean) : HapticEvent { override val category = HapticCategory.LIGHT }

    data object Confirm : HapticEvent { override val category = HapticCategory.MEDIUM }

    data object Reject : HapticEvent { override val category = HapticCategory.EMPHASIS }

    /** A step of a value (volume, a band of the equaliser, a lyric line); [level] 0–1 makes it stronger as it rises. */
    data class Step(val level: Float? = null) : HapticEvent { override val category = HapticCategory.LIGHT }

    /** A light tick that isn't a detent (the boot's beats). */
    data object Tick : HapticEvent { override val category = HapticCategory.MICRO }

    data class Skip(val forward: Boolean) : HapticEvent { override val category = HapticCategory.MEDIUM }

    data class PlayPause(val playing: Boolean) : HapticEvent { override val category = HapticCategory.MEDIUM }

    data class Favorite(val on: Boolean) : HapticEvent { override val category = HapticCategory.LIGHT }

    /** Something picked up to be moved (a song in Up Next or a playlist). */
    data object DragStart : HapticEvent { override val category = HapticCategory.MEDIUM }

    /** The lifted thing crossed a slot. */
    data object DragStep : HapticEvent { override val category = HapticCategory.MICRO }

    /** The lifted thing set down. */
    data object Drop : HapticEvent { override val category = HapticCategory.HEAVY }

    /** Something landed or struck ([impact] 0–1): the vinyl's needle, a bounce. */
    data class Collision(val impact: Float) : HapticEvent { override val category = HapticCategory.HEAVY }

    /** A magnetic point passed or settled on. */
    data object Snap : HapticEvent { override val category = HapticCategory.LIGHT }
}

/** The Wheel as precision hardware (§4): a detent's feel by how fast it turns. */
object WheelHaptics {
    /** Below this many detents a second, a turn is deliberate: each detent a distinct click. */
    const val SLOW = 8f

    /** At or above this (a turn a second), a spin: a fine ratchet. */
    const val FAST = 20f

    fun categoryOf(velocity: Float): HapticCategory = when {
        velocity >= FAST -> HapticCategory.MICRO
        else -> HapticCategory.LIGHT
    }

    /** The detent's primitive and strength: a bearing's click slow, a tick in between, a low tick in a spin. */
    fun detent(velocity: Float): HapticStep {
        val v = velocity.coerceAtLeast(0f)
        return when {
            v < SLOW -> HapticStep(Primitive.CLICK, 0.75f - 0.10f * (v / SLOW))
            v < FAST -> HapticStep(Primitive.TICK, 0.55f - 0.10f * ((v - SLOW) / (FAST - SLOW)))
            else -> HapticStep(Primitive.LOW_TICK, (0.35f - 0.15f * ((v - FAST) / FAST)).coerceAtLeast(0.20f))
        }
    }
}

/**
 * How each event feels, and the fallback ladder (§3): an envelope where the phone has them and the
 * event is continuous; else a composition of primitives it plays (each swapped for its nearest
 * cousin if missing, contour ornaments dropped); else a predefined effect; else the phone's own
 * constant; else nothing. Micro textures are never played on a motor without amplitude control.
 */
object HapticDesign {

    fun plan(event: HapticEvent, caps: HapticCapabilities, profile: HapticProfile): HapticPlan {
        if (profile == HapticProfile.OFF || !caps.hasVibrator) return HapticPlan.None
        // Without full control of the motor, or asked for: the vibration Podium always had.
        if (profile == HapticProfile.SYSTEM || !caps.fullControl) return legacy(event)?.let { HapticPlan.System(it) } ?: HapticPlan.None
        if (profile == HapticProfile.REDUCED && isTexture(event)) return HapticPlan.None
        val gain = when (profile) {
            HapticProfile.TACTILE -> 1.2f
            HapticProfile.REDUCED -> 0.6f
            else -> 1f
        }
        if (caps.envelopes) envelope(event, gain)?.let { return it }
        composition(event, profile)?.let { steps ->
            val playable = playable(steps.map { it.copy(scale = (it.scale * gain).coerceIn(0.05f, 1f)) }, caps)
            if (playable != null) return HapticPlan.Composition(playable)
        }
        if (event == HapticEvent.HoldBuild) return HapticPlan.None
        return HapticPlan.Predefined(predefined(event))
    }

    /** The click sound that goes with an event, if any (played with it, rate-limited with it). */
    fun sound(event: HapticEvent): ClickSound? = when (event) {
        is HapticEvent.WheelDetent, is HapticEvent.Step, HapticEvent.Tick, HapticEvent.DragStep, HapticEvent.Snap -> ClickSound.TICK
        HapticEvent.CenterPress, HapticEvent.ButtonPress, HapticEvent.KeyPress -> ClickSound.PRESS
        else -> null
    }

    /** How long a plan holds the vibrator, roughly, in ms. */
    fun durationMs(plan: HapticPlan, caps: HapticCapabilities): Int = when (plan) {
        is HapticPlan.Composition -> plan.steps.sumOf { caps.duration(it.primitive) + it.delayMs }
        is HapticPlan.Envelope -> plan.points.sumOf { it.durationMs }.toInt()
        is HapticPlan.Predefined -> if (plan.effect == PredefinedEffect.DOUBLE_CLICK) 120 else 20
        // The phone's constants are short; at 12 ms detents are kept 16 ms apart, as they always were.
        is HapticPlan.System -> 12
        HapticPlan.None -> 0
    }

    /** Fine textures a reduced profile leaves out: a spin's ratchet, a drag's slots, the boot's beats. */
    private fun isTexture(event: HapticEvent): Boolean = event.category == HapticCategory.MICRO

    /** Tier 1, for what is continuous by nature: a hold's rising pressure. */
    private fun envelope(event: HapticEvent, gain: Float): HapticPlan.Envelope? = when (event) {
        HapticEvent.HoldBuild -> HapticPlan.Envelope(
            initialSharpness = 0.3f,
            points = listOf(
                EnvelopePoint((0.10f * gain).coerceAtMost(1f), 0.35f, 60),
                EnvelopePoint((0.42f * gain).coerceAtMost(1f), 0.6f, 160),
            ),
        )
        else -> null
    }

    /** Tier 2: each event's composition (the brief's matrix, §2 and §6). */
    private fun composition(event: HapticEvent, profile: HapticProfile): List<HapticStep>? {
        val rich = profile == HapticProfile.TACTILE
        return when (event) {
            is HapticEvent.WheelDetent -> listOf(WheelHaptics.detent(event.velocity))
            HapticEvent.WheelBoundary -> if (rich) {
                listOf(HapticStep(Primitive.THUD, 0.9f), HapticStep(Primitive.CLICK, 0.5f, 20))
            } else {
                listOf(HapticStep(Primitive.THUD, 0.85f))
            }
            HapticEvent.CenterPress -> if (rich) {
                // A two-part latch, as a real key travels and seats.
                listOf(HapticStep(Primitive.TICK, 0.45f), HapticStep(Primitive.CLICK, 0.85f, 14))
            } else {
                listOf(HapticStep(Primitive.CLICK, 0.8f))
            }
            HapticEvent.ButtonPress -> listOf(HapticStep(Primitive.CLICK, 0.7f))
            HapticEvent.KeyPress -> listOf(HapticStep(Primitive.TICK, 0.6f))
            HapticEvent.HoldBuild -> listOf(HapticStep(Primitive.SLOW_RISE, 0.35f))
            HapticEvent.HoldLock -> if (rich) {
                listOf(HapticStep(Primitive.QUICK_RISE, 0.5f), HapticStep(Primitive.THUD, 0.9f), HapticStep(Primitive.CLICK, 0.8f, 20))
            } else {
                listOf(HapticStep(Primitive.THUD, 0.85f), HapticStep(Primitive.CLICK, 0.75f, 20))
            }
            HapticEvent.MenuOpen -> listOf(HapticStep(Primitive.TICK, 0.5f), HapticStep(Primitive.QUICK_FALL, 0.5f))
            is HapticEvent.Navigate -> if (event.forward) {
                listOf(HapticStep(Primitive.TICK, 0.45f), HapticStep(Primitive.CLICK, 0.65f, 30))
            } else {
                listOf(HapticStep(Primitive.CLICK, 0.6f), HapticStep(Primitive.TICK, 0.35f, 30))
            }
            is HapticEvent.Toggle -> if (event.on) {
                listOf(HapticStep(Primitive.TICK, 0.5f), HapticStep(Primitive.CLICK, 0.8f, 25))
            } else {
                listOf(HapticStep(Primitive.CLICK, 0.6f), HapticStep(Primitive.LOW_TICK, 0.4f, 25))
            }
            HapticEvent.Confirm -> listOf(HapticStep(Primitive.TICK, 0.5f), HapticStep(Primitive.CLICK, 0.85f, 40))
            HapticEvent.Reject -> listOf(HapticStep(Primitive.THUD, 0.7f), HapticStep(Primitive.THUD, 0.5f, 70))
            is HapticEvent.Step -> listOf(HapticStep(Primitive.TICK, 0.35f + 0.35f * (event.level ?: 0.45f).coerceIn(0f, 1f)))
            HapticEvent.Tick -> listOf(HapticStep(Primitive.TICK, 0.4f))
            is HapticEvent.Skip -> if (event.forward) {
                listOf(HapticStep(Primitive.CLICK, 0.75f), HapticStep(Primitive.LOW_TICK, 0.4f, 35))
            } else {
                listOf(HapticStep(Primitive.LOW_TICK, 0.4f), HapticStep(Primitive.CLICK, 0.75f, 35))
            }
            is HapticEvent.PlayPause -> if (event.playing) {
                listOf(HapticStep(Primitive.CLICK, 0.85f))
            } else {
                listOf(HapticStep(Primitive.TICK, 0.6f), HapticStep(Primitive.QUICK_FALL, 0.45f))
            }
            is HapticEvent.Favorite -> if (event.on) {
                listOf(HapticStep(Primitive.QUICK_RISE, 0.35f), HapticStep(Primitive.CLICK, 0.9f))
            } else {
                listOf(HapticStep(Primitive.TICK, 0.45f))
            }
            HapticEvent.DragStart -> listOf(HapticStep(Primitive.QUICK_RISE, 0.5f), HapticStep(Primitive.CLICK, 0.7f))
            HapticEvent.DragStep -> listOf(HapticStep(Primitive.LOW_TICK, 0.4f))
            HapticEvent.Drop -> listOf(HapticStep(Primitive.THUD, 0.85f))
            is HapticEvent.Collision -> listOf(HapticStep(Primitive.THUD, event.impact.coerceIn(0.15f, 1f)))
            HapticEvent.Snap -> listOf(HapticStep(Primitive.TICK, 0.4f))
        }
    }

    /**
     * The steps this phone can play: a missing primitive swapped for its nearest cousin (a low tick
     * for a tick, a thud for a firm click, a spin for a tick), a missing rise or fall (an ornament)
     * dropped. Null when nothing playable is left.
     */
    internal fun playable(steps: List<HapticStep>, caps: HapticCapabilities): List<HapticStep>? {
        val out = steps.mapNotNull { step ->
            if (step.primitive in caps.primitives) return@mapNotNull step
            val cousin = when (step.primitive) {
                Primitive.LOW_TICK -> Primitive.TICK to 0.7f
                Primitive.THUD -> Primitive.CLICK to 1f
                Primitive.SPIN -> Primitive.TICK to 1f
                Primitive.TICK -> Primitive.CLICK to 0.6f
                else -> null
            } ?: return@mapNotNull null
            if (cousin.first in caps.primitives) step.copy(primitive = cousin.first, scale = step.scale * cousin.second) else null
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** Tier 3, by weight. */
    private fun predefined(event: HapticEvent): PredefinedEffect = when (event.category) {
        HapticCategory.MICRO, HapticCategory.LIGHT -> PredefinedEffect.TICK
        HapticCategory.MEDIUM -> PredefinedEffect.CLICK
        HapticCategory.HEAVY -> PredefinedEffect.HEAVY_CLICK
        HapticCategory.EMPHASIS -> PredefinedEffect.DOUBLE_CLICK
    }

    /**
     * The vibration Podium had before D-105, event by event: the phone's own constants, as the GitHub
     * release played them (a detent's frequent tick, a step's segment tick, a key, a long press, a
     * confirm or a reject). Played on phones without full control of their motor and by the System
     * default profile. What had no vibration then has none here, except what a turn of the Wheel
     * answers (a slot passed while moving a song), which keeps the detent it used to get.
     */
    fun legacy(event: HapticEvent): SystemFeedback? = when (event) {
        is HapticEvent.WheelDetent, HapticEvent.KeyPress, HapticEvent.DragStep -> SystemFeedback.FREQUENT_TICK
        is HapticEvent.Step, HapticEvent.Snap -> SystemFeedback.SEGMENT_TICK
        HapticEvent.WheelBoundary -> SystemFeedback.THRESHOLD_DEACTIVATE
        HapticEvent.CenterPress, HapticEvent.ButtonPress, is HapticEvent.Skip, is HapticEvent.PlayPause -> SystemFeedback.KEY
        HapticEvent.HoldLock -> SystemFeedback.LONG_PRESS
        HapticEvent.Confirm, HapticEvent.Drop, is HapticEvent.Toggle, is HapticEvent.Favorite -> SystemFeedback.CONFIRM
        HapticEvent.Reject -> SystemFeedback.REJECT
        HapticEvent.Tick -> SystemFeedback.CLOCK_TICK
        HapticEvent.HoldBuild, HapticEvent.MenuOpen, is HapticEvent.Navigate, HapticEvent.DragStart, is HapticEvent.Collision -> null
    }
}

/**
 * The cooldown law (§4): textures (detents, steps, ticks) never closer together than the vibrator
 * can play them, max(15 ms, duration + 4 ms) — in a fast spin the extra detents are dropped, so the
 * motor never falls behind the finger. Heavier events always play (a new effect replaces whatever
 * is playing), each kind no more often than its own cooldown, and while one plays no texture cuts it.
 */
class HapticRateLimiter {
    private var textureFreeAt = 0L
    private val heavyAt = HashMap<Class<*>, Long>()

    fun admit(event: HapticEvent, durationMs: Int, nowMs: Long): Boolean {
        val texture = event.category == HapticCategory.MICRO ||
            (event.category == HapticCategory.LIGHT && event !is HapticEvent.Toggle && event !is HapticEvent.Favorite)
        if (texture) {
            if (nowMs < textureFreeAt) return false
            textureFreeAt = nowMs + max(MIN_TEXTURE_GAP_MS, durationMs + 4).toLong()
            return true
        }
        val kind = event.javaClass
        val last = heavyAt[kind]
        if (last != null && nowMs - last < cooldownOf(event)) return false
        heavyAt[kind] = nowMs
        textureFreeAt = max(textureFreeAt, nowMs + durationMs)
        return true
    }

    /** Clean termination (§4): a released wheel or ended gesture starts afresh. */
    fun reset() {
        textureFreeAt = 0L
        heavyAt.clear()
    }

    private fun cooldownOf(event: HapticEvent): Long = when (event) {
        // Spinning into the end of a list knocks once per beat, not once per detent.
        HapticEvent.WheelBoundary -> 120L
        HapticEvent.Reject -> 150L
        else -> 40L
    }

    companion object {
        const val MIN_TEXTURE_GAP_MS = 15
    }
}
