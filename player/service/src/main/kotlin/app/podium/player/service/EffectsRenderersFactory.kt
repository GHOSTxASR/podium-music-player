@file:OptIn(UnstableApi::class)

package app.podium.player.service

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * The default renderers, with Podium's own audio steps in the audio path: the equaliser (D-76), then
 * the bass meter the lyrics move with (D-82), when there is one.
 */
internal class EffectsRenderersFactory(
    context: Context,
    private val equalizer: EqualizerProcessor,
    private val meter: AudioMeter? = null,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .setAudioProcessors(if (meter == null) arrayOf(equalizer) else arrayOf(equalizer, meter))
            .build()
}
