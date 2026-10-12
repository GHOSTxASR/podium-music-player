@file:OptIn(UnstableApi::class)

package app.podium.player.service

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/** The default renderers, with Podium's own audio effects (the equaliser, D-76) in the audio path. */
internal class EffectsRenderersFactory(
    context: Context,
    private val equalizer: EqualizerProcessor,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .setAudioProcessors(arrayOf(equalizer))
            .build()
}
