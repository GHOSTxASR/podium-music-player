package app.podium.core.database

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.model.MediaKind
import app.podium.core.model.SourceId
import app.podium.core.model.TrackId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Schema v5 (D-38): the media kind, and the account-scoped online library operations. */
@RunWith(AndroidJUnit4::class)
class SchemaV5Test {

    /** The track row exactly as schema v4 shaped it (same properties, same order). */
    private data class V4Row(
        val id: String, val sourceId: String, val sourceTrackId: String, val title: String, val titleSort: String,
        val artistDisplay: String, val artistId: String, val artistsJson: String, val albumId: String, val sourceAlbumId: String?,
        val albumTitle: String?, val albumArtistDisplay: String?, val trackNo: Int?, val discNo: Int?, val releaseDate: String?,
        val year: Int?, val durationMs: Long?, val isrc: String?, val mbid: String?, val explicitness: String, val versionJson: String?,
        val identityKey: String?, val providerUri: String?, val providerData: String?, val availability: String,
        val artworkSourceId: String?, val artworkKey: String?, val qualitiesJson: String?, val routes: String, val inLibrary: Boolean,
        val removedAt: Long?, val contentHash: Int, val updatedAt: Long,
    )

    private fun TrackEntity.asV4() = V4Row(
        id, sourceId, sourceTrackId, title, titleSort, artistDisplay, artistId, artistsJson, albumId, sourceAlbumId, albumTitle,
        albumArtistDisplay, trackNo, discNo, releaseDate, year, durationMs, isrc, mbid, explicitness, versionJson, identityKey,
        providerUri, providerData, availability, artworkSourceId, artworkKey, qualitiesJson, routes, inLibrary, removedAt, contentHash, updatedAt,
    )

    @Test
    fun `a song's content hash is exactly the one v4 stored, so upgrading rewrites no library row`() {
        listOf(song("Coastline"), song("Signal", album = "Waves", number = 3), song("Quiet", year = null, disc = null)).forEach { s ->
            val entity = TrackMapping.toEntity(s, inLibrary = true, now = 5)
            val v4 = entity.asV4().copy(inLibrary = true, removedAt = null, contentHash = 0, updatedAt = 0).hashCode()
            assertEquals(v4, entity.contentHash, "hash for ${s.title}")
        }
    }

    @Test
    fun `a change of kind is a change of content`() {
        val song = TrackMapping.toEntity(song("Coastline"), inLibrary = false, now = 5)
        val video = TrackMapping.toEntity(song("Coastline").copy(kind = MediaKind.MUSIC_VIDEO), inLibrary = false, now = 5)
        assertNotEquals(song.contentHash, video.contentHash)
        assertEquals(MediaKind.MUSIC_VIDEO, TrackMapping.toTrack(video).kind)
    }

    private val db = PodiumDatabase.createInMemory(ApplicationProvider.getApplicationContext())

    @After
    fun close() = db.close()

    @Test
    fun `an account's likes are replaced as the account reports them, in its order`() = runTest {
        val store = OnlineLibraryStore(db, now = { 1_000L })
        val yt = SourceId("ytmusic")
        store.setLiked(song("Old", source = yt, key = "old00000000"), true, account = "ytm-a")
        store.replaceLikes("ytm-a", listOf(song("First", source = yt, key = "first000000"), song("Second", source = yt, key = "second00000")))
        assertEquals(listOf("First", "Second"), store.likedTracks("ytm-a").first().map { it.title })
        assertEquals(2, store.likeCount("ytm-a"))
    }

    @Test
    fun `forgetting an account leaves other accounts, the device's rows and local favorites alone`() = runTest {
        val store = OnlineLibraryStore(db)
        val yt = SourceId("ytmusic")
        val a = song("Mine", source = yt, key = "mine0000000")
        store.setLiked(a, true, account = "ytm-a")
        store.setLiked(a, true, account = "ytm-b")
        store.setLiked(a, true)
        store.record(a, startedAt = 1, playedMs = 10_000, account = "ytm-a")
        store.record(a, startedAt = 2, playedMs = 10_000, account = "ytm-b")
        store.createPlaylist("A's", yt, listOf(a), account = "ytm-a")
        db.likes().like(listOf(LikedTrackEntity("local|x", 1)))

        store.forgetAccount("ytm-a")

        assertTrue(store.likedIds("ytm-a").first().isEmpty())
        assertTrue(store.listens(account = "ytm-a").isEmpty())
        assertTrue(store.playlists("ytm-a").first().isEmpty())
        assertEquals(setOf(TrackId(a.id.value)), store.likedIds("ytm-b").first())
        assertEquals(1, store.listens(account = "ytm-b").size)
        assertEquals(setOf(a.id), store.likedIds().first())
        assertEquals(listOf("local|x"), db.likes().likedIds().first())
    }
}
