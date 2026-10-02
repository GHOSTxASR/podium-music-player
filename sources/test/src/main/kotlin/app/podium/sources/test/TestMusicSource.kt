package app.podium.sources.test

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import app.podium.core.common.Outcome
import app.podium.core.common.PodiumError
import app.podium.core.model.AlbumId
import app.podium.core.model.AlbumRef
import app.podium.core.model.ArtistCredit
import app.podium.core.model.ArtistId
import app.podium.core.model.ArtworkRef
import app.podium.core.model.AudioQuality
import app.podium.core.model.Availability
import app.podium.core.model.Codec
import app.podium.core.model.PlaybackRoute
import app.podium.core.model.RecordingIdentifiers
import app.podium.core.model.SourceId
import app.podium.core.model.SourceRef
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.sources.api.AlbumDetail
import app.podium.sources.api.AlbumSummary
import app.podium.sources.api.ArtistDetail
import app.podium.sources.api.ArtistSummary
import app.podium.sources.api.ArtworkFacet
import app.podium.sources.api.ArtworkPayload
import app.podium.sources.api.Basis
import app.podium.sources.api.Capability
import app.podium.sources.api.CatalogFacet
import app.podium.sources.api.FacetResolution
import app.podium.sources.api.LibraryFacet
import app.podium.sources.api.MissReason
import app.podium.sources.api.MusicSource
import app.podium.sources.api.PlayableMedia
import app.podium.sources.api.PlaybackFacet
import app.podium.sources.api.PlaybackTarget
import app.podium.sources.api.Purpose
import app.podium.sources.api.QualityRequest
import app.podium.sources.api.SearchQuery
import app.podium.sources.api.SearchResults
import app.podium.sources.api.SourceCapabilities
import app.podium.sources.api.SourceDescriptor
import app.podium.sources.api.matching.TrackNormalizer
import app.podium.sources.test.ToneSynth.A3
import app.podium.sources.test.ToneSynth.A4
import app.podium.sources.test.ToneSynth.B3
import app.podium.sources.test.ToneSynth.C3
import app.podium.sources.test.ToneSynth.C4
import app.podium.sources.test.ToneSynth.C5
import app.podium.sources.test.ToneSynth.Chord
import app.podium.sources.test.ToneSynth.D3
import app.podium.sources.test.ToneSynth.D4
import app.podium.sources.test.ToneSynth.E3
import app.podium.sources.test.ToneSynth.E4
import app.podium.sources.test.ToneSynth.F3
import app.podium.sources.test.ToneSynth.F4
import app.podium.sources.test.ToneSynth.G3
import app.podium.sources.test.ToneSynth.G4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * A deterministic, offline source of generated test tones (debug builds and tests only).
 * Proves the whole pipeline — MusicSource → Track → Queue → StreamResolver → PlayableMedia →
 * Media3 — with no external service. Titles say what they are; nothing here poses as music.
 *
 * Two variants exist so cross-source behaviour is testable: [Variant.PRIMARY] deliberately can't
 * serve "Missing Master", and [Variant.MIRROR] holds an EXACT copy (same ISRC) plus a "Live" decoy.
 */
class TestMusicSource(
    context: Context,
    private val variant: Variant = Variant.PRIMARY,
    /** Scales every duration (tests use short audio). */
    private val durationScale: Double = 1.0,
) : MusicSource {

    enum class Variant(val id: String, val displayName: String) {
        PRIMARY("test", "Test tones"),
        MIRROR("test-mirror", "Test mirror"),
    }

    private val appContext = context.applicationContext
    private val sourceId = SourceId(variant.id)
    private val audioDir = File(appContext.cacheDir, "test-audio/${variant.id}")
    private val artDir = File(appContext.cacheDir, "test-artwork")

    override val descriptor = SourceDescriptor(
        id = sourceId,
        displayName = variant.displayName,
        providerName = "Podium test tones",
        basis = Basis.LOCAL_DEVICE,
        cheapResolve = true,
    )

    override val capabilities: StateFlow<SourceCapabilities> = MutableStateFlow(
        SourceCapabilities.available(
            Capability.SEARCH, Capability.BROWSE, Capability.LIBRARY, Capability.DIRECT_STREAM, Capability.ARTWORK,
        ),
    )

    private data class Spec(
        val key: String,
        val title: String,
        val artist: String,
        val album: String,
        val trackNumber: Int,
        val chords: List<Chord>,
        val isrc: String? = null,
        val behaviour: Behaviour = Behaviour.NORMAL,
    )

    private enum class Behaviour { NORMAL, MISSING, CORRUPT, CLAIMS_HIGH_RES }

    private fun scaled(vararg chords: Chord) = chords.map { it.copy(seconds = it.seconds * durationScale) }

    private val specs: List<Spec> = buildList {
        val lab = "Podium Lab"
        val ensemble = "Podium Ensemble"
        val common = listOf(
            Spec("tones-1", "Tuning Fork", lab, "Test Tones", 1, scaled(Chord(listOf(A4), 14.0))),
            Spec("tones-2", "Major Triad", lab, "Test Tones", 2, scaled(Chord(listOf(C4, E4, G4), 8.0), Chord(listOf(F3, A3, C4), 8.0), Chord(listOf(G3, B3, D4), 8.0))),
            Spec("tones-3", "Minor Turn", lab, "Test Tones", 3, scaled(Chord(listOf(A3, C4, E4), 7.0), Chord(listOf(D3, F3, A3), 7.0), Chord(listOf(E3, G3, B3), 7.0))),
            Spec("tones-4", "Low Hum", lab, "Test Tones", 4, scaled(Chord(listOf(C3, G3), 16.0))),
            Spec("signal-1", "High Resolution Claim", lab, "Signal Path", 1, scaled(Chord(listOf(E4, G4), 12.0)), behaviour = Behaviour.CLAIMS_HIGH_RES),
            Spec("signal-2", "Missing Master", lab, "Signal Path", 2, scaled(Chord(listOf(D4, F4, A4), 12.0)), isrc = "ZZPOD2600001", behaviour = Behaviour.MISSING),
            Spec("signal-3", "Broken Tape", lab, "Signal Path", 3, scaled(Chord(listOf(C4), 10.0)), behaviour = Behaviour.CORRUPT),
            Spec("long-1", "Slow Drift", ensemble, "Long Form", 1, scaled(Chord(listOf(C3, G3, E4), 10.0), Chord(listOf(A3, E4, C5), 10.0), Chord(listOf(F3, C4, A4), 10.0))),
            Spec("long-2", "Night Train", ensemble, "Long Form", 2, scaled(Chord(listOf(D3, A3, F4), 9.0), Chord(listOf(G3, D4, B3), 9.0), Chord(listOf(C3, G3, E4), 9.0))),
            Spec("long-3", "Coda", ensemble, "Long Form", 3, scaled(Chord(listOf(C4, E4, G4, C5), 12.0))),
        )
        when (variant) {
            Variant.PRIMARY -> addAll(common)
            Variant.MIRROR -> {
                // The exact same recording ("Missing Master", same ISRC, same length) — playable here.
                add(common.first { it.key == "signal-2" }.copy(behaviour = Behaviour.NORMAL))
                // A live decoy with the same title: must never be chosen as a fallback.
                add(Spec("signal-2-live", "Missing Master (Live)", lab, "Signal Path (Live)", 1, scaled(Chord(listOf(D4, F4, A4), 13.0))))
            }
        }
    }

    private val tracks: List<Track> = specs.map(::toTrack)
    private val byKey = specs.associateBy { it.key }

    private fun toTrack(s: Spec): Track {
        val albumKey = s.album.lowercase().replace(' ', '-')
        return Track(
            id = TrackId.of(sourceId, s.key),
            source = SourceRef(sourceId, s.key),
            title = s.title,
            artists = listOf(ArtistCredit(s.artist, id = ArtistId.of(sourceId, s.artist.lowercase().replace(' ', '-')))),
            artistDisplay = s.artist,
            album = AlbumRef(s.album, AlbumId.of(sourceId, albumKey), s.artist),
            durationMs = ToneSynth.durationMs(s.chords),
            artwork = ArtworkRef(sourceId, "album/$albumKey"),
            identifiers = RecordingIdentifiers(isrc = s.isrc),
            trackNumber = s.trackNumber,
            discNumber = 1,
            availability = Availability.Playable,
            routes = setOf(PlaybackRoute.DIRECT),
        )
    }

    private fun albums(): List<AlbumSummary> = tracks.groupBy { it.album!!.id!! }.map { (id, ts) ->
        AlbumSummary(id, ts.first().album!!.title, ts.first().artistDisplay, ts.first().artwork, trackCount = ts.size)
    }

    private fun artists(): List<ArtistSummary> = tracks.groupBy { it.artists.first().id!! }.map { (id, ts) ->
        ArtistSummary(id, ts.first().artistDisplay, trackCount = ts.size)
    }

    override val library: LibraryFacet = object : LibraryFacet {
        override fun tracks(): Flow<List<Track>> = flowOf(tracks)
        override fun albums(): Flow<List<AlbumSummary>> = flowOf(this@TestMusicSource.albums())
        override fun artists(): Flow<List<ArtistSummary>> = flowOf(this@TestMusicSource.artists())
    }

    override val catalog: CatalogFacet = object : CatalogFacet {
        override suspend fun search(query: SearchQuery): Outcome<SearchResults> {
            val words = TrackNormalizer.identityKey(query.text).split(' ').filter { it.isNotEmpty() }
            val hits = tracks.filter { t ->
                val hay = TrackNormalizer.identityKey("${t.title} ${t.artistDisplay} ${t.album?.title}").split(' ')
                words.all { it in hay }
            }
            return Outcome.Success(SearchResults(tracks = hits.take(query.limit)))
        }

        override suspend fun track(ref: SourceRef): Outcome<Track> =
            tracks.firstOrNull { it.source.providerKey == ref.providerKey }?.let { Outcome.Success(it) }
                ?: Outcome.Failure(PodiumError.NotFound(ref.providerKey))

        override suspend fun album(id: AlbumId): Outcome<AlbumDetail> {
            val summary = albums().firstOrNull { it.id == id } ?: return Outcome.Failure(PodiumError.NotFound("album"))
            return Outcome.Success(AlbumDetail(summary, tracks.filter { it.album?.id == id }.sortedBy { it.trackNumber }))
        }

        override suspend fun artist(id: ArtistId): Outcome<ArtistDetail> {
            val summary = artists().firstOrNull { it.id == id } ?: return Outcome.Failure(PodiumError.NotFound("artist"))
            val own = tracks.filter { it.artists.first().id == id }
            return Outcome.Success(ArtistDetail(summary, albums().filter { a -> own.any { it.album?.id == a.id } }, own))
        }
    }

    override val playback: PlaybackFacet = object : PlaybackFacet {
        override val routes = setOf(PlaybackRoute.DIRECT)

        override suspend fun resolve(track: Track, quality: QualityRequest, purpose: Purpose): FacetResolution {
            val spec = byKey[track.source.providerKey] ?: return FacetResolution.Miss(MissReason.NOT_FOUND)
            if (spec.behaviour == Behaviour.MISSING) return FacetResolution.Miss(MissReason.NOT_FOUND)
            val file = withContext(Dispatchers.IO) { ensureAudio(spec) }
            val actualFormat = AudioQuality(Codec.PCM, container = "audio/wav", sampleRateHz = ToneSynth.SAMPLE_RATE, bitDepth = ToneSynth.BITS, channels = ToneSynth.CHANNELS)
            // This one deliberately over-claims, so the UI's honesty can be verified end to end.
            val claimed = if (spec.behaviour == Behaviour.CLAIMS_HIGH_RES) {
                AudioQuality(Codec.FLAC, sampleRateHz = 96_000, bitDepth = 24, channels = 2)
            } else actualFormat
            return FacetResolution.Resolved(
                PlaybackTarget.DirectStream(
                    track.id,
                    PlayableMedia(
                        uri = file.toURI().toString(),
                        mimeType = "audio/wav",
                        claimedQuality = claimed,
                        durationMs = track.durationMs,
                        sourceId = sourceId,
                        cacheKey = "${track.id.value}|original",
                    ),
                ),
            )
        }
    }

    override val artwork: ArtworkFacet = object : ArtworkFacet {
        override suspend fun load(ref: ArtworkRef, sizePx: Int): ArtworkPayload? = withContext(Dispatchers.IO) {
            val albumKey = ref.key.removePrefix("album/")
            val file = File(artDir, "$albumKey-$sizePx.png")
            if (!file.exists()) renderArtwork(albumKey, sizePx, file)
            ArtworkPayload.LocalFile(file.absolutePath)
        }
    }

    private fun ensureAudio(spec: Spec): File {
        val file = File(audioDir, "${spec.key}-${(durationScale * 1000).toInt()}.wav")
        if (file.exists() && file.length() > 0) return file
        val tmp = File(audioDir, "${file.name}.part")
        if (spec.behaviour == Behaviour.CORRUPT) ToneSynth.writeCorrupt(tmp) else ToneSynth.writeWav(tmp, spec.chords)
        tmp.renameTo(file)
        return file
    }

    /** Simple geometric covers with distinct palettes (dark, bright/warm, saturated blue). */
    private fun renderArtwork(albumKey: String, size: Int, out: File) {
        val palettes = mapOf(
            "test-tones" to intArrayOf(0xFF101418.toInt(), 0xFF2E3A46.toInt(), 0xFFE8EEF4.toInt()),
            "signal-path" to intArrayOf(0xFFF2B33D.toInt(), 0xFFE0592A.toInt(), 0xFF2B1B12.toInt()),
            "signal-path-(live)" to intArrayOf(0xFF6B2E7E.toInt(), 0xFFD9A3E8.toInt(), 0xFF1A0D1F.toInt()),
            "long-form" to intArrayOf(0xFF0E3B8C.toInt(), 0xFF3C82F0.toInt(), 0xFFBFD8FF.toInt()),
        )
        val p = palettes[albumKey] ?: intArrayOf(0xFF333333.toInt(), 0xFF777777.toInt(), 0xFFDDDDDD.toInt())
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(p[0])
        val cx = size * 0.62f
        val cy = size * 0.42f
        for (i in 6 downTo 1) {
            paint.color = if (i % 2 == 0) p[1] else p[0]
            canvas.drawCircle(cx, cy, size * 0.09f * i, paint)
        }
        paint.color = p[2]
        canvas.drawRect(size * 0.08f, size * 0.80f, size * 0.52f, size * 0.84f, paint)
        canvas.drawCircle(cx, cy, size * 0.035f, paint)
        out.parentFile?.mkdirs()
        FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    /** All tracks, for tests and debug tooling. */
    fun allTracks(): List<Track> = tracks
}
