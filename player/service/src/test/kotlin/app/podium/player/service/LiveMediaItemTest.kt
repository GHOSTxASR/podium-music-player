package app.podium.player.service

import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.model.ArtistCredit
import app.podium.core.model.MediaKind
import app.podium.core.model.QueueUid
import app.podium.core.model.SourceId
import app.podium.core.model.SourceRef
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.player.api.QueueItem
import app.podium.player.api.QueueOrigin
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A live station's place in the player (D-90): live HLS, known before anything loads. */
@RunWith(AndroidJUnit4::class)
class LiveMediaItemTest {

    private fun item(kind: MediaKind) = QueueItem(
        uid = QueueUid("u-$kind"),
        track = Track(
            id = TrackId.of(SourceId("ytmusic"), "aaaaaaaaaa1"),
            source = SourceRef(SourceId("ytmusic"), "aaaaaaaaaa1"),
            title = "lofi hip hop radio",
            artists = listOf(ArtistCredit("Big beats")),
            kind = kind,
        ),
        origin = QueueOrigin.CONTEXT,
    )

    @Test
    fun `a station is live HLS, a song isn't`() {
        val live = QueueMediaItems.toMediaItem(item(MediaKind.LIVE))
        assertEquals(MimeTypes.APPLICATION_M3U8, live.localConfiguration?.mimeType)
        assertNotNull(live.liveConfiguration)
        val song = QueueMediaItems.toMediaItem(item(MediaKind.SONG))
        assertNull(song.localConfiguration?.mimeType)
    }

    @Test
    fun `the player builds an HLS source for a station`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(context))
        assertTrue(factory.supportedTypes.contains(androidx.media3.common.C.CONTENT_TYPE_HLS), "HLS is on the classpath")
        val source = factory.createMediaSource(QueueMediaItems.toMediaItem(item(MediaKind.LIVE)))
        assertEquals("HlsMediaSource", source::class.simpleName)
    }
}
