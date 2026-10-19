@file:OptIn(UnstableApi::class)

package app.podium.player.service.alac

import android.content.Context
import android.net.Uri
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.TestUtil
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Podium's ALAC decoder (D-87) against FFmpeg's: ALAC is lossless, so every file must decode to
 * exactly the PCM FFmpeg decodes it to (resources/alac/expected.txt: its MD5 and length). The files
 * cover 16-bit stereo with stretches of silence, mono, 24-bit (with shifted low bytes),
 * uncompressed frames and 5.1.
 */
@RunWith(AndroidJUnit4::class)
class AlacDecoderTest {

    private fun bytes(name: String) = assertNotNull(javaClass.getResourceAsStream("/alac/$name"), name).use { it.readBytes() }

    private val expected: Map<String, Pair<String, Int>> = String(bytes("expected.txt")).lines().filter { it.isNotBlank() }.associate {
        val (name, md5, size) = it.split(" ")
        name to (md5 to size.toInt())
    }

    private fun md5(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }

    private fun decodeAll(name: String): Pair<AlacConfig, ByteArray> {
        val output = TestUtil.extractAllSamplesFromByteArray(Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED), bytes("$name.m4a"))
        val track = output.trackOutputs.valueAt(0)
        val format = assertNotNull(track.lastFormat)
        assertEquals(MimeTypes.AUDIO_ALAC, format.sampleMimeType)
        val config = assertNotNull(AlacConfig.parse(format.initializationData.single()))
        val decoder = AlacFrameDecoder(config)
        val pcm = java.io.ByteArrayOutputStream()
        val out = ByteBuffer.allocate(config.frameLength * config.channels * config.bytesPerSample)
        for (i in 0 until track.sampleCount) {
            val frame = track.getSampleData(i)
            out.clear()
            decoder.decode(frame, frame.size, out)
            pcm.write(out.array(), 0, out.position())
        }
        return config to pcm.toByteArray()
    }

    private fun assertBitExact(name: String, depth: Int, channels: Int) {
        val (config, pcm) = decodeAll(name)
        assertEquals(depth, config.bitDepth)
        assertEquals(channels, config.channels)
        val (md5, size) = expected.getValue(name)
        assertEquals(size, pcm.size, "$name: the same number of samples as FFmpeg")
        assertEquals(md5, md5(pcm), "$name: the same samples as FFmpeg, bit for bit")
    }

    @Test
    fun `16-bit stereo decodes bit for bit, silence included`() = assertBitExact("s16_stereo", 16, 2)

    @Test
    fun `16-bit mono decodes bit for bit`() = assertBitExact("s16_mono", 16, 1)

    @Test
    fun `24-bit stereo decodes bit for bit`() = assertBitExact("s24_stereo", 24, 2)

    @Test
    fun `uncompressed frames decode bit for bit`() = assertBitExact("s16_noise", 16, 2)

    @Test
    fun `5_1 decodes bit for bit, in Android's channel order`() = assertBitExact("s16_5_1", 16, 6)

    @Test
    fun `a cookie behind QuickTime's atom headers is read too, and nonsense isn't`() {
        val bare = ByteBuffer.allocate(24).putInt(4096).put(0).put(16).put(40).put(10).put(14).put(2)
            .putShort(255).putInt(8192).putInt(0).putInt(44_100).array()
        val wrapped = ByteBuffer.allocate(48).putInt(12).put("frma".toByteArray()).put("alac".toByteArray())
            .putInt(36).put("alac".toByteArray()).putInt(0).put(bare).array()
        assertEquals(AlacConfig.parse(bare), AlacConfig.parse(wrapped))
        assertEquals(44_100, AlacConfig.parse(bare)!!.sampleRate)
        assertNull(AlacConfig.parse(ByteArray(10)))
        assertNull(AlacConfig.parse(bare.copyOf().also { it[5] = 12 }), "12-bit isn't ALAC")
    }

    @Test
    fun `the renderer takes ALAC, and only ALAC with a configuration`() {
        val renderer = AlacAudioRenderer(null, null, DefaultAudioSink.Builder(ApplicationProvider.getApplicationContext()).build())
        val cookie = bytes("s16_stereo.m4a").let { TestUtil.extractAllSamplesFromByteArray(Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED), it) }
            .trackOutputs.valueAt(0).lastFormat!!.initializationData
        val alac = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_ALAC).setInitializationData(cookie).setChannelCount(2).setSampleRate(44_100).build()
        assertEquals(C.FORMAT_HANDLED, renderer.supportsFormat(alac) and 0b111)
        assertEquals(C.FORMAT_UNSUPPORTED_SUBTYPE, renderer.supportsFormat(alac.buildUpon().setInitializationData(null).build()) and 0b111)
        assertEquals(C.FORMAT_UNSUPPORTED_TYPE, renderer.supportsFormat(alac.buildUpon().setSampleMimeType(MimeTypes.AUDIO_AAC).build()) and 0b111)
    }

    /** What reached the audio output, byte for byte. */
    private class Capture(sink: AudioSink) : ForwardingAudioSink(sink) {
        val bytes = java.io.ByteArrayOutputStream()
        var format: Format? = null

        override fun configure(config: AudioSink.AudioSinkConfig) {
            format = config.format
            super.configure(config)
        }

        override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
            val from = buffer.position()
            val copy = buffer.duplicate()
            val done = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            val consumed = buffer.position() - from
            if (consumed > 0) {
                val b = ByteArray(consumed)
                copy.get(b)
                bytes.write(b)
            }
            return done
        }
    }

    @Test
    fun `an ALAC file plays to the end through the renderer, every sample reaching the output`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "s16_stereo.m4a").apply { writeBytes(bytes("s16_stereo.m4a")) }
        val capture = Capture(DefaultAudioSink.Builder(context).build())
        // Only Podium's renderer: Robolectric's phone has no ALAC decoder of its own.
        val renderers = RenderersFactory { handler: Handler, _, audioListener, _, _ ->
            arrayOf<Renderer>(AlacAudioRenderer(handler, audioListener, capture))
        }
        val player = TestExoPlayerBuilder(context).setClock(FakeClock(true)).setRenderersFactory(renderers).build()
        try {
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
            player.play()
            TestPlayerRunHelper.run(player).untilState(Player.STATE_ENDED)
            assertNull(player.playerError)
            assertEquals(C.ENCODING_PCM_16BIT, capture.format?.pcmEncoding)
            val (md5, size) = expected.getValue("s16_stereo")
            assertEquals(size, capture.bytes.size())
            assertEquals(md5, md5(capture.bytes.toByteArray()))
        } finally {
            player.release()
            file.delete()
        }
    }
}
