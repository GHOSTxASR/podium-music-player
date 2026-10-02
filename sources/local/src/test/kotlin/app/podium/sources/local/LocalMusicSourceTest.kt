package app.podium.sources.local

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.model.Codec
import app.podium.sources.api.Capability
import app.podium.sources.api.CapabilityAction
import app.podium.sources.api.CapabilityStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class LocalTrackMapperTest {
    private fun row(
        title: String? = "Song",
        artist: String? = "Artist",
        album: String? = "Album",
        track: Int? = null,
        cdTrack: String? = null,
        disc: String? = null,
        mime: String? = "audio/flac",
        bitrate: Int? = null,
    ) = MediaStoreRow(
        id = 42, title = title, artist = artist, album = album, albumId = 7, artistId = 3, albumArtist = null,
        durationMs = 200_000, trackColumn = track, cdTrackNumber = cdTrack, discNumber = disc, year = 2024,
        mimeType = mime, bitrate = bitrate, sizeBytes = 1,
    )

    @Test
    fun `rows become source-qualified tracks`() {
        val t = LocalTrackMapper.toTrack(row())
        assertEquals("local|42", t.id.value)
        assertEquals("Album", t.album?.title)
        assertEquals("podium-art://local/album%2F7%2F42", t.artwork?.uri)
    }

    @Test
    fun `unknown placeholders become friendly values`() {
        val t = LocalTrackMapper.toTrack(row(artist = "<unknown>", album = "<unknown>", title = null))
        assertEquals("Unknown artist", t.artistDisplay)
        assertNull(t.album)
        assertEquals("Untitled", t.title)
    }

    @Test
    fun `track and disc numbers from either column`() {
        assertEquals(3, LocalTrackMapper.trackNumber(row(track = 2003)))
        assertEquals(2, LocalTrackMapper.discNumber(row(track = 2003)))
        assertEquals(5, LocalTrackMapper.trackNumber(row(cdTrack = "5/12")))
        assertEquals(1, LocalTrackMapper.discNumber(row(disc = "1/2")))
    }

    @Test
    fun `mime types are claims, and ambiguous containers claim no codec`() {
        assertEquals(Codec.FLAC, LocalTrackMapper.claimedQuality("audio/flac", null)?.codec)
        assertEquals(320, LocalTrackMapper.claimedQuality("audio/mpeg", 320_000)?.bitrateKbps)
        assertNull(LocalTrackMapper.claimedQuality("audio/mp4", null), "MP4 may hold AAC or ALAC")
        assertNull(LocalTrackMapper.claimedQuality("audio/ogg", null)?.codec)
    }

    @Test
    fun `artwork keys round-trip`() {
        assertEquals(7L to 42L, LocalTrackMapper.parseArtworkKey(LocalTrackMapper.artworkKey(7, 42)))
        assertNull(LocalTrackMapper.parseArtworkKey("nonsense"))
    }
}

@RunWith(AndroidJUnit4::class)
class LocalMusicSourcePermissionTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `without permission every capability asks for it, generically`() {
        shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_AUDIO)
        val source = LocalMusicSource(app, CoroutineScope(Dispatchers.Unconfined))
        val state = source.capabilities.value[Capability.LIBRARY]
        assertEquals(CapabilityStatus.REQUIRES_PERMISSION, state.status)
        val action = assertIs<CapabilityAction.RequestPermission>(state.action)
        assertEquals(Manifest.permission.READ_MEDIA_AUDIO, action.permission)
    }

    @Test
    fun `granting the permission makes the source available`() {
        shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_AUDIO)
        val source = LocalMusicSource(app, CoroutineScope(Dispatchers.Unconfined))
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_AUDIO)
        source.onPermissionChanged()
        assertEquals(CapabilityStatus.AVAILABLE, source.capabilities.value[Capability.DIRECT_STREAM].status)
    }
}
