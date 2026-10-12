package app.podium.feature.nowplaying

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
    fun `go to album and each artist appear only for the pages the song has`() {
        assertEquals(emptyList(), goToActions(null, {}, {}).map { it.label })
        assertEquals(emptyList(), goToActions(track(null, ArtistCredit("Nobody")), {}, {}).map { it.label })
        val one = track(AlbumId.of(source, "MPREb_1"), ArtistCredit("A", id = ArtistId.of(source, "UC1")))
        assertEquals(listOf("Go to album", "Go to A"), goToActions(one, {}, {}).map { it.label })
    }

    @Test
    fun `several artists are each named in the menu itself, once`() {
        val opened = mutableListOf<String>()
        val a = ArtistCredit("The Weeknd", id = ArtistId.of(source, "UC1"))
        val b = ArtistCredit("Daft Punk", id = ArtistId.of(source, "UC2"))
        val actions = goToActions(track(null, a, b, b, ArtistCredit("Unlinked")), {}, { opened += it.name })
        assertEquals(listOf("Go to The Weeknd", "Go to Daft Punk"), actions.map { it.label })
        actions.last().onSelect()
        assertEquals(listOf("Daft Punk"), opened)
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

/** Which way the cover moves when the song changes (D-78). */
class SongChangeDirectionTest {
    private val q = listOf("a", "b", "c", "d").map { app.podium.core.model.QueueUid(it) }

    @Test
    fun `previous, one back in the same queue, comes in from the left`() {
        kotlin.test.assertTrue(songChangeIsBack(q[2], newIndex = 1, queueOrder = q))
    }

    @Test
    fun `anything else comes in from the right`() {
        kotlin.test.assertFalse(songChangeIsBack(q[1], newIndex = 2, queueOrder = q), "next")
        kotlin.test.assertFalse(songChangeIsBack(q[3], newIndex = 0, queueOrder = q), "repeat wraps round to the start")
        kotlin.test.assertFalse(songChangeIsBack(app.podium.core.model.QueueUid("old"), newIndex = 0, queueOrder = q), "a new queue, from a search or a list")
        kotlin.test.assertFalse(songChangeIsBack(q[1], newIndex = 0, queueOrder = emptyList()), "a queue another app doesn't show")
    }
}
