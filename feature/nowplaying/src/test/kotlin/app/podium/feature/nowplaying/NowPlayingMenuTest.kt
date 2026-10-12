package app.podium.feature.nowplaying

import app.podium.core.designsystem.component.OverlayHost
import app.podium.core.model.AlbumId
import app.podium.core.model.AlbumRef
import app.podium.core.model.ArtistCredit
import app.podium.core.model.ArtistId
import app.podium.core.model.MediaKind
import app.podium.core.model.SourceId
import app.podium.core.model.SourceRef
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.player.api.SleepTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Now Playing's menu entries and notes (D-74, D-75). */
class NowPlayingMenuTest {

    private val source = SourceId("ytmusic")

    private fun track(album: AlbumId? = null, vararg artists: ArtistCredit) = Track(
        id = TrackId.of(source, "v1"),
        source = SourceRef(source, "v1"),
        title = "Song",
        artists = artists.toList(),
        album = album?.let { AlbumRef("Album", it) },
    )

    @Test
    fun `go to album and artist appear only for the pages the song has`() {
        val overlay = OverlayHost()
        assertEquals(emptyList(), goToActions(null, overlay, {}, {}).map { it.label })
        assertEquals(emptyList(), goToActions(track(null, ArtistCredit("Nobody")), overlay, {}, {}).map { it.label })
        val one = track(AlbumId.of(source, "MPREb_1"), ArtistCredit("A", id = ArtistId.of(source, "UC1")))
        assertEquals(listOf("Go to album", "Go to artist"), goToActions(one, overlay, {}, {}).map { it.label })
    }

    @Test
    fun `go to artist goes straight there for one artist, and asks which for several`() {
        val overlay = OverlayHost()
        val opened = mutableListOf<String>()
        val a = ArtistCredit("A", id = ArtistId.of(source, "UC1"))
        val b = ArtistCredit("B", id = ArtistId.of(source, "UC2"))
        goToActions(track(null, a), overlay, {}, { opened += it.name }).single().onSelect()
        assertEquals(listOf("A"), opened)
        goToActions(track(null, a, b, b), overlay, {}, { opened += it.name }).single().onSelect()
        assertEquals(listOf("A", "B"), overlay.menu?.actions?.map { it.label }, "each artist once, to choose from")
    }

    @Test
    fun `now playing says when the sound is a video's`() {
        assertEquals("Audio from the music video", videoNote(MediaKind.MUSIC_VIDEO))
        assertEquals("Audio from a video", videoNote(MediaKind.VIDEO))
        assertNull(videoNote(MediaKind.SONG))
    }

    @Test
    fun `the sleep timer menu offers turning off only when it's on`() {
        val timer = SleepTimer(CoroutineScope(Dispatchers.Unconfined), { 0L })
        val off = sleepTimerMenu(timer)
        assertEquals("Sleep timer", off.title)
        assertEquals(listOf("15 minutes", "30 minutes", "45 minutes", "1 hour", "1 hour 30 minutes", "End of song", "End of album"), off.actions.map { it.label })
        timer.stopAtEndOfSong()
        val on = sleepTimerMenu(timer)
        assertEquals("Sleep timer, after this song", on.title)
        assertEquals("Turn off", on.actions.first().label)
    }
}
