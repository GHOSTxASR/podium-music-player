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
 * sound effect (D-89), then the bass meter the lyrics move with (D-82), when there is one. Podium's
 * own ALAC decoder (D-87) comes before the platform's audio decoders, for every phone.
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
        // First, ahead of the phone's decoders: a track goes to the first renderer that handles it.
        // Some phones' own ALAC decoders claim the format and then fail on the file's configuration
        // (issue #6); Podium's is bit-exact and handles nothing but ALAC, so every other format still
        // goes to the phone's decoders exactly as before.
        out.add(AlacAudioRenderer(eventHandler, eventListener, audioSink))
        super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out)
    }

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .setAudioProcessors(if (meter == null) arrayOf(equalizer, sound) else arrayOf(equalizer, sound, meter))
            .build()
}
