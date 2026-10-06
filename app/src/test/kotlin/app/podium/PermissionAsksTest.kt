package app.podium

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Asking for music access: once by itself, then on request, then the system settings when Android won't ask. */
@RunWith(AndroidJUnit4::class)
class PermissionAsksTest {

    private val music = "android.permission.READ_MEDIA_AUDIO"
    private fun asks() = PermissionAsks(ApplicationProvider.getApplicationContext())

    @Test
    fun `asks by itself once, and never when access is already there`() {
        val asks = asks()
        assertFalse(asks.shouldAskFirstTime(music, granted = true))
        assertTrue(asks.shouldAskFirstTime(music, granted = false))
        asks.onAsked(music)
        assertFalse(asks.shouldAskFirstTime(music, granted = false), "only once")
        assertFalse(asks().shouldAskFirstTime(music, granted = false), "remembered across restarts")
    }

    @Test
    fun `a refusal Android will ask about again keeps asking, a final one goes to the settings`() {
        val asks = asks()
        asks.onAnswer(music, granted = false, canAskAgain = true)
        assertFalse(asks.isBlocked(music, granted = false), "the dialog can still show")
        asks.onAnswer(music, granted = false, canAskAgain = false)
        assertTrue(asks.isBlocked(music, granted = false), "only the system settings can allow it now")
        assertFalse(asks.isBlocked(music, granted = true), "allowed in the system settings: nothing blocked")
        asks.onGranted(music)
        assertFalse(asks().isBlocked(music, granted = false), "a later refusal starts over")
    }
}
