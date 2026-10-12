package app.podium.player.api

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The sleep timer (D-75): counts down, fades the player out over its last seconds, then pauses it. */
@OptIn(ExperimentalCoroutinesApi::class)
class SleepTimerTest {

    private fun TestScope.timer() = SleepTimer(backgroundScope, { testScheduler.currentTime })

    @Test
    fun `a countdown fades the player's volume over its last ten seconds, then pauses in silence`() = runTest {
        val timer = timer()
        val fired = mutableListOf<Unit>()
        backgroundScope.launch { timer.expired.toList(fired) }
        timer.start(15)
        assertIs<SleepTimerState.Counting>(timer.state.value)
        assertEquals(15 * 60_000L, timer.remainingMs())

        advanceTimeBy(15 * 60_000L - 10_000L - 1)
        assertEquals(1f, timer.volume.value, "full volume until the fade")
        advanceTimeBy(5_001)
        runCurrent()
        assertTrue(timer.volume.value in 0.4f..0.6f, "halfway through the fade: ${timer.volume.value}")
        assertTrue(fired.isEmpty())

        advanceTimeBy(5_100)
        runCurrent()
        assertEquals(listOf(Unit), fired)
        assertEquals(SleepTimerState.Off, timer.state.value)
        assertEquals(0f, timer.volume.value, "the pause lands in silence")

        timer.resumed()
        assertEquals(1f, timer.volume.value, "playing again is at full volume")
    }

    @Test
    fun `cancelling during the fade brings the volume straight back`() = runTest {
        val timer = timer()
        timer.start(15)
        advanceTimeBy(15 * 60_000L - 3_000L)
        runCurrent()
        assertTrue(timer.volume.value < 0.5f)
        timer.cancel()
        assertEquals(1f, timer.volume.value)
        assertEquals(SleepTimerState.Off, timer.state.value)
        advanceTimeBy(60_000)
        assertEquals(1f, timer.volume.value, "nothing fires later")
    }

    @Test
    fun `a new choice replaces the old one`() = runTest {
        val timer = timer()
        timer.start(90)
        timer.start(30)
        assertEquals(30 * 60_000L, timer.remainingMs())
        timer.stopAtEndOfSong()
        assertEquals(SleepTimerState.EndOfSong, timer.state.value)
        assertEquals(null, timer.remainingMs())
    }

    @Test
    fun `the end of a song or album fires when the player reaches it`() = runTest {
        val timer = timer()
        val fired = mutableListOf<Unit>()
        backgroundScope.launch { timer.expired.toList(fired) }
        runCurrent()
        timer.endReached()
        assertTrue(fired.isEmpty(), "nothing to reach while off")
        timer.stopAtEndOfAlbum()
        timer.endReached()
        runCurrent()
        assertEquals(1, fired.size)
        assertEquals(SleepTimerState.Off, timer.state.value)
        assertEquals(1f, timer.volume.value, "no fade at the end of a song: the song itself ends")
    }

    @Test
    fun `resuming while it counts down doesn't undo a fade under way`() = runTest {
        val timer = timer()
        timer.start(15)
        advanceTimeBy(15 * 60_000L - 4_000L)
        runCurrent()
        val fading = timer.volume.value
        timer.resumed()
        assertEquals(fading, timer.volume.value)
    }
}

class SleepTimerTextTest {
    @Test
    fun `the timer is described plainly, rounding minutes up`() {
        assertEquals("15 minutes", SleepTimerText.choice(15))
        assertEquals("1 hour", SleepTimerText.choice(60))
        assertEquals("1 hour 30 minutes", SleepTimerText.choice(90))
        assertEquals("Off", SleepTimerText.summary(SleepTimerState.Off, null))
        assertEquals("23 min left", SleepTimerText.summary(SleepTimerState.Counting(0, 30), 22 * 60_000L + 1))
        assertEquals("Under a minute left", SleepTimerText.summary(SleepTimerState.Counting(0, 30), 59_000))
        assertEquals("Music stops in 1 min", SleepTimerText.note(SleepTimerState.Counting(0, 30), 60_000))
        assertEquals("Music stops after this album", SleepTimerText.note(SleepTimerState.EndOfAlbum, null))
        assertEquals(null, SleepTimerText.note(SleepTimerState.Off, null))
    }
}
