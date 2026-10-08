package app.podium.guide

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The tour's state (D-57): offered once, checkpoints passed by doing, Next/Back/Skip, replayable. */
@RunWith(AndroidJUnit4::class)
class GuideTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `offered on a first launch, and never again once skipped`() {
        val store = GuideStore(context)
        val guide = GuideState(store)
        guide.offer()
        assertEquals(GuideStep.WELCOME, guide.step)
        guide.skip()
        assertNull(guide.step)
        assertTrue(store.seen.value)
        val later = GuideState(GuideStore(context))
        later.offer()
        assertNull(later.step, "a restart reads the same answer")
    }

    @Test
    fun `checkpoints in order, each passed only while it shows`() {
        val guide = GuideState(GuideStore(context))
        guide.offer()
        guide.pass(GuideStep.WELCOME)
        assertTrue(guide.passed.isEmpty(), "the welcome isn't a checkpoint")
        guide.begin()
        assertEquals(GuideStep.TURN, guide.step)
        guide.pass(GuideStep.CENTER)
        assertTrue(guide.passed.isEmpty(), "only the checkpoint showing can be passed")
        guide.pass(GuideStep.TURN)
        assertEquals(setOf(GuideStep.TURN), guide.passed)
        val order = listOf(GuideStep.CENTER, GuideStep.MENU, GuideStep.PLAY, GuideStep.PINCH, GuideStep.STICKERS)
        for (s in order) {
            guide.next()
            assertEquals(s, guide.step)
        }
        guide.back()
        assertEquals(GuideStep.PINCH, guide.step)
        guide.next()
        guide.next()
        assertNull(guide.step, "Next on the last checkpoint finishes")
        assertTrue(GuideStore(context).seen.value)
    }

    @Test
    fun `back stops at the first checkpoint`() {
        val guide = GuideState(GuideStore(context))
        guide.begin()
        guide.back()
        assertEquals(GuideStep.TURN, guide.step)
        assertEquals("Step 1 of 6", guide.step?.counter)
        assertNull(GuideStep.WELCOME.counter)
    }

    @Test
    fun `Help replays it after it was seen, and debug forget offers it again`() {
        val store = GuideStore(context).apply { markSeen() }
        val guide = GuideState(store)
        guide.offer()
        assertNull(guide.step)
        guide.begin()
        assertEquals(GuideStep.TURN, guide.step)
        guide.finish()
        store.forget()
        assertFalse(store.seen.value)
        guide.offer()
        assertEquals(GuideStep.WELCOME, guide.step)
    }
}
