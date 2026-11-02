package app.podium.core.interaction

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Podium's haptic design (D-105): the fallback ladder, the Wheel's feel by speed, the cooldown law. */
class HapticDesignTest {

    /** A wideband LRA (Pixel, OnePlus, Nothing) on Android 16: everything. */
    private val rich = HapticCapabilities(
        sdk = 36,
        hasVibrator = true,
        hasAmplitudeControl = true,
        primitives = Primitive.entries.toSet(),
        primitiveDurationsMs = mapOf(Primitive.TICK to 8, Primitive.CLICK to 12, Primitive.LOW_TICK to 12, Primitive.THUD to 30),
        envelopes = true,
    )

    /** Android 11 with an LRA: compositions, but no low tick, thud or spin yet, no envelopes. */
    private val android11 = HapticCapabilities(
        sdk = 30,
        hasVibrator = true,
        hasAmplitudeControl = true,
        primitives = setOf(Primitive.CLICK, Primitive.TICK, Primitive.QUICK_RISE, Primitive.SLOW_RISE, Primitive.QUICK_FALL),
    )

    /** An old rotating motor: no primitives, no amplitude control. */
    private val erm = HapticCapabilities(sdk = 29, hasVibrator = true, hasAmplitudeControl = false, primitives = emptySet())

    /**
     * A linear motor whose driver plays only its own built-in effects: no primitives, no amplitude
     * control (the Nothing Phone (3a) on Android 17 reports exactly this).
     */
    private val builtInOnly = HapticCapabilities(sdk = 37, hasVibrator = true, hasAmplitudeControl = false, primitives = emptySet())

    @Test
    fun offAndNoVibratorPlayNothing() {
        assertEquals(HapticPlan.None, HapticDesign.plan(HapticEvent.Confirm, rich, HapticProfile.OFF))
        assertEquals(HapticPlan.None, HapticDesign.plan(HapticEvent.Confirm, HapticCapabilities.None, HapticProfile.BALANCED))
    }

    @Test
    fun systemUsesThePhonesOwnConstantsAsBefore() {
        assertEquals(HapticPlan.System(SystemFeedback.FREQUENT_TICK), HapticDesign.plan(HapticEvent.WheelDetent(3f), rich, HapticProfile.SYSTEM))
        assertEquals(HapticPlan.System(SystemFeedback.THRESHOLD_DEACTIVATE), HapticDesign.plan(HapticEvent.WheelBoundary, rich, HapticProfile.SYSTEM))
        assertEquals(HapticPlan.System(SystemFeedback.KEY), HapticDesign.plan(HapticEvent.ButtonPress, rich, HapticProfile.SYSTEM))
        assertEquals(HapticPlan.System(SystemFeedback.CONFIRM), HapticDesign.plan(HapticEvent.Confirm, rich, HapticProfile.SYSTEM))
        assertEquals(HapticPlan.System(SystemFeedback.REJECT), HapticDesign.plan(HapticEvent.Reject, rich, HapticProfile.SYSTEM))
    }

    @Test
    fun withoutFullControlOfTheMotorEveryProfilePlaysTheOldVibration() {
        assertTrue(rich.fullControl && android11.fullControl)
        assertFalse(builtInOnly.fullControl || erm.fullControl)
        listOf(builtInOnly, erm).forEach { phone ->
            listOf(HapticProfile.BALANCED, HapticProfile.TACTILE, HapticProfile.REDUCED).forEach { profile ->
                fun plan(event: HapticEvent) = HapticDesign.plan(event, phone, profile)
                // What the GitHub release played, call for call.
                assertEquals(HapticPlan.System(SystemFeedback.FREQUENT_TICK), plan(HapticEvent.WheelDetent(30f)))
                assertEquals(HapticPlan.System(SystemFeedback.SEGMENT_TICK), plan(HapticEvent.Step(0.5f)))
                assertEquals(HapticPlan.System(SystemFeedback.THRESHOLD_DEACTIVATE), plan(HapticEvent.WheelBoundary))
                assertEquals(HapticPlan.System(SystemFeedback.KEY), plan(HapticEvent.CenterPress))
                assertEquals(HapticPlan.System(SystemFeedback.LONG_PRESS), plan(HapticEvent.HoldLock))
                assertEquals(HapticPlan.System(SystemFeedback.CONFIRM), plan(HapticEvent.Toggle(on = false)))
                assertEquals(HapticPlan.System(SystemFeedback.REJECT), plan(HapticEvent.Reject))
                // A slot passed while moving a song keeps the detent the Wheel always gave it.
                assertEquals(HapticPlan.System(SystemFeedback.FREQUENT_TICK), plan(HapticEvent.DragStep))
                // What had no vibration then has none now.
                assertEquals(HapticPlan.None, plan(HapticEvent.MenuOpen))
                assertEquals(HapticPlan.None, plan(HapticEvent.Navigate(forward = true)))
                assertEquals(HapticPlan.None, plan(HapticEvent.HoldBuild))
                assertEquals(HapticPlan.None, plan(HapticEvent.Collision(0.5f)))
            }
            assertEquals(HapticPlan.None, HapticDesign.plan(HapticEvent.CenterPress, phone, HapticProfile.OFF))
        }
    }

    @Test
    fun theOldDetentsStaySixteenMillisecondsApart() {
        val limiter = HapticRateLimiter()
        val detent = HapticEvent.WheelDetent(30f)
        val ms = HapticDesign.durationMs(HapticDesign.plan(detent, builtInOnly, HapticProfile.BALANCED), builtInOnly)
        assertTrue(limiter.admit(detent, ms, nowMs = 1_000))
        assertFalse(limiter.admit(detent, ms, nowMs = 1_015))
        assertTrue(limiter.admit(detent, ms, nowMs = 1_016))
    }

    @Test
    fun theWheelClicksSlowTicksInBetweenAndRatchetsInASpin() {
        fun step(velocity: Float) = (HapticDesign.plan(HapticEvent.WheelDetent(velocity), rich, HapticProfile.BALANCED) as HapticPlan.Composition).steps.single()
        assertEquals(Primitive.CLICK, step(2f).primitive)
        assertEquals(Primitive.TICK, step(12f).primitive)
        assertEquals(Primitive.LOW_TICK, step(30f).primitive)
        // Within each tier, faster is lighter, and a spin never goes below the floor.
        assertTrue(step(1f).scale > step(7f).scale)
        assertTrue(step(9f).scale > step(19f).scale)
        assertTrue(step(200f).scale >= 0.2f)
        assertTrue(step(0f).scale in 0.65f..0.75f && step(12f).scale in 0.45f..0.55f && step(25f).scale in 0.2f..0.35f)
    }

    @Test
    fun missingPrimitivesFallToTheirNearestCousins() {
        // Android 11 has no low tick: a spin's ratchet becomes a lighter tick; a thud becomes a click.
        val spin = HapticDesign.plan(HapticEvent.WheelDetent(30f), android11, HapticProfile.BALANCED) as HapticPlan.Composition
        assertEquals(Primitive.TICK, spin.steps.single().primitive)
        assertTrue(spin.steps.single().scale < 0.35f)
        val boundary = HapticDesign.plan(HapticEvent.WheelBoundary, android11, HapticProfile.BALANCED) as HapticPlan.Composition
        assertEquals(Primitive.CLICK, boundary.steps.single().primitive)
    }

    @Test
    fun aHoldRisesOnAnEnvelopeWhereThePhoneHasThem() {
        assertIs<HapticPlan.Envelope>(HapticDesign.plan(HapticEvent.HoldBuild, rich, HapticProfile.BALANCED))
        val older = HapticDesign.plan(HapticEvent.HoldBuild, android11, HapticProfile.BALANCED) as HapticPlan.Composition
        assertEquals(Primitive.SLOW_RISE, older.steps.single().primitive)
    }

    @Test
    fun reducedLeavesOutTexturesAndSoftensTheRest() {
        assertEquals(HapticPlan.None, HapticDesign.plan(HapticEvent.WheelDetent(30f), rich, HapticProfile.REDUCED))
        assertEquals(HapticPlan.None, HapticDesign.plan(HapticEvent.DragStep, rich, HapticProfile.REDUCED))
        val balanced = HapticDesign.plan(HapticEvent.ButtonPress, rich, HapticProfile.BALANCED) as HapticPlan.Composition
        val reduced = HapticDesign.plan(HapticEvent.ButtonPress, rich, HapticProfile.REDUCED) as HapticPlan.Composition
        assertEquals(balanced.steps.single().scale * 0.6f, reduced.steps.single().scale, 0.001f)
    }

    @Test
    fun moreTactileIsRicherAndFirmerButNeverPastFull() {
        val centre = HapticDesign.plan(HapticEvent.CenterPress, rich, HapticProfile.TACTILE) as HapticPlan.Composition
        assertEquals(listOf(Primitive.TICK, Primitive.CLICK), centre.steps.map { it.primitive }, "a two-part latch")
        val lock = HapticDesign.plan(HapticEvent.HoldLock, rich, HapticProfile.TACTILE) as HapticPlan.Composition
        assertTrue(lock.steps.all { it.scale <= 1f })
    }

    @Test
    fun eventsSayWhatTheyAre() {
        val on = HapticDesign.plan(HapticEvent.Toggle(on = true), rich, HapticProfile.BALANCED) as HapticPlan.Composition
        val off = HapticDesign.plan(HapticEvent.Toggle(on = false), rich, HapticProfile.BALANCED) as HapticPlan.Composition
        assertTrue(on.steps.last().scale > on.steps.first().scale, "on rises")
        assertTrue(off.steps.last().scale < off.steps.first().scale, "off falls")
        val collision = { impact: Float -> (HapticDesign.plan(HapticEvent.Collision(impact), rich, HapticProfile.BALANCED) as HapticPlan.Composition).steps.single().scale }
        assertTrue(collision(0.9f) > collision(0.3f))
        assertEquals(0.15f, collision(0f), 0.001f)
    }

    @Test
    fun soundsGoWithTheirHaptics() {
        assertEquals(ClickSound.TICK, HapticDesign.sound(HapticEvent.WheelDetent(5f)))
        assertEquals(ClickSound.PRESS, HapticDesign.sound(HapticEvent.CenterPress))
        assertNull(HapticDesign.sound(HapticEvent.Confirm))
    }

    @Test
    fun texturesKeepTheCooldownLaw() {
        val limiter = HapticRateLimiter()
        val detent = HapticEvent.WheelDetent(30f)
        assertTrue(limiter.admit(detent, durationMs = 8, nowMs = 1_000))
        // Closer than max(15 ms, duration + 4 ms): dropped, so the motor never falls behind the finger.
        assertFalse(limiter.admit(detent, durationMs = 8, nowMs = 1_010))
        assertTrue(limiter.admit(detent, durationMs = 8, nowMs = 1_016))
        // A longer primitive spaces them further.
        assertTrue(limiter.admit(detent, durationMs = 20, nowMs = 1_100))
        assertFalse(limiter.admit(detent, durationMs = 20, nowMs = 1_120))
        assertTrue(limiter.admit(detent, durationMs = 20, nowMs = 1_124))
    }

    @Test
    fun heavyEventsCutInButDontRepeatTooFast() {
        val limiter = HapticRateLimiter()
        assertTrue(limiter.admit(HapticEvent.WheelDetent(5f), durationMs = 12, nowMs = 0))
        // The end of a list right after a detent: it still lands.
        assertTrue(limiter.admit(HapticEvent.WheelBoundary, durationMs = 30, nowMs = 5))
        // While it plays, no texture cuts it.
        assertFalse(limiter.admit(HapticEvent.WheelDetent(5f), durationMs = 12, nowMs = 20))
        // Spinning on against the end: once per beat, not per detent.
        assertFalse(limiter.admit(HapticEvent.WheelBoundary, durationMs = 30, nowMs = 60))
        assertTrue(limiter.admit(HapticEvent.WheelBoundary, durationMs = 30, nowMs = 130))
    }

    @Test
    fun aGestureEndingStartsAfresh() {
        val limiter = HapticRateLimiter()
        assertTrue(limiter.admit(HapticEvent.WheelBoundary, durationMs = 30, nowMs = 0))
        limiter.reset()
        assertTrue(limiter.admit(HapticEvent.WheelDetent(5f), durationMs = 12, nowMs = 1))
        assertTrue(limiter.admit(HapticEvent.WheelBoundary, durationMs = 30, nowMs = 2))
    }
}
