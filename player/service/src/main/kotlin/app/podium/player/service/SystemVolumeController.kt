package app.podium.player.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat
import app.podium.player.api.VolumeController
import app.podium.player.api.VolumeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The wheel's volume on Now Playing (D-16): system media volume, adjusted without showing the
 * system panel (Podium draws its own bar). Fixed-volume outputs report isFixed and are left alone.
 */
class SystemVolumeController(context: Context) : VolumeController {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val _state = MutableStateFlow(read())
    override val state: StateFlow<VolumeState> = _state.asStateFlow()

    init {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                _state.value = read()
            }
        }
        ContextCompat.registerReceiver(
            context.applicationContext,
            receiver,
            IntentFilter(VOLUME_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun step(delta: Int) {
        if (audio.isVolumeFixed || delta == 0) return
        val direction = if (delta > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        repeat(kotlin.math.abs(delta)) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0) }
        _state.value = read()
    }

    private fun read() = VolumeState(
        level = audio.getStreamVolume(AudioManager.STREAM_MUSIC),
        max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
        isFixed = audio.isVolumeFixed,
    )

    private companion object {
        /** System broadcast sent on any stream volume change. */
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
    }
}
