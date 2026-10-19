@file:OptIn(UnstableApi::class)

package app.podium.player.service.alac

import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DecoderAudioRenderer

/**
 * Apple Lossless for every phone (D-87). Many phones ship no ALAC decoder (Android's own software
 * codecs have none), so their MP4 ALAC files wouldn't play. This renderer decodes ALAC in Podium
 * itself, bit for bit, and is added after the platform's: a phone that has its own decoder keeps
 * using it, exactly as before; one that has none uses this.
 */
internal class AlacAudioRenderer(
    eventHandler: Handler?,
    eventListener: AudioRendererEventListener?,
    sink: AudioSink,
) : DecoderAudioRenderer<AlacDecoder>(eventHandler, eventListener, sink) {

    override fun getName(): String = "AlacAudioRenderer"

    override fun supportsFormatInternal(format: Format): Int {
        if (format.sampleMimeType != MimeTypes.AUDIO_ALAC) return C.FORMAT_UNSUPPORTED_TYPE
        val config = format.initializationData.firstOrNull()?.let(AlacConfig::parse) ?: return C.FORMAT_UNSUPPORTED_SUBTYPE
        if (format.cryptoType != C.CRYPTO_TYPE_NONE) return C.FORMAT_UNSUPPORTED_DRM
        if (!sinkSupportsFormat(pcmFormat(config, format))) return C.FORMAT_UNSUPPORTED_SUBTYPE
        return C.FORMAT_HANDLED
    }

    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?): AlacDecoder = AlacDecoder(format)

    override fun getOutputFormat(decoder: AlacDecoder): Format = pcmFormat(decoder.config, decoder.format)

    companion object {
        /** The PCM the decoder writes: 16, 24 (20-bit samples too, left-justified) or 32 bits. */
        fun pcmFormat(config: AlacConfig, format: Format): Format = Util.getPcmFormat(
            when (config.bitDepth) {
                16 -> C.ENCODING_PCM_16BIT
                20, 24 -> C.ENCODING_PCM_24BIT
                else -> C.ENCODING_PCM_32BIT
            },
            config.channels,
            if (config.sampleRate > 0) config.sampleRate else format.sampleRate,
        )
    }
}

internal class AlacDecoderException(message: String, cause: Throwable? = null) : DecoderException(message, cause)

/** One stream's ALAC frames, decoded on Media3's decoder thread. */
@Suppress("UNCHECKED_CAST")
internal class AlacDecoder(val format: Format) : SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, AlacDecoderException>(
    arrayOfNulls<DecoderInputBuffer>(BUFFERS) as Array<DecoderInputBuffer>,
    arrayOfNulls<SimpleDecoderOutputBuffer>(BUFFERS) as Array<SimpleDecoderOutputBuffer>,
) {
    val config: AlacConfig = format.initializationData.firstOrNull()?.let(AlacConfig::parse)
        ?: throw AlacDecoderException("No ALAC configuration")

    private val frames = AlacFrameDecoder(config)
    private var frame = ByteArray(if (config.maxFrameBytes > 0) config.maxFrameBytes else DEFAULT_FRAME_BYTES)
    private val outputSize = config.frameLength * config.channels * config.bytesPerSample

    init {
        setInitialInputBufferSize(frame.size)
    }

    override fun getName(): String = "podium-alac"

    override fun createInputBuffer() = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)

    override fun createOutputBuffer() = SimpleDecoderOutputBuffer(::releaseOutputBuffer)

    override fun createUnexpectedDecodeException(error: Throwable) = AlacDecoderException("Unexpected decode error", error)

    override fun decode(inputBuffer: DecoderInputBuffer, outputBuffer: SimpleDecoderOutputBuffer, reset: Boolean): AlacDecoderException? {
        val data = inputBuffer.data ?: return AlacDecoderException("No data")
        val size = data.remaining()
        if (frame.size < size) frame = ByteArray(size)
        data.get(frame, 0, size)
        val out = outputBuffer.init(inputBuffer.timeUs, outputSize)
        return try {
            frames.decode(frame, size, out)
            out.limit(out.position())
            out.position(0)
            null
        } catch (e: AlacException) {
            AlacDecoderException(e.message ?: "Malformed ALAC frame", e)
        } catch (e: IndexOutOfBoundsException) {
            AlacDecoderException("Malformed ALAC frame", e)
        }
    }

    private companion object {
        const val BUFFERS = 16
        const val DEFAULT_FRAME_BYTES = 16 * 1024
    }
}
