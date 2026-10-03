package app.podium.core.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.RawQuery
import androidx.room3.RoomRawQuery
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

/** Id and content hash of a stored song, for change detection during sync. */
data class TrackHash(val id: String, @androidx.room3.ColumnInfo(name = "content_hash") val contentHash: Int)

@Dao
interface LibraryDao {

    // --- Sync ----------------------------------------------------------------------------------------

    @Upsert
    suspend fun upsertSource(source: SourceAccountEntity)

    /** A source known only because something references one of its songs (never synced yet). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSourcesIfMissing(sources: List<SourceAccountEntity>)

    @Query("SELECT id, content_hash FROM track WHERE source_id = :sourceId AND in_library = 1")
    suspend fun libraryHashes(sourceId: String): List<TrackHash>

    @Upsert
    suspend fun upsertTracks(tracks: List<TrackEntity>)

    /** Songs only kept for a reference (the queue) are added if missing and never overwrite a library row. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTracksIfMissing(tracks: List<TrackEntity>)

    @Query("UPDATE track SET in_library = 0, removed_at = :now WHERE source_id = :sourceId AND id IN (:ids)")
    suspend fun markRemoved(sourceId: String, ids: List<String>, now: Long)

    @Query("DELETE FROM album WHERE source_id = :sourceId")
    suspend fun deleteAlbums(sourceId: String)

    @Query("DELETE FROM artist WHERE source_id = :sourceId")
    suspend fun deleteArtists(sourceId: String)

    @Upsert
    suspend fun upsertAlbums(albums: List<AlbumEntity>)

    @Upsert
    suspend fun upsertArtists(artists: List<ArtistEntity>)

    // --- Reads ---------------------------------------------------------------------------------------
    // [sources] scopes every read to the sources whose library is usable right now.

    @Query("SELECT * FROM track WHERE in_library = 1 AND source_id IN (:sources) ORDER BY title_sort, id")
    fun songs(sources: List<String>): Flow<List<TrackEntity>>

    @Query("SELECT * FROM album WHERE source_id IN (:sources) ORDER BY title_sort, id")
    fun albums(sources: List<String>): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM album WHERE id = :id")
    fun album(id: String): Flow<AlbumEntity?>

    @Query(
        "SELECT * FROM track WHERE in_library = 1 AND album_id = :albumId " +
            "ORDER BY COALESCE(disc_no, 1), COALESCE(track_no, 2147483647), title_sort",
    )
    fun albumTracks(albumId: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM artist WHERE source_id IN (:sources) ORDER BY name_sort, id")
    fun artists(sources: List<String>): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM artist WHERE id = :id")
    fun artist(id: String): Flow<ArtistEntity?>

    @Query("SELECT * FROM album WHERE id IN (SELECT DISTINCT album_id FROM track WHERE in_library = 1 AND artist_id = :artistId) ORDER BY year, title_sort")
    fun artistAlbums(artistId: String): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM track WHERE in_library = 1 AND artist_id = :artistId ORDER BY title_sort, id")
    fun artistTracks(artistId: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM track WHERE id = :id")
    suspend fun track(id: String): TrackEntity?

    @Query("SELECT COUNT(*) FROM track WHERE in_library = 1")
    fun librarySongCount(): Flow<Int>

    @Query("SELECT * FROM track WHERE id IN (:ids)")
    suspend fun tracks(ids: List<String>): List<TrackEntity>

    /** Full-text search over the FTS5 index (`track_fts`, created outside Room's schema: see [Fts]). */
    @RawQuery(observedEntities = [TrackEntity::class])
    suspend fun search(query: RoomRawQuery): List<TrackEntity>
}

@Dao
interface LikeDao {
    @Query("SELECT track_id FROM liked_track ORDER BY liked_at DESC")
    fun likedIds(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun like(likes: List<LikedTrackEntity>)

    @Query("DELETE FROM liked_track WHERE track_id = :trackId")
    suspend fun unlike(trackId: String)
}

@Dao
interface QueueDao {
    @Query("SELECT * FROM queue_state WHERE id = 1")
    suspend fun state(): QueueStateEntity?

    @Query("SELECT * FROM queue_item ORDER BY ordinal")
    suspend fun items(): List<QueueItemEntity>

    @Upsert
    suspend fun upsertState(state: QueueStateEntity)

    @Query("DELETE FROM queue_item")
    suspend fun clearItems()

    @Insert
    suspend fun insertItems(items: List<QueueItemEntity>)

    @Query("UPDATE queue_state SET current_index = :currentIndex, position_ms = :positionMs, updated_at = :now WHERE id = 1")
    suspend fun updatePosition(currentIndex: Int, positionMs: Long, now: Long)

    @Query("DELETE FROM queue_state")
    suspend fun clearState()
}
