package app.podium.core.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/*
 * Schema v1 (data-model.md §3, D-31): the library cache, favorites and the saved queue. Column names
 * follow the data model (snake_case). Structured values that only need to round-trip (credits,
 * version analysis, advertised qualities) are JSON; anything queried or sorted is a column.
 */

/** A source whose library has been synced. */
@Entity(tableName = "source_account")
data class SourceAccountEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "last_sync_at") val lastSyncAt: Long?,
)

@Entity(
    tableName = "track",
    foreignKeys = [
        ForeignKey(
            entity = SourceAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["source_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["source_id", "source_track_id"], unique = true),
        Index(value = ["album_id", "disc_no", "track_no"]),
        Index(value = ["artist_id"]),
        Index(value = ["in_library", "title_sort"]),
        Index(value = ["identity_key"]),
        Index(value = ["isrc"]),
    ],
)
data class TrackEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "source_track_id") val sourceTrackId: String,
    val title: String,
    @ColumnInfo(name = "title_sort") val titleSort: String,
    @ColumnInfo(name = "artist_display") val artistDisplay: String,
    /** The artist the library files this song under (LibraryGrouping). */
    @ColumnInfo(name = "artist_id") val artistId: String,
    @ColumnInfo(name = "artists_json") val artistsJson: String,
    /** The album the library files this song under (LibraryGrouping); never null. */
    @ColumnInfo(name = "album_id") val albumId: String,
    /** The source's own album id, if it gave one (null for songs filed under "Unknown album"). */
    @ColumnInfo(name = "source_album_id") val sourceAlbumId: String?,
    @ColumnInfo(name = "album_title") val albumTitle: String?,
    @ColumnInfo(name = "album_artist_display") val albumArtistDisplay: String?,
    @ColumnInfo(name = "track_no") val trackNo: Int?,
    @ColumnInfo(name = "disc_no") val discNo: Int?,
    /** "YYYY", "YYYY-MM" or "YYYY-MM-DD". */
    @ColumnInfo(name = "release_date") val releaseDate: String?,
    val year: Int?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    val isrc: String?,
    val mbid: String?,
    val explicitness: String,
    @ColumnInfo(name = "version_json") val versionJson: String?,
    @ColumnInfo(name = "identity_key") val identityKey: String?,
    @ColumnInfo(name = "provider_uri") val providerUri: String?,
    @ColumnInfo(name = "provider_data") val providerData: String?,
    val availability: String,
    @ColumnInfo(name = "artwork_source_id") val artworkSourceId: String?,
    @ColumnInfo(name = "artwork_key") val artworkKey: String?,
    @ColumnInfo(name = "qualities_json") val qualitiesJson: String?,
    val routes: String,
    /** 1 when the song is in a library source's current sync; 0 for songs only kept because something references them. */
    @ColumnInfo(name = "in_library") val inLibrary: Boolean,
    /** When a sync stopped seeing it. */
    @ColumnInfo(name = "removed_at") val removedAt: Long?,
    /** Hash of everything above, so a sync rewrites only rows that changed. */
    @ColumnInfo(name = "content_hash") val contentHash: Int,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** Derived from the songs at sync time; owned by one source, rebuilt with it. */
@Entity(
    tableName = "album",
    indices = [Index(value = ["source_id"]), Index(value = ["title_sort"]), Index(value = ["artist_id", "year"])],
)
data class AlbumEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "source_id") val sourceId: String,
    val title: String,
    @ColumnInfo(name = "title_sort") val titleSort: String,
    @ColumnInfo(name = "artist_display") val artistDisplay: String,
    @ColumnInfo(name = "artist_id") val artistId: String,
    val year: Int?,
    @ColumnInfo(name = "track_count") val trackCount: Int,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "artwork_uri") val artworkUri: String?,
)

/** Derived from the songs at sync time; [artworkUri] only from sources with artist pictures. */
@Entity(
    tableName = "artist",
    indices = [Index(value = ["source_id"]), Index(value = ["name_sort"])],
)
data class ArtistEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "source_id") val sourceId: String,
    val name: String,
    @ColumnInfo(name = "name_sort") val nameSort: String,
    @ColumnInfo(name = "artwork_uri") val artworkUri: String?,
    @ColumnInfo(name = "album_count") val albumCount: Int,
    @ColumnInfo(name = "track_count") val trackCount: Int,
)

/**
 * A favorite, by source-qualified track id. Deliberately no foreign key: a favorite outlives a sync
 * that briefly can't see the song (and can be recorded before the first sync).
 */
@Entity(tableName = "liked_track", indices = [Index(value = ["liked_at"])])
data class LikedTrackEntity(
    @PrimaryKey @ColumnInfo(name = "track_id") val trackId: String,
    @ColumnInfo(name = "liked_at") val likedAt: Long,
    @ColumnInfo(name = "sync_state") val syncState: String = "LOCAL_ONLY",
)

/** The saved queue's single state row (id = 1). */
@Entity(tableName = "queue_state")
data class QueueStateEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "current_index") val currentIndex: Int,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    @ColumnInfo(name = "repeat_mode") val repeatMode: String,
    @ColumnInfo(name = "shuffle_enabled") val shuffleEnabled: Boolean,
    @ColumnInfo(name = "context_label") val contextLabel: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** One saved queue slot, in play order. Slots get fresh ids when the queue is restored. */
@Entity(tableName = "queue_item")
data class QueueItemEntity(
    @PrimaryKey val ordinal: Int,
    @ColumnInfo(name = "original_ordinal") val originalOrdinal: Int,
    @ColumnInfo(name = "track_id") val trackId: String,
    val origin: String,
)
