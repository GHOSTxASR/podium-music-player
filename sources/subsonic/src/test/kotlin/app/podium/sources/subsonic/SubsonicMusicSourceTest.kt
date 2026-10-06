package app.podium.sources.subsonic

import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import app.podium.core.model.AlbumId
import app.podium.core.model.ArtistId
import app.podium.core.model.ArtworkRef
import app.podium.core.model.Codec
import app.podium.core.model.Explicitness
import app.podium.core.model.PlaylistId
import app.podium.core.model.SourceId
import app.podium.sources.api.ArtworkPayload
import app.podium.sources.api.AuthState
import app.podium.sources.api.Basis
import app.podium.sources.api.Capability
import app.podium.sources.api.CapabilityStatus
import app.podium.sources.api.FacetResolution
import app.podium.sources.api.HealthOutcome
import app.podium.sources.api.InMemoryCredentialStore
import app.podium.sources.api.MissReason
import app.podium.sources.api.MusicEnvironment
import app.podium.sources.api.PlaybackTarget
import app.podium.sources.api.Purpose
import app.podium.sources.api.QualityRequest
import app.podium.sources.api.SearchQuery
import app.podium.sources.api.SetupProblem
import app.podium.sources.api.SignInResult
import app.podium.sources.api.SourceProfile
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubsonicMusicSourceTest {
    private val server = FakeSubsonicServer()
    private val id = SourceId("opensubsonic-test")
    private val credentials = InMemoryCredentialStore()
    private val profile = SourceProfile(
        id, SubsonicSourceFactory.KIND, "Navidrome (music.local)",
        mapOf(SubsonicMusicSource.KEY_ADDRESS to "https://music.local", SubsonicMusicSource.KEY_USERNAME to server.user),
    )

    private fun signedIn(): SubsonicMusicSource {
        credentials.write(id, mapOf(SubsonicMusicSource.KEY_PASSWORD to server.password))
        return SubsonicMusicSource(profile, credentials, server)
    }

    private suspend fun SubsonicMusicSource.searchSongs(text: String) =
        assertIs<Outcome.Success<app.podium.sources.api.SearchResults>>(catalog.search(SearchQuery(text))).value

    @Test
    fun `it is an online source on the listener's own server, signed in with stored credentials`() {
        val source = signedIn()
        assertEquals(MusicEnvironment.ONLINE, source.descriptor.environment)
        assertEquals(Basis.USER_SERVER, source.descriptor.basis)
        assertEquals("Navidrome (music.local)", source.descriptor.displayName)
        assertEquals(AuthState.SignedIn(server.user), source.auth.state.value)
        val caps = source.capabilities.value
        listOf(Capability.SEARCH, Capability.BROWSE, Capability.DIRECT_STREAM, Capability.ARTWORK, Capability.RECOMMENDATIONS)
            .forEach { assertTrue(caps.isUsable(it), "$it") }
        assertEquals(CapabilityStatus.UNAVAILABLE, caps[Capability.DOWNLOADS].status)
        assertEquals(CapabilityStatus.UNAVAILABLE, caps[Capability.LIKES].status)
    }

    @Test
    fun `without credentials it asks to sign in and offers nothing`() = runTest {
        val source = SubsonicMusicSource(profile, credentials, server)
        assertEquals(AuthState.SignedOut, source.auth.state.value)
        assertEquals(CapabilityStatus.REQUIRES_SIGN_IN, source.capabilities.value[Capability.SEARCH].status)
        assertIs<PodiumError.AuthRequired>(assertIs<Outcome.Failure>(source.catalog.search(SearchQuery("song"))).error)
        assertTrue(server.requests.isEmpty(), "nothing is sent without credentials")
    }

    @Test
    fun `signing in checks with the server and keeps the password only in the credential store`() = runTest {
        val source = SubsonicMusicSource(profile, credentials, server)
        assertEquals(SignInResult.SignedIn, source.auth.signIn(mapOf(SubsonicMusicSource.KEY_PASSWORD to server.password)))
        assertEquals(server.password, credentials.read(id)?.get(SubsonicMusicSource.KEY_PASSWORD))
        assertEquals(AuthState.SignedIn(server.user), source.auth.state.value)
        assertTrue(source.capabilities.value.isUsable(Capability.SEARCH))
    }

    @Test
    fun `a wrong password is refused and nothing is kept`() = runTest {
        val source = SubsonicMusicSource(profile, credentials, server)
        val result = assertIs<SignInResult.Refused>(source.auth.signIn(mapOf(SubsonicMusicSource.KEY_PASSWORD to "nope")))
        assertEquals(SetupProblem.WRONG_CREDENTIALS, result.problem)
        assertNull(credentials.read(id))
        assertEquals(AuthState.SignedOut, source.auth.state.value)
    }

    @Test
    fun `signing out removes the credentials and the source asks to sign in again`() = runTest {
        val source = signedIn()
        source.auth.signOut()
        assertNull(credentials.read(id))
        assertEquals(AuthState.SignedOut, source.auth.state.value)
        assertEquals(CapabilityStatus.REQUIRES_SIGN_IN, source.capabilities.value[Capability.DIRECT_STREAM].status)
    }

    @Test
    fun `a password changed on the server turns the source to rejected`() = runTest {
        val source = signedIn()
        server.password = "changed"
        assertIs<Outcome.Failure>(source.catalog.search(SearchQuery("song")))
        assertIs<AuthState.Rejected>(source.auth.state.value)
        val resolution = assertIs<FacetResolution.Failed>(source.playback.resolve(source.searchSongsFallback(), QualityRequest.Maximum, Purpose.PLAYBACK))
        assertEquals(HealthOutcome.AUTH_FAILURE, resolution.outcome)
    }

    private fun SubsonicMusicSource.searchSongsFallback() =
        SubsonicMapper(id).track(app.podium.sources.subsonic.SubsonicApi.json.decodeFromString(SongDto.serializer(), server.songs[0]))

    @Test
    fun `the password never goes over the wire, and every request has its own salt`() = runTest {
        val source = signedIn()
        source.searchSongs("song")
        source.searchSongs("alpha")
        assertTrue(server.requests.none { q -> q.values.any { it == server.password } })
        assertTrue(server.requests.all { it["t"] != null && it["s"] != null && it["u"] == server.user })
        assertEquals(server.requests.size, server.requests.map { it["s"] }.toSet().size)
    }

    @Test
    fun `search maps songs, albums and artists into source-qualified things`() = runTest {
        val results = signedIn().searchSongs("song")
        assertEquals(listOf("Song Shared", "Song Alpha", "Song Live (Live)"), results.tracks.map { it.title })
        assertTrue(results.tracks.all { it.id.sourceId == id && it.id.value.startsWith("${id.value}|") })
        assertEquals(AlbumId.of(id, "al.al1"), results.albums.single().id)
        assertEquals(ArtistId.of(id, "ar1"), results.artists.single().id)
    }

    @Test
    fun `a song carries the identifiers, edition and quality the server states`() = runTest {
        val shared = signedIn().searchSongs("shared").tracks.single()
        assertEquals("USABC2600001", shared.identifiers.isrc)
        assertEquals("mb-1", shared.identifiers.musicBrainzRecordingId)
        assertEquals(200_000L, shared.durationMs)
        assertEquals("Record", shared.album?.title)
        assertEquals(AlbumId.of(id, "al.al1"), shared.album?.id)
        assertEquals(ArtistId.of(id, "ar1"), shared.artists.single().id)
        assertEquals(ArtworkRef(id, "al-al1"), shared.artwork)
        val claimed = shared.advertisedQualities.single()
        assertEquals(Codec.FLAC, claimed.codec)
        assertEquals(24, claimed.bitDepth)
        assertEquals(96_000, claimed.sampleRateHz)
        val live = signedIn().searchSongs("live").tracks.single()
        assertEquals(Explicitness.EXPLICIT, live.explicitness)
        assertNull(live.identifiers.isrc, "never invented")
    }

    @Test
    fun `an album opens as a collection, a server playlist too`() = runTest {
        val source = signedIn()
        val album = assertIs<Outcome.Success<app.podium.sources.api.PlaylistDetail>>(source.catalog.playlist(PlaylistId.of(id, "al.al1"))).value
        assertTrue(album.summary.isAlbum)
        assertEquals(listOf("Song Shared", "Song Alpha"), album.tracks.map { it.title })
        val detail = assertIs<Outcome.Success<app.podium.sources.api.AlbumDetail>>(source.catalog.album(AlbumId.of(id, "al.al1"))).value
        assertEquals("Record", detail.summary.title)
        val playlist = assertIs<Outcome.Success<app.podium.sources.api.PlaylistDetail>>(source.catalog.playlist(PlaylistId.of(id, "pl.p1"))).value
        assertEquals("Road trip", playlist.summary.title)
        assertIs<Outcome.Failure>(source.catalog.playlist(PlaylistId.of(id, "pl.nope")))
    }

    @Test
    fun `an artist page always has music, even without the server's popular songs`() = runTest {
        val artist = assertIs<Outcome.Success<app.podium.sources.api.ArtistDetail>>(signedIn().catalog.artist(ArtistId.of(id, "ar1"))).value
        assertEquals("Band", artist.summary.name)
        assertEquals(listOf("Record"), artist.albums.map { it.title })
        assertEquals(listOf("Song Shared", "Song Alpha"), artist.tracks.map { it.title }, "the first album stands in")
    }

    @Test
    fun `artwork comes as bytes through the source, never a URL`() = runTest {
        val source = signedIn()
        val art = assertIs<ArtworkPayload.Bytes>(source.artwork.load(ArtworkRef(id, "al-al1"), 300))
        assertEquals("image/png", art.mimeType)
        assertNull(source.artwork.load(ArtworkRef(id, "missing"), 300))
        assertNull(source.artwork.load(ArtworkRef(SourceId("other"), "al-al1"), 300), "never another source's art")
    }

    @Test
    fun `a song plays as a direct stream with fresh authentication`() = runTest {
        val source = signedIn()
        val song = source.searchSongs("shared").tracks.single()
        val target = assertIs<PlaybackTarget.DirectStream>(assertIs<FacetResolution.Resolved>(source.playback.resolve(song, QualityRequest.Maximum, Purpose.PLAYBACK)).target)
        val media = target.media
        assertTrue(media.uri.startsWith("https://music.local/rest/stream?"), "the player fetches the server directly")
        assertTrue("id=s1" in media.uri)
        assertTrue(server.password !in media.uri)
        assertEquals(id, media.sourceId)
        assertEquals(Codec.FLAC, media.claimedQuality?.codec)
        assertEquals("${song.id.value}|original", media.cacheKey)
        val capped = assertIs<PlaybackTarget.DirectStream>(assertIs<FacetResolution.Resolved>(source.playback.resolve(song, QualityRequest.Capped(128), Purpose.PLAYBACK)).target)
        assertTrue("maxBitRate=128" in capped.media.uri)
        assertNull(capped.media.claimedQuality, "a transcode's quality isn't claimed")
    }

    @Test
    fun `a song the server no longer has is a miss, not a failure`() = runTest {
        val source = signedIn()
        val song = source.searchSongs("shared").tracks.single()
        server.missingSongs += "s1"
        assertEquals(MissReason.NOT_FOUND, assertIs<FacetResolution.Miss>(source.playback.resolve(song, QualityRequest.Maximum, Purpose.PLAYBACK)).reason)
    }

    @Test
    fun `a dead server, a garbled answer and a server error are failures`() = runTest {
        val source = signedIn()
        val song = source.searchSongs("shared").tracks.single()
        server.unreachable = true
        assertEquals(HealthOutcome.NETWORK_FAILURE, assertIs<FacetResolution.Failed>(source.playback.resolve(song, QualityRequest.Maximum, Purpose.PLAYBACK)).outcome)
        server.unreachable = false
        server.garbled = true
        assertEquals(HealthOutcome.UNKNOWN, assertIs<FacetResolution.Failed>(source.playback.resolve(song, QualityRequest.Maximum, Purpose.PLAYBACK)).outcome)
        assertIs<PodiumError.Unexpected>(assertIs<Outcome.Failure>(source.catalog.search(SearchQuery("song"))).error)
        server.garbled = false
        server.httpStatus = 503
        assertEquals(HealthOutcome.SERVER_ERROR, assertIs<FacetResolution.Failed>(source.playback.resolve(song, QualityRequest.Maximum, Purpose.PLAYBACK)).outcome)
    }

    @Test
    fun `browsing offers the server's own shelves, genres and playlists`() = runTest {
        val source = signedIn()
        val shelves = assertIs<Outcome.Success<List<app.podium.sources.api.Shelf>>>(source.discovery.shelves()).value
        assertEquals(listOf("Recently added", "Random picks", "Most played", "Server playlists"), shelves.map { it.title })
        assertTrue(shelves.first().playlists.single().isAlbum)
        assertEquals(listOf("Jazz", "Rock"), (source.discovery.genres() as Outcome.Success).value, "most songs first")
        assertEquals(listOf("Song Alpha"), (source.discovery.genre("Rock", 0, 10) as Outcome.Success).value.map { it.title })
    }

    @Test
    fun `recommendations come from the server's similar songs, never the seeds again`() = runTest {
        val source = signedIn()
        val seed = source.searchSongs("shared").tracks.single()
        val related = (source.recommendations.related(listOf(seed), 10) as Outcome.Success).value
        assertEquals(listOf("Song Alpha"), related.map { it.title })
        assertEquals(listOf("Friends"), (source.recommendations.relatedArtists(ArtistId.of(id, "ar1"), 5) as Outcome.Success).value.map { it.name })
        assertTrue((source.recommendations.related(listOf(app.podium.sources.testing.track("Elsewhere", source = "other")), 10) as Outcome.Success).value.isEmpty(), "another source's songs mean nothing here")
    }

    @Test
    fun `no address, user or password ever appears in a failure the app could log`() = runTest {
        val source = signedIn()
        server.unreachable = true
        val error = assertIs<Outcome.Failure>(source.catalog.search(SearchQuery("song"))).error
        val text = error.toString()
        assertTrue(server.password !in text && "music.local" !in text && "t=" !in text, text)
        assertNotNull(text)
    }
}
