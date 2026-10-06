package app.podium.core.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/*
 * Schema v2 (D-34): the ONLINE environment's own tables. They never share rows with the local
 * library's (`liked_track` stays local favorites, history is per environment). Every row carries
 * the source, the account ("" = on this device, not signed in) and the provider's id, so its
 * identity is never ambiguous. Online songs' metadata is cached in `track` with in_library = 0,
 * which local reads never list.
 */

/** A song liked in ONLINE. */
@Entity(
    tableName = "online_liked_track",
    primaryKeys = ["account_key", "track_id"],
    indices = [Index(value = ["liked_at"]), Index(value = ["source_id"])],
)
data class OnlineLikedTrackEntity(
    @ColumnInfo(name = "account_key") val accountKey: String,
    @ColumnInfo(name = "track_id") val trackId: String,
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "liked_at") val likedAt: Long,
    /** LOCAL_ONLY until a signed-in source can hold it; then PENDING / SYNCED. */
    @ColumnInfo(name = "sync_state") val syncState: String = "LOCAL_ONLY",
)

/** A playlist in ONLINE: Podium's own, or mirroring one on the source ([remotePlaylistId]). */
@Entity(tableName = "online_playlist", indices = [Index(value = ["source_id", "account_key"])])
data class OnlinePlaylistEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "account_key") val accountKey: String,
    @ColumnInfo(name = "remote_playlist_id") val remotePlaylistId: String?,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** One entry of an online playlist; [position] is fractional so inserts never renumber. */
@Entity(
    tableName = "online_playlist_track",
    foreignKeys = [
        ForeignKey(entity = OnlinePlaylistEntity::class, parentColumns = ["id"], childColumns = ["playlist_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["playlist_id", "position"])],
)
data class OnlinePlaylistTrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    @ColumnInfo(name = "track_id") val trackId: String,
    val position: Double,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

/** One listen in ONLINE. */
@Entity(
    tableName = "online_history",
    indices = [Index(value = ["started_at"]), Index(value = ["track_id", "started_at"])],
)
data class OnlineHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "account_key") val accountKey: String,
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "track_id") val trackId: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "played_ms") val playedMs: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long?,
    /** 0..1 of the song heard, when its length is known. */
    val completion: Float?,
    /**
     * Schema v3 (D-35): the source whose copy actually played, when it wasn't the song's own (an
     * EXACT copy elsewhere). The row stays the song the listener chose; this only says who served it.
     */
    @ColumnInfo(name = "served_by") val servedBy: String? = null,
)

/** A playlist row with what lists need: how many songs and a few covers. */
data class OnlinePlaylistRow(
    val id: String,
    @ColumnInfo(name = "source_id") val sourceId: String,
    val name: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "track_count") val trackCount: Int,
)

/** A playlist entry joined with its song. */
data class OnlinePlaylistEntryRow(
    @ColumnInfo(name = "entry_id") val entryId: Long,
    val position: Double,
    @androidx.room3.Embedded val track: TrackEntity,
)
