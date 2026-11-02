package app.podium.core.interaction

import android.content.Context
import android.media.AudioAttributes
import android.media.audiofx.HapticGenerator
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView

/** The Wheel's audible click (D-32): a tick per detent, a firmer click per press. */
interface Clicker {
    fun tick()
    fun press()

    companion object {
        val Silent = object : Clicker {
            override fun tick() = Unit
            override fun press() = Unit
        }
    }
}

/** How the device answers touch, from Settings: the haptic profile (D-105), and the click sound. */
@Immutable
data class Feedback(val profile: HapticProfile = HapticProfile.BALANCED, val clicker: Clicker = Clicker.Silent)

val LocalFeedback = compositionLocalOf { Feedback() }

/** The app's one haptic engine (provided by the app; screens without one make their own). */
val LocalHapticEngine = staticCompositionLocalOf<HapticEngine?> { null }

/**
 * Podium's haptics (D-105): one engine for the whole app. It finds once what the vibrator can do
 * ([capabilities]), turns each semantic event into the right effect for this phone and profile
 * ([HapticDesign]'s ladder), keeps the built effects, rate-limits ([HapticRateLimiter]) and plays
 * the click sound with the haptic it belongs to, so the two never drift apart.
 *
 * Effects go to the vibrator as touch feedback, which the phone's own touch-feedback setting
 * governs (Android 13 and later; before that Podium reads the setting itself); the phone's own
 * constants go through the view. A hold's rising pressure is the one long effect: it stops the
 * moment the finger lifts or the Wheel turns ([cancelHold]).
 */
class HapticEngine(context: Context, private val view: View?) {
    private val appContext = context.applicationContext
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    val capabilities: HapticCapabilities = detect(vibrator)
    private val limiter = HapticRateLimiter()
    private val effects = HashMap<HapticPlan, VibrationEffect>()
    private var holding = false

    /** Counts the events asked for (whether they played or were rate-limited): see [mark]. */
    private var asked = 0L

    /**
     * Before handing an input on: [askedSince] then says whether whoever handled it gave its own
     * feedback (a boundary, a volume step), so the Wheel's plain detent is played only when nobody
     * did — never both.
     */
    fun mark(): Long = asked

    fun askedSince(mark: Long): Boolean = asked != mark

    fun perform(event: HapticEvent, profile: HapticProfile, clicker: Clicker) {
        asked++
        val now = SystemClock.uptimeMillis()
        val plan = HapticDesign.plan(event, capabilities, profile)
        if (!limiter.admit(event, HapticDesign.durationMs(plan, capabilities), now)) return
        holding = event == HapticEvent.HoldBuild && plan != HapticPlan.None
        play(plan)
        when (HapticDesign.sound(event)) {
            ClickSound.TICK -> clicker.tick()
            ClickSound.PRESS -> clicker.press()
            null -> Unit
        }
    }

    /** Stops a hold's rising pressure (the finger lifted, or the Wheel turned); nothing else. */
    fun cancelHold() {
        if (!holding) return
        holding = false
        runCatching { vibrator?.cancel() }
    }

    /** A gesture ended: the next starts afresh (§4, clean termination). */
    fun endGesture() {
        cancelHold()
        limiter.reset()
    }

    private fun play(plan: HapticPlan) {
        when (plan) {
            HapticPlan.None -> Unit
            is HapticPlan.System -> view?.performHapticFeedback(constantOf(plan.feedback))
            else -> {
                val vibrator = vibrator ?: return
                if (Build.VERSION.SDK_INT < 33 && !touchFeedbackOn()) return
                val effect = effects[plan] ?: build(plan)?.also { effects[plan] = it } ?: return
                runCatching {
                    if (Build.VERSION.SDK_INT >= 33) {
                        vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(effect, TouchAudio)
                    }
                }
            }
        }
    }

    private fun build(plan: HapticPlan): VibrationEffect? = runCatching {
        when (plan) {
            is HapticPlan.Composition -> if (Build.VERSION.SDK_INT >= 30) {
                VibrationEffect.startComposition().apply {
                    plan.steps.forEach { addPrimitive(idOf(it.primitive), it.scale, it.delayMs) }
                }.compose()
            } else {
                null
            }
            is HapticPlan.Envelope -> if (Build.VERSION.SDK_INT >= 36) {
                VibrationEffect.BasicEnvelopeBuilder().apply {
                    setInitialSharpness(plan.initialSharpness)
                    plan.points.forEach { addControlPoint(it.intensity, it.sharpness, it.durationMs) }
                }.build()
            } else {
                null
            }
            is HapticPlan.Predefined -> VibrationEffect.createPredefined(effectIdOf(plan.effect))
            else -> null
        }
    }.getOrNull()

    /** Before Android 13 the system's touch-feedback switch isn't applied to the vibrator for us. */
    private fun touchFeedbackOn(): Boolean =
        runCatching { Settings.System.getInt(appContext.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0 }.getOrDefault(true)

    private companion object {
        @Suppress("DEPRECATION")
        val TouchAudio: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        fun detect(vibrator: Vibrator?): HapticCapabilities {
            if (vibrator == null || !vibrator.hasVibrator()) return HapticCapabilities.None
            val sdk = Build.VERSION.SDK_INT
            val candidates = Primitive.entries.filter { it.minSdk <= sdk }
            val primitives = if (sdk >= 30 && candidates.isNotEmpty()) {
                val ids = candidates.map(::idOf).toIntArray()
                val supported = runCatching { vibrator.arePrimitivesSupported(*ids) }.getOrNull()
                candidates.filterIndexed { i, _ -> supported?.getOrNull(i) == true }.toSet()
            } else {
                emptySet()
            }
            val durations = if (sdk >= 31 && primitives.isNotEmpty()) {
                val list = primitives.toList()
                val ms = runCatching { vibrator.getPrimitiveDurations(*list.map(::idOf).toIntArray()) }.getOrNull()
                list.indices.associate { list[it] to (ms?.getOrNull(it) ?: 0) }
            } else {
                emptyMap()
            }
            return HapticCapabilities(
                sdk = sdk,
                hasVibrator = true,
                hasAmplitudeControl = vibrator.hasAmplitudeControl(),
                primitives = primitives,
                primitiveDurationsMs = durations,
                envelopes = sdk >= 36 && runCatching { vibrator.areEnvelopeEffectsSupported() }.getOrDefault(false),
                audioCoupled = sdk >= 31 && runCatching { HapticGenerator.isAvailable() }.getOrDefault(false),
            )
        }

        fun effectIdOf(effect: PredefinedEffect): Int = when (effect) {
            PredefinedEffect.TICK -> VibrationEffect.EFFECT_TICK
            PredefinedEffect.CLICK -> VibrationEffect.EFFECT_CLICK
            PredefinedEffect.HEAVY_CLICK -> VibrationEffect.EFFECT_HEAVY_CLICK
            PredefinedEffect.DOUBLE_CLICK -> VibrationEffect.EFFECT_DOUBLE_CLICK
        }

        fun idOf(primitive: Primitive): Int = when (primitive) {
            Primitive.CLICK -> VibrationEffect.Composition.PRIMITIVE_CLICK
            Primitive.TICK -> VibrationEffect.Composition.PRIMITIVE_TICK
            Primitive.QUICK_RISE -> VibrationEffect.Composition.PRIMITIVE_QUICK_RISE
            Primitive.SLOW_RISE -> VibrationEffect.Composition.PRIMITIVE_SLOW_RISE
            Primitive.QUICK_FALL -> VibrationEffect.Composition.PRIMITIVE_QUICK_FALL
            Primitive.LOW_TICK -> VibrationEffect.Composition.PRIMITIVE_LOW_TICK
            Primitive.THUD -> VibrationEffect.Composition.PRIMITIVE_THUD
            Primitive.SPIN -> VibrationEffect.Composition.PRIMITIVE_SPIN
        }

        /** The phone's constant for each [SystemFeedback], with older constants before their API level. */
        fun constantOf(feedback: SystemFeedback): Int {
            val sdk = Build.VERSION.SDK_INT
            return when (feedback) {
                SystemFeedback.FREQUENT_TICK -> if (sdk >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK
                SystemFeedback.SEGMENT_TICK -> if (sdk >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
                SystemFeedback.CLOCK_TICK -> HapticFeedbackConstants.CLOCK_TICK
                SystemFeedback.KEY -> HapticFeedbackConstants.VIRTUAL_KEY
                SystemFeedback.KEYBOARD_TAP -> HapticFeedbackConstants.KEYBOARD_TAP
                SystemFeedback.CONTEXT_CLICK -> HapticFeedbackConstants.CONTEXT_CLICK
                SystemFeedback.LONG_PRESS -> HapticFeedbackConstants.LONG_PRESS
                SystemFeedback.CONFIRM -> if (sdk >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
                SystemFeedback.REJECT -> if (sdk >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
                SystemFeedback.TOGGLE_ON -> if (sdk >= 34) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.VIRTUAL_KEY
                SystemFeedback.TOGGLE_OFF -> if (sdk >= 34) HapticFeedbackConstants.TOGGLE_OFF else HapticFeedbackConstants.VIRTUAL_KEY
                SystemFeedback.THRESHOLD_ACTIVATE -> if (sdk >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CONTEXT_CLICK
                SystemFeedback.THRESHOLD_DEACTIVATE -> if (sdk >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE else HapticFeedbackConstants.CONTEXT_CLICK
                SystemFeedback.DRAG_START -> if (sdk >= 34) HapticFeedbackConstants.DRAG_START else HapticFeedbackConstants.LONG_PRESS
                SystemFeedback.GESTURE_END -> if (sdk >= 30) HapticFeedbackConstants.GESTURE_END else HapticFeedbackConstants.CONTEXT_CLICK
            }
        }
    }
}

/**
 * What screens call (design-system.md §9): each method names what happened, and the engine decides
 * how it feels for this phone and the listener's profile. [detent] and [step] are textures, rate
 * limited so fast spins never buzz; the rest are events in their own right.
 */
class PodiumHaptics(private val engine: HapticEngine, private val feedback: Feedback = Feedback()) {

    /** Whether Podium shapes its own haptics on this phone, or plays the phone's own (see [HapticCapabilities.fullControl]). */
    val fullControl: Boolean get() = engine.capabilities.fullControl

    /** The Wheel crossed a detent; [velocity] in detents per second sets its feel. */
    fun detent(velocity: Float = 0f) = perform(HapticEvent.WheelDetent(velocity))

    /** A value stepped (volume, a band, a line); [level] 0–1 makes it firmer as it rises. */
    fun step(level: Float? = null) = perform(HapticEvent.Step(level))

    /** The start or end of a list, the edge of a range. */
    fun boundary() = perform(HapticEvent.WheelBoundary)

    /** A Wheel quarter or a key on the display went down. */
    fun press() = perform(HapticEvent.ButtonPress)

    /** The Wheel's centre went down. */
    fun centerPress() = perform(HapticEvent.CenterPress)

    /** A key on Podium's keyboard. */
    fun keyPress() = perform(HapticEvent.KeyPress)

    /** A hold building toward its long press: a rising pressure until [releaseHold] or [longPress]. */
    fun holdBuild() = perform(HapticEvent.HoldBuild)

    /** The finger lifted or the Wheel turned before the hold locked in. */
    fun releaseHold() = engine.cancelHold()

    /** The gesture is over: rate limits and holds start afresh. */
    fun endGesture() = engine.endGesture()

    /** The hold locked in. */
    fun longPress() = perform(HapticEvent.HoldLock)

    fun confirm() = perform(HapticEvent.Confirm)

    fun reject() = perform(HapticEvent.Reject)

    /** A light tick that isn't a Wheel detent (the boot's beats). */
    fun tick() = perform(HapticEvent.Tick)

    fun menuOpen() = perform(HapticEvent.MenuOpen)

    fun navigate(forward: Boolean) = perform(HapticEvent.Navigate(forward))

    fun toggle(on: Boolean) = perform(HapticEvent.Toggle(on))

    fun skip(forward: Boolean) = perform(HapticEvent.Skip(forward))

    fun playPause(playing: Boolean) = perform(HapticEvent.PlayPause(playing))

    fun favorite(on: Boolean) = perform(HapticEvent.Favorite(on))

    fun dragStart() = perform(HapticEvent.DragStart)

    fun dragStep() = perform(HapticEvent.DragStep)

    fun drop() = perform(HapticEvent.Drop)

    fun collision(impact: Float) = perform(HapticEvent.Collision(impact))

    fun snap() = perform(HapticEvent.Snap)

    fun perform(event: HapticEvent) = engine.perform(event, feedback.profile, feedback.clicker)

    /** See [HapticEngine.mark]. */
    fun mark(): Long = engine.mark()

    fun askedSince(mark: Long): Boolean = engine.askedSince(mark)
}

@Composable
fun rememberPodiumHaptics(): PodiumHaptics {
    val view = LocalView.current
    val feedback = LocalFeedback.current
    val engine = LocalHapticEngine.current ?: remember(view) { HapticEngine(view.context, view) }
    return remember(engine, feedback) { PodiumHaptics(engine, feedback) }
}
