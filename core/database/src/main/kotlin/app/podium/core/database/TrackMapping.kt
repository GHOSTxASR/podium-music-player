package app.podium.core.database

import app.podium.core.model.AlbumId
import app.podium.core.model.AlbumRef
import app.podium.core.model.ArtistCredit
import app.podium.core.model.ArtistId
import app.podium.core.model.ArtistRole
import app.podium.core.model.ArtworkRef
import app.podium.core.model.AudioQuality
import app.podium.core.model.Availability
import app.podium.core.model.Codec
import app.podium.core.model.Explicitness
import app.podium.core.model.LibraryGrouping
import app.podium.core.model.PartialDate
import app.podium.core.model.PlaybackRoute
import app.podium.core.model.RecordingIdentifiers
import app.podium.core.model.SourceId
import app.podium.core.model.SourceRef
import app.podium.core.model.Track
import app.podium.core.model.TrackId
import app.podium.core.model.VariantNote
import app.podium.core.model.VersionInfo
import app.podium.core.model.VersionTag
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * Track ↔ row. Every field of [Track] survives the round trip (tested), so a song read back from
 * the database is the same song the source gave us — playback and matching can't tell the
 * difference.
 */
internal object TrackMapping {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun toEntity(track: Track, inLibrary: Boolean, now: Long): TrackEntity {
        val base = TrackEntity(
            id = track.id.value,
            sourceId = track.source.sourceId.value,
            sourceTrackId = track.source.providerKey,
            title = track.title,
            titleSort = LibraryGrouping.sortKey(track.title),
            artistDisplay = track.artistDisplay,
            artistId = LibraryGrouping.artistIdOf(track).value,
            artistsJson = json.encodeToString(ListSerializer(CreditDto.serializer()), track.artists.map(CreditDto::of)),
            albumId = LibraryGrouping.albumIdOf(track).value,
            sourceAlbumId = track.album?.id?.value,
            albumTitle = track.album?.title,
            albumArtistDisplay = track.album?.albumArtist,
            trackNo = track.trackNumber,
            discNo = track.discNumber,
            releaseDate = track.releaseDate?.let(::formatDate),
            year = track.releaseDate?.year,
            durationMs = track.durationMs,
            isrc = track.identifiers.isrc,
            mbid = track.identifiers.musicBrainzRecordingId,
            explicitness = track.explicitness.name,
            versionJson = track.version.takeIf { it != VersionInfo.Unknown }?.let { json.encodeToString(VersionDto.serializer(), VersionDto.of(it)) },
            identityKey = track.version.identityKey.takeIf { it.isNotEmpty() },
            providerUri = track.source.providerUri,
            providerData = track.source.providerData,
            availability = encodeAvailability(track.availability),
            artworkSourceId = track.artwork?.sourceId?.value,
            artworkKey = track.artwork?.key,
            qualitiesJson = track.advertisedQualities.takeIf { it.isNotEmpty() }
                ?.let { q -> json.encodeToString(ListSerializer(QualityDto.serializer()), q.map(QualityDto::of)) },
            routes = track.routes.map { it.name }.sorted().joinToString(","),
            inLibrary = inLibrary,
            removedAt = null,
            contentHash = 0,
            updatedAt = now,
        )
        return base.copy(contentHash = contentHash(base))
    }

    /** Everything that describes the song (not bookkeeping), so an unchanged song isn't rewritten. */
    fun contentHash(e: TrackEntity): Int = e.copy(inLibrary = true, removedAt = null, contentHash = 0, updatedAt = 0).hashCode()

    fun toTrack(e: TrackEntity): Track {
        val source = SourceId(e.sourceId)
        return Track(
            id = TrackId(e.id),
            source = SourceRef(source, e.sourceTrackId, e.providerUri, e.providerData),
            title = e.title,
            artists = json.decodeFromString(ListSerializer(CreditDto.serializer()), e.artistsJson).map(CreditDto::toCredit),
            artistDisplay = e.artistDisplay,
            album = if (e.albumTitle != null || e.sourceAlbumId != null || e.albumArtistDisplay != null) {
                AlbumRef(e.albumTitle.orEmpty(), e.sourceAlbumId?.let(::AlbumId), e.albumArtistDisplay)
            } else null,
            durationMs = e.durationMs,
            artwork = if (e.artworkSourceId != null && e.artworkKey != null) ArtworkRef(SourceId(e.artworkSourceId), e.artworkKey) else null,
            identifiers = if (e.isrc == null && e.mbid == null) RecordingIdentifiers.None else RecordingIdentifiers(e.isrc, e.mbid),
            explicitness = runCatching { Explicitness.valueOf(e.explicitness) }.getOrDefault(Explicitness.UNKNOWN),
            releaseDate = e.releaseDate?.let(::parseDate),
            discNumber = e.discNo,
            trackNumber = e.trackNo,
            version = e.versionJson?.let { json.decodeFromString(VersionDto.serializer(), it).toVersion() } ?: VersionInfo.Unknown,
            advertisedQualities = e.qualitiesJson?.let { json.decodeFromString(ListSerializer(QualityDto.serializer()), it).map(QualityDto::toQuality) }.orEmpty(),
            availability = decodeAvailability(e.availability),
            routes = e.routes.split(',').filter { it.isNotEmpty() }.mapNotNull { runCatching { PlaybackRoute.valueOf(it) }.getOrNull() }.toSet(),
        )
    }

    private fun formatDate(d: PartialDate): String = buildString {
        append(String.format(Locale.ROOT, "%04d", d.year))
        d.month?.let { append(String.format(Locale.ROOT, "-%02d", it)) }
        if (d.month != null) d.day?.let { append(String.format(Locale.ROOT, "-%02d", it)) }
    }

    private fun parseDate(s: String): PartialDate? {
        val parts = s.split('-').map { it.toIntOrNull() ?: return null }
        return PartialDate(parts[0], parts.getOrNull(1), parts.getOrNull(2))
    }

    fun encodeAvailability(a: Availability): String = when (a) {
        Availability.Playable -> "PLAYABLE"
        Availability.Unknown -> "UNKNOWN"
        Availability.RequiresSubscription -> "REQUIRES_SUBSCRIPTION"
        Availability.RegionBlocked -> "REGION_BLOCKED"
        is Availability.Unavailable -> "UNAVAILABLE:" + a.reason
    }

    fun decodeAvailability(s: String): Availability = when {
        s == "PLAYABLE" -> Availability.Playable
        s == "REQUIRES_SUBSCRIPTION" -> Availability.RequiresSubscription
        s == "REGION_BLOCKED" -> Availability.RegionBlocked
        s.startsWith("UNAVAILABLE:") -> Availability.Unavailable(s.removePrefix("UNAVAILABLE:"))
        else -> Availability.Unknown
    }
}

@Serializable
private data class CreditDto(val name: String, val role: String = "PRIMARY", val id: String? = null) {
    fun toCredit() = ArtistCredit(name, runCatching { ArtistRole.valueOf(role) }.getOrDefault(ArtistRole.PRIMARY), id?.let(::ArtistId))

    companion object {
        fun of(c: ArtistCredit) = CreditDto(c.name, c.role.name, c.id?.value)
    }
}

@Serializable
private data class VersionDto(
    val identityKey: String,
    val tags: List<String> = emptyList(),
    val variants: List<String> = emptyList(),
    val versionDetail: String = "",
    val packaging: List<String> = emptyList(),
) {
    fun toVersion() = VersionInfo(
        identityKey = identityKey,
        tags = tags.mapNotNull { runCatching { VersionTag.valueOf(it) }.getOrNull() }.toSet(),
        variants = variants.mapNotNull { runCatching { VariantNote.valueOf(it) }.getOrNull() }.toSet(),
        versionDetail = versionDetail,
        packaging = packaging,
    )

    companion object {
        fun of(v: VersionInfo) = VersionDto(v.identityKey, v.tags.map { it.name }.sorted(), v.variants.map { it.name }.sorted(), v.versionDetail, v.packaging)
    }
}

@Serializable
private data class QualityDto(
    val codec: String? = null,
    val container: String? = null,
    val bitrateKbps: Int? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val channels: Int? = null,
) {
    fun toQuality() = AudioQuality(
        codec?.let { runCatching { Codec.valueOf(it) }.getOrNull() },
        container, bitrateKbps, sampleRateHz, bitDepth, channels,
    )

    companion object {
        fun of(q: AudioQuality) = QualityDto(q.codec?.name, q.container, q.bitrateKbps, q.sampleRateHz, q.bitDepth, q.channels)
    }
}
