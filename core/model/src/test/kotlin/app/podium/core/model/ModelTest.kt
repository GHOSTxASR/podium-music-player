package app.podium.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelTest {

    @Test
    fun `track ids are source-qualified`() {
        val id = TrackId.of(SourceId("local"), "123")
        assertEquals(SourceId("local"), id.sourceId)
        assertEquals("123", id.providerKey)
        assertFailsWith<IllegalArgumentException> { TrackId("no-source") }
    }

    @Test
    fun `the same provider key on two sources never collides`() {
        val a = SourceId("audius")
        val b = SourceId("other")
        assertTrue(TrackId.of(a, "123") != TrackId.of(b, "123"))
        assertTrue(ArtistId.of(a, "123") != ArtistId.of(b, "123"))
        assertTrue(AlbumId.of(a, "123") != AlbumId.of(b, "123"))
        assertTrue(PlaylistId.of(a, "123") != PlaylistId.of(b, "123"))
        assertEquals(b, PlaylistId.of(b, "123").sourceId)
        assertEquals("123", PlaylistId.of(b, "123").providerKey)
        val shelf = ScopedKey.of(b, "trending")
        assertEquals(b, shelf.sourceId)
        assertEquals("trending", shelf.key)
        assertFailsWith<IllegalArgumentException> { ScopedKey("trending") }
    }

    @Test
    fun `a track must belong to its source`() {
        assertFailsWith<IllegalArgumentException> {
            Track(
                id = TrackId.of(SourceId("a"), "1"),
                source = SourceRef(SourceId("b"), "1"),
                title = "x",
                artists = emptyList(),
            )
        }
    }

    @Test
    fun `artwork refs round-trip through their uri, including unicode keys`() {
        val ref = ArtworkRef(SourceId("local"), "album/42 – Café ☕")
        assertEquals(ref, ArtworkRef.parse(ref.uri))
        assertNull(ArtworkRef.parse("https://example.com/a.jpg"))
    }

    @Test
    fun `isrc normalisation`() {
        assertEquals("USUM71700001", RecordingIdentifiers(isrc = "us-um7-17-00001").normalizedIsrc)
        assertNull(RecordingIdentifiers(isrc = "too-short").normalizedIsrc)
    }

    @Test
    fun `explicit and clean conflict, unknown never does`() {
        assertTrue(Explicitness.EXPLICIT.conflictsWith(Explicitness.CLEAN))
        assertFalse(Explicitness.EXPLICIT.conflictsWith(Explicitness.UNKNOWN))
        assertFalse(Explicitness.NOT_EXPLICIT.conflictsWith(Explicitness.CLEAN))
    }
}

class QualityTest {

    @Test
    fun `labels use natural case and real numbers`() {
        assertEquals("FLAC 24-bit/96 kHz", QualityLabel.format(AudioQuality(Codec.FLAC, bitDepth = 24, sampleRateHz = 96_000)))
        assertEquals("ALAC 16-bit/44.1 kHz", QualityLabel.format(AudioQuality(Codec.ALAC, bitDepth = 16, sampleRateHz = 44_100)))
        assertEquals("AAC 256 kbps", QualityLabel.format(AudioQuality(Codec.AAC, bitrateKbps = 256)))
        assertEquals("MP3", QualityLabel.format(AudioQuality(Codec.MP3)))
        assertNull(QualityLabel.format(AudioQuality()))
    }

    @Test
    fun `the label is based on the actual media, never the claim`() {
        val report = QualityReport(
            sourceClaimed = AudioQuality(Codec.FLAC, bitDepth = 24, sampleRateHz = 96_000),
            actualMedia = AudioQuality(Codec.AAC, bitrateKbps = 256),
        )
        assertEquals("AAC 256 kbps", QualityLabel.forNowPlaying(report))
        assertTrue(report.codecMismatch)
        assertFalse(report.isVerifiedLossless)
        assertEquals(QualityProvenance.ACTUAL_MEDIA_QUALITY, report.provenance)
    }

    @Test
    fun `a claim alone produces no label`() {
        val claimOnly = QualityReport(sourceClaimed = AudioQuality(Codec.FLAC, bitDepth = 24, sampleRateHz = 96_000))
        assertNull(QualityLabel.forNowPlaying(claimOnly))
        assertEquals(QualityProvenance.SOURCE_CLAIMED_QUALITY, claimOnly.provenance)
        assertEquals(QualityProvenance.UNKNOWN, QualityReport().provenance)
    }

    @Test
    fun `sample rates print the way people say them`() {
        assertEquals("44.1", QualityLabel.kiloHertz(44_100))
        assertEquals("48", QualityLabel.kiloHertz(48_000))
        assertEquals("176.4", QualityLabel.kiloHertz(176_400))
    }
}
