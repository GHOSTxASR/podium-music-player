package app.podium.core.model

/**
 * The provider-independent track (ADR-013, MUSIC_SOURCE_ARCHITECTURE §4).
 *
 * Contains no provider-specific fields: anything an adapter needs later lives in
 * [SourceRef.providerData], which nothing outside that adapter parses. Capabilities and resolved
 * playback targets are not stored here; they are computed per context and per play.
 */
data class Track(
    val id: TrackId,
    val source: SourceRef,
    val title: String,
    val artists: List<ArtistCredit>,
    val artistDisplay: String = artists.joinToString(", ") { it.name },
    val album: AlbumRef? = null,
    val durationMs: Long? = null,
    val artwork: ArtworkRef? = null,
    val identifiers: RecordingIdentifiers = RecordingIdentifiers.None,
    val explicitness: Explicitness = Explicitness.UNKNOWN,
    val releaseDate: PartialDate? = null,
    val discNumber: Int? = null,
    val trackNumber: Int? = null,
    val version: VersionInfo = VersionInfo.Unknown,
    /** What the catalogue says it can offer — never what will actually play. */
    val advertisedQualities: List<AudioQuality> = emptyList(),
    val availability: Availability = Availability.Unknown,
    /** Which playback routes this track's source can use for it (kinds only; resolution is later). */
    val routes: Set<PlaybackRoute> = setOf(PlaybackRoute.DIRECT),
) {
    init {
        require(id.sourceId == source.sourceId) { "Track id $id must belong to source ${source.sourceId}" }
    }

    val primaryArtists: List<ArtistCredit> get() = artists.filter { it.role == ArtistRole.PRIMARY }
}

/** Where a track lives at its provider. Everything but [sourceId] is opaque to Podium's core. */
data class SourceRef(
    val sourceId: SourceId,
    val providerKey: String,
    /** A provider deep link, e.g. for "Open in …". */
    val providerUri: String? = null,
    /** Adapter-private serialized data. Never parsed outside the adapter that wrote it. */
    val providerData: String? = null,
)

enum class ArtistRole { PRIMARY, FEATURED, REMIXER, COMPOSER }

data class ArtistCredit(
    val name: String,
    val role: ArtistRole = ArtistRole.PRIMARY,
    val id: ArtistId? = null,
)

data class AlbumRef(
    val title: String,
    val id: AlbumId? = null,
    val albumArtist: String? = null,
)

/**
 * Artwork addressed through its source's ArtworkFacet. Rendered by a provider-neutral loader that
 * understands [uri] (`podium-art://<sourceId>/<key>`), so UI and notifications never see provider URLs.
 */
data class ArtworkRef(val sourceId: SourceId, val key: String) {
    val uri: String get() = "$SCHEME://${sourceId.value}/${encode(key)}"

    companion object {
        const val SCHEME = "podium-art"

        fun parse(uri: String): ArtworkRef? {
            val prefix = "$SCHEME://"
            if (!uri.startsWith(prefix)) return null
            val rest = uri.removePrefix(prefix)
            val slash = rest.indexOf('/')
            if (slash <= 0) return null
            return ArtworkRef(SourceId(rest.substring(0, slash)), decode(rest.substring(slash + 1)))
        }

        // The charset-name overloads exist on every Android API level (the Charset ones need API 33).
        private fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

        private fun decode(s: String): String = java.net.URLDecoder.decode(s, "UTF-8")
    }
}

/** Recording identifiers — the strongest identity evidence when present. */
data class RecordingIdentifiers(
    val isrc: String? = null,
    val musicBrainzRecordingId: String? = null,
) {
    val normalizedIsrc: String? get() = isrc?.uppercase()?.filter { it.isLetterOrDigit() }?.takeIf { it.length == 12 }

    companion object {
        val None = RecordingIdentifiers()
    }
}

enum class Explicitness {
    EXPLICIT,
    CLEAN,
    NOT_EXPLICIT,
    UNKNOWN;

    /** Explicit vs clean editions are different releases of a song and are never interchangeable. */
    fun conflictsWith(other: Explicitness): Boolean {
        val a = this.edition() ?: return false
        val b = other.edition() ?: return false
        return a != b
    }

    private fun edition(): Explicitness? = when (this) {
        EXPLICIT -> EXPLICIT
        CLEAN -> CLEAN
        NOT_EXPLICIT, UNKNOWN -> null
    }
}

/** Year, year-month, or full date. */
data class PartialDate(val year: Int, val month: Int? = null, val day: Int? = null)

sealed interface Availability {
    data object Playable : Availability
    data object Unknown : Availability
    data class Unavailable(val reason: String) : Availability
    data object RequiresSubscription : Availability
    data object RegionBlocked : Availability
}

/** How a track can be played (kinds only). See PLAYBACK_TARGETS.md. */
enum class PlaybackRoute { DIRECT, REMOTE, EMBEDDED }
