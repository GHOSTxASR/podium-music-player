@file:OptIn(UnstableApi::class)

package app.podium.player.service

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import app.podium.player.service.alac.AlacAudioRenderer

/**
 * The default renderers, with Podium's own audio steps in the audio path: the equaliser (D-76), the
 * sound effect (D-89), then the bass meter the lyrics move with (D-82), when there is one. After the
 * platform's audio decoders comes Podium's own ALAC decoder (D-87), for phones that have none.
 */
internal class EffectsRenderersFactory(
    context: Context,
    private val equalizer: EqualizerProcessor,
    private val sound: SoundEffectProcessor,
    private val meter: AudioMeter? = null,
) : DefaultRenderersFactory(context) {

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out)
        // Last: a track goes to the first renderer that handles it, so the phone's own decoder wins when it has one.
        out.add(AlacAudioRenderer(eventHandler, eventListener, audioSink))
    }

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .setAudioProcessors(if (meter == null) arrayOf(equalizer, sound) else arrayOf(equalizer, sound, meter))
            .build()
}
