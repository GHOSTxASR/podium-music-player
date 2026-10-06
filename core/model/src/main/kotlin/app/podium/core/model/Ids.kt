package app.podium.core.model

/**
 * A connected source instance, e.g. `local`, `subsonic:9f2c`, `test:primary`.
 * Stable for the lifetime of the connection; never parsed by UI code.
 */
@JvmInline
value class SourceId(val value: String) {
    init {
        require(value.isNotBlank()) { "SourceId must not be blank" }
        require(':' !in value || value.indexOf(':') > 0) { "SourceId must not start with ':'" }
    }

    override fun toString(): String = value
}

/**
 * Podium's stable track id: `<sourceId>|<provider track key>`.
 *
 * The provider key is opaque — only the owning source's adapter interprets it. Identity is always
 * source-qualified; two copies of the same recording on two sources are two TrackIds linked by an
 * equivalence decision, never one id (ADR-002, ADR-013).
 */
@JvmInline
value class TrackId(val value: String) {
    init {
        require(SEPARATOR in value) { "TrackId must be source-qualified: $value" }
    }

    val sourceId: SourceId get() = SourceId(value.substringBefore(SEPARATOR))
    val providerKey: String get() = value.substringAfter(SEPARATOR)

    override fun toString(): String = value

    companion object {
        const val SEPARATOR = '|'
        fun of(source: SourceId, providerKey: String): TrackId {
            require(providerKey.isNotEmpty()) { "providerKey must not be empty" }
            return TrackId("${source.value}$SEPARATOR$providerKey")
        }
    }
}

/** An album within one source. */
@JvmInline
value class AlbumId(val value: String) {
    override fun toString(): String = value

    val sourceId: SourceId get() = SourceId(value.substringBefore(TrackId.SEPARATOR))

    companion object {
        fun of(source: SourceId, providerKey: String) = AlbumId("${source.value}${TrackId.SEPARATOR}$providerKey")
    }
}

/** An artist within one source. */
@JvmInline
value class ArtistId(val value: String) {
    override fun toString(): String = value

    val sourceId: SourceId get() = SourceId(value.substringBefore(TrackId.SEPARATOR))

    companion object {
        fun of(source: SourceId, providerKey: String) = ArtistId("${source.value}${TrackId.SEPARATOR}$providerKey")
    }
}

/** A playlist (or a source's album presented as one) within one source. */
@JvmInline
value class PlaylistId(val value: String) {
    override fun toString(): String = value

    companion object {
        fun of(source: SourceId, providerKey: String) = PlaylistId("${source.value}${TrackId.SEPARATOR}$providerKey")
    }

    /** The source-local key (everything after the source id). */
    val providerKey: String get() = value.substringAfter(TrackId.SEPARATOR)

    val sourceId: SourceId get() = SourceId(value.substringBefore(TrackId.SEPARATOR))
}

/**
 * A source-local key made unique across sources — `<sourceId>|<key>`, the TrackId shape — for
 * things without an id type of their own (a source's shelves). "trending" means nothing until it
 * says whose (D-35).
 */
@JvmInline
value class ScopedKey(val value: String) {
    init {
        require(TrackId.SEPARATOR in value) { "ScopedKey must be source-qualified: $value" }
    }

    val sourceId: SourceId get() = SourceId(value.substringBefore(TrackId.SEPARATOR))
    val key: String get() = value.substringAfter(TrackId.SEPARATOR)

    override fun toString(): String = value

    companion object {
        fun of(source: SourceId, key: String) = ScopedKey("${source.value}${TrackId.SEPARATOR}$key")
    }
}

/** One play slot in the queue. Distinct from TrackId: the same track may be queued twice. */
@JvmInline
value class QueueUid(val value: String) {
    override fun toString(): String = value
}
