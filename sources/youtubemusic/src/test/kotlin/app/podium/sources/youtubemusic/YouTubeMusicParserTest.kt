package app.podium.sources.youtubemusic

import app.podium.core.model.MediaKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class YouTubeMusicParserTest {

    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun `search reads every kind, keeps videos apart from songs and skips profiles`() {
        val page = YouTubeMusicParser.search(json(Fixtures.searchAll))
        val songs = page.items.filterIsInstance<YtmSong>()
        val song = songs.single { it.videoId == "aaaaaaaaaa1" }
        assertEquals("First Light", song.title)
        assertEquals(listOf(YtmRef("Night Owls", "UCnightowls00000000000")), song.artists)
        assertEquals(YtmRef("Dawn", "MPREb_dawn000"), song.album)
        assertEquals(225_000L, song.durationMs)
        assertEquals(MediaKind.SONG, song.kind)
        assertTrue(song.explicit)
        assertEquals(MediaKind.VIDEO, songs.single { it.videoId == "vvvvvvvvvv1" }.kind)
        assertEquals(MediaKind.EPISODE, songs.single { it.videoId == "eeeeeeeeee1" }.kind)
        // The top card is the artist.
        assertEquals("Night Owls", page.items.filterIsInstance<YtmArtist>().single().name)
        val album = page.items.filterIsInstance<YtmAlbum>().single()
        assertEquals(2021, album.year)
        assertEquals("Album", album.type)
        val playlist = page.items.filterIsInstance<YtmPlaylist>().single()
        assertEquals("PLowls0001", playlist.playlistId)
        assertEquals(42, playlist.trackCount)
        assertEquals("Someone", playlist.author)
        // A user's profile is not music to browse.
        assertTrue(page.items.none { it is YtmArtist && it.name == "owlfan" })
        assertEquals("SONGS-PARAMS", page.filters["songs"])
        assertEquals("CP-PARAMS", page.filters["community playlists"])
    }

    @Test
    fun `typed song search reads the shelf and its continuation`() {
        val page = YouTubeMusicParser.search(json(Fixtures.searchSongs))
        assertEquals(listOf("aaaaaaaaaa1", "aaaaaaaaaa2"), page.items.map { (it as YtmSong).videoId })
        assertEquals(YouTubeMusicClient.Continuation("SONGS-TOKEN-1", inBody = false), page.continuation)
        val more = YouTubeMusicParser.searchContinuation(json(Fixtures.searchSongsMore), YouTubeMusicParser.Hint.SONG)
        assertEquals("aaaaaaaaaa3", (more.items.single() as YtmSong).videoId)
        assertNull(more.continuation)
    }

    @Test
    fun `an empty search says so`() {
        val page = YouTubeMusicParser.search(json(Fixtures.searchNothing))
        assertTrue(page.noResults)
        assertTrue(page.items.isEmpty())
    }

    @Test
    fun `home shelves keep their titles and skip shelves that hold nothing`() {
        val page = YouTubeMusicParser.shelves(json(Fixtures.home))
        assertEquals(listOf("Quick picks", "Albums for you"), page.items.map { it.title })
        assertTrue(page.items[0].items.single() is YtmSong)
        assertTrue(page.items[1].items.single() is YtmAlbum)
        assertEquals("HOME-TOKEN", page.continuation?.token)
        val more = YouTubeMusicParser.shelves(json(Fixtures.homeMore))
        assertEquals("Recommended artists", more.items.single().title)
        assertTrue(more.items.single().items.single() is YtmArtist)
    }

    @Test
    fun `an album page reads its header, its songs and their playlist`() {
        val album = assertNotNull(YouTubeMusicParser.collection(json(Fixtures.album), albumPage = true))
        assertEquals("Dawn", album.title)
        assertEquals(2021, album.year)
        assertEquals(listOf(YtmRef("Night Owls", "UCnightowls00000000000")), album.artists)
        assertEquals(2, album.trackCount)
        assertEquals("OLAK5uy_dawn", album.playlistId)
        assertEquals(listOf(1, 2), album.tracks.map { it.trackNumber })
        assertEquals(241_000L, album.tracks[1].durationMs)
        assertFalse(album.tracks[1].playable)
        assertTrue(album.tracks[0].playable)
    }

    @Test
    fun `a playlist page continues with the newer continuation style`() {
        val playlist = assertNotNull(YouTubeMusicParser.collection(json(Fixtures.playlist), albumPage = false))
        assertEquals("Owl songs", playlist.title)
        assertEquals("PLowls0001", playlist.playlistId)
        assertEquals(3, playlist.trackCount)
        assertEquals(2, playlist.tracks.size)
        assertEquals(YouTubeMusicClient.Continuation("PL-TOKEN", inBody = true), playlist.continuation)
        val more = YouTubeMusicParser.songsContinuation(json(Fixtures.playlistMore))
        assertEquals("aaaaaaaaaa3", more.items.single().videoId)
    }

    @Test
    fun `an artist page sorts albums, singles and related artists and finds the radio`() {
        val artist = assertNotNull(YouTubeMusicParser.artist(json(Fixtures.artist)))
        assertEquals("Night Owls", artist.name)
        assertEquals("aaaaaaaaaa1", artist.songs.single().videoId)
        assertEquals("OLAK5uy_allowls", artist.songsPlaylistId)
        assertEquals(listOf("Dawn"), artist.albums.map { it.title })
        assertEquals(listOf("Dusk"), artist.singles.map { it.title })
        assertEquals(listOf("Morning Birds"), artist.related.map { it.name })
        assertEquals("RDEMowls", artist.radioPlaylistId)
        assertEquals("RDAOowls", artist.shufflePlaylistId)
    }

    @Test
    fun `the radio list reads plain and wrapped rows and continues`() {
        val page = YouTubeMusicParser.next(json(Fixtures.radio))
        assertEquals(listOf("aaaaaaaaaa1", "bbbbbbbbbb1"), page.items.map { it.videoId })
        assertEquals(2021, page.items[0].year)
        assertEquals(YtmRef("Dawn", "MPREb_dawn000"), page.items[0].album)
        assertEquals(MediaKind.MUSIC_VIDEO, page.items[1].kind)
        assertEquals(YouTubeMusicClient.Continuation("RADIO-TOKEN", inBody = false), page.continuation)
        val more = YouTubeMusicParser.nextContinuation(json(Fixtures.radioMore))
        assertEquals("Evening Choir", more.items.single().artists.single().name)
    }

    @Test
    fun `library grids skip the create button`() {
        val page = YouTubeMusicParser.library(json(Fixtures.libraryPlaylists))
        assertEquals(listOf("LM", "PLmine0001"), page.items.map { (it as YtmPlaylist).playlistId })
    }

    @Test
    fun `history keeps the service's own grouping`() {
        val history = YouTubeMusicParser.history(json(Fixtures.history))
        assertEquals(listOf("Today", "Yesterday"), history.map { it.period })
    }

    @Test
    fun `the account menu names the signed-in account`() {
        val account = assertNotNull(YouTubeMusicParser.account(json(Fixtures.accountMenu)))
        assertEquals("Listener Name", account.name)
        assertEquals("@listener", account.handle)
    }

    @Test
    fun `unrecognisable answers parse to nothing instead of failing`() {
        assertTrue(YouTubeMusicParser.search(json("{}")).items.isEmpty())
        assertTrue(YouTubeMusicParser.shelves(json("""{"contents":[1,2,"x"]}""")).items.isEmpty())
        assertNull(YouTubeMusicParser.collection(json("""{"contents":{}}"""), albumPage = true))
        assertNull(YouTubeMusicParser.artist(json("{}")))
        assertTrue(YouTubeMusicParser.next(json("""{"contents":"weird"}""")).items.isEmpty())
        assertNull(YouTubeMusicParser.account(json("{}")))
    }

    @Test
    fun `durations parse minutes and hours`() {
        assertEquals(225_000L, YouTubeMusicParser.parseDuration("3:45"))
        assertEquals(3_723_000L, YouTubeMusicParser.parseDuration("1:02:03"))
        assertNull(YouTubeMusicParser.parseDuration("12K views"))
        assertNull(YouTubeMusicParser.parseDuration("3"))
    }

    @Test
    fun `video types map to kinds and never make a video a song`() {
        assertEquals(MediaKind.SONG, YouTubeMusicParser.kindOf("MUSIC_VIDEO_TYPE_ATV", null, YouTubeMusicParser.Hint.NONE))
        assertEquals(MediaKind.MUSIC_VIDEO, YouTubeMusicParser.kindOf("MUSIC_VIDEO_TYPE_OMV", null, YouTubeMusicParser.Hint.SONG))
        assertEquals(MediaKind.VIDEO, YouTubeMusicParser.kindOf("MUSIC_VIDEO_TYPE_UGC", "Song", YouTubeMusicParser.Hint.SONG))
        assertEquals(MediaKind.EPISODE, YouTubeMusicParser.kindOf("MUSIC_VIDEO_TYPE_PODCAST_EPISODE", null, YouTubeMusicParser.Hint.NONE))
        assertEquals(MediaKind.VIDEO, YouTubeMusicParser.kindOf(null, "Video", YouTubeMusicParser.Hint.NONE))
    }

    @Test
    fun `the page configuration gives the client version and visitor id`() {
        assertEquals("1.20261005.01.00" to "CgtWaXNpdG9yMTIz", PageConfigParser.parse(Fixtures.PAGE_HTML))
        assertNull(PageConfigParser.parse("<html>nothing</html>"))
    }
}
