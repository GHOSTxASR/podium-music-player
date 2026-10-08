package app.podium.player.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the phone's music is heard (D-52). */
enum class AudioRoute { SPEAKER, WIRED, BLUETOOTH, OTHER }

/** The music output as the status bar shows it: where it goes, and whether it's silenced. */
data class AudioOutputState(val route: AudioRoute, val muted: Boolean)

/**
 * The media output, kept current (D-52): the route media plays to — asked of the system on
 * Android 13+, otherwise inferred from what's connected (Bluetooth before wired before the
 * speaker) — and whether media volume is muted or at zero. Updated when outputs come and go and
 * when volume changes; [refresh] covers a route switched in the system's output picker.
 */
class AudioOutputMonitor(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AudioOutputState> = _state.asStateFlow()

    init {
        audio.registerAudioDeviceCallback(
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
            },
            Handler(Looper.getMainLooper()),
        )
        ContextCompat.registerReceiver(
            context.applicationContext,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = refresh()
            },
            IntentFilter().apply {
                addAction(VOLUME_CHANGED_ACTION)
                addAction(STREAM_MUTE_CHANGED_ACTION)
                addAction(AudioManager.ACTION_HEADSET_PLUG)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun refresh() {
        _state.value = read()
    }

    private fun read(): AudioOutputState {
        val muted = audio.isStreamMute(AudioManager.STREAM_MUSIC) || audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
        return AudioOutputState(route(), muted)
    }

    private fun route(): AudioRoute {
        if (Build.VERSION.SDK_INT >= 33) {
            val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            val now = runCatching { audio.getAudioDevicesForAttributes(media) }.getOrNull().orEmpty()
            now.firstOrNull()?.let { return routeOf(it.type) }
        }
        val connected = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { routeOf(it.type) }.toSet()
        return when {
            AudioRoute.BLUETOOTH in connected -> AudioRoute.BLUETOOTH
            AudioRoute.WIRED in connected -> AudioRoute.WIRED
            else -> AudioRoute.SPEAKER
        }
    }

    companion object {
        /** System broadcasts sent on any stream volume or mute change. */
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val STREAM_MUTE_CHANGED_ACTION = "android.media.STREAM_MUTE_CHANGED_ACTION"

        fun routeOf(type: Int): AudioRoute = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRoute.SPEAKER
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST -> AudioRoute.BLUETOOTH
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE -> AudioRoute.WIRED
            else -> AudioRoute.OTHER
        }
    }
}
