package app.podium.sources.addon

import app.podium.core.model.AudioQuality
import app.podium.core.model.Codec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StreamFactsTest {
    private fun stream(url: String, format: String? = null, quality: String? = null, mime: String? = null) =
        AddonStream(url, format, quality, mime, encrypted = false, headers = emptyMap(), expiresAtMillis = null)

    @Test
    fun `the player is told about manifests before it opens them`() {
        assertEquals(StreamFacts.MIME_DASH, StreamFacts.mimeType(stream("https://x/a", format = "dash")))
        assertEquals(StreamFacts.MIME_DASH, StreamFacts.mimeType(stream("https://x/a.mpd?token=1")))
        assertEquals(StreamFacts.MIME_HLS, StreamFacts.mimeType(stream("https://x/a", format = "hls")))
        assertEquals(StreamFacts.MIME_HLS, StreamFacts.mimeType(stream("https://x/master.m3u8")))
        assertEquals(StreamFacts.MIME_DASH, StreamFacts.mimeType(stream("https://x/a", mime = "application/dash+xml")))
    }

    @Test
    fun `progressive files by name, or left for the player to sniff`() {
        assertEquals("audio/flac", StreamFacts.mimeType(stream("https://x/a.flac")))
        assertEquals("audio/mpeg", StreamFacts.mimeType(stream("https://x/a", format = "mp3")))
        assertEquals("audio/mp4", StreamFacts.mimeType(stream("https://x/a.m4a")))
        assertEquals("audio/flac", StreamFacts.mimeType(stream("https://x/a", mime = "audio/flac")))
        assertNull(StreamFacts.mimeType(stream("https://x/play?id=1")))
    }

    @Test
    fun `the claimed quality keeps only what the label states`() {
        assertEquals(
            AudioQuality(sampleRateHz = 44_100, bitDepth = 24),
            StreamFacts.claimedQuality(stream("u", quality = "Hi-Res Lossless · 24-bit / 44.1 kHz")),
        )
        assertEquals(
            AudioQuality(codec = Codec.FLAC, sampleRateHz = 96_000, bitDepth = 24),
            StreamFacts.claimedQuality(stream("u", quality = "FLAC 24 bit 96kHz")),
        )
        assertEquals(AudioQuality(codec = Codec.MP3, bitrateKbps = 320), StreamFacts.claimedQuality(stream("u", quality = "320 kbps MP3")))
        assertNull(StreamFacts.claimedQuality(stream("u", quality = "Lossless")), "\"lossless\" names no codec or format")
        assertNull(StreamFacts.claimedQuality(stream("u")))
    }
}
