package app.podium.player.service

import android.media.AudioDeviceInfo
import org.junit.Test
import kotlin.test.assertEquals

/** Which output the status bar names for each kind of device (D-52). */
class AudioOutputTest {
    @Test
    fun `devices map to the route the listener hears`() {
        assertEquals(AudioRoute.SPEAKER, AudioOutputMonitor.routeOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertEquals(AudioRoute.BLUETOOTH, AudioOutputMonitor.routeOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertEquals(AudioRoute.BLUETOOTH, AudioOutputMonitor.routeOf(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals(AudioRoute.WIRED, AudioOutputMonitor.routeOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        assertEquals(AudioRoute.WIRED, AudioOutputMonitor.routeOf(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals(AudioRoute.OTHER, AudioOutputMonitor.routeOf(AudioDeviceInfo.TYPE_HDMI))
    }
}
