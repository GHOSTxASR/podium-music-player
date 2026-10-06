package app.podium.core.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface OnlineDao {

    // --- The online songs' metadata cache (in `track`, in_library = 0) --------------------------------

    @Query("SELECT id FROM track WHERE in_library = 1 AND id IN (:ids)")
    suspend fun libraryIds(ids: List<String>): List<String>

    @Upsert
    suspend fun cacheTracks(tracks: List<TrackEntity>)

    // --- Likes ---------------------------------------------------------------------------------------

    @Query("SELECT track_id FROM online_liked_track WHERE account_key = :account")
    fun likedIds(account: String): Flow<List<String>>

    @Query(
        "SELECT track.* FROM online_liked_track JOIN track ON track.id = online_liked_track.track_id " +
            "WHERE online_liked_track.account_key = :account ORDER BY online_liked_track.liked_at DESC",
    )
    fun likedTracks(account: String): Flow<List<TrackEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun like(like: OnlineLikedTrackEntity)

    @Query("DELETE FROM online_liked_track WHERE account_key = :account AND track_id = :trackId")
    suspend fun unlike(account: String, trackId: String)

    // --- Playlists -----------------------------------------------------------------------------------

    @Query(
        "SELECT p.id, p.source_id, p.name, p.updated_at, " +
            "(SELECT COUNT(*) FROM online_playlist_track t WHERE t.playlist_id = p.id) AS track_count " +
            "FROM online_playlist p WHERE p.account_key = :account ORDER BY p.updated_at DESC",
    )
    fun playlists(account: String): Flow<List<OnlinePlaylistRow>>

    @Query("SELECT * FROM online_playlist WHERE id = :id")
    fun playlist(id: String): Flow<OnlinePlaylistEntity?>

    @Query("SELECT * FROM online_playlist WHERE id = :id")
    suspend fun playlistNow(id: String): OnlinePlaylistEntity?

    @Query(
        "SELECT t.id AS entry_id, t.position, track.* FROM online_playlist_track t JOIN track ON track.id = t.track_id " +
            "WHERE t.playlist_id = :id ORDER BY t.position, t.id",
    )
    fun entries(id: String): Flow<List<OnlinePlaylistEntryRow>>

    @Query("SELECT id, position FROM online_playlist_track WHERE playlist_id = :id ORDER BY position, id")
    suspend fun positions(id: String): List<EntryPosition>

    @Insert
    suspend fun insertPlaylist(playlist: OnlinePlaylistEntity)

    @Query("UPDATE online_playlist SET name = :name, updated_at = :now WHERE id = :id")
    suspend fun rename(id: String, name: String, now: Long)

    @Query("UPDATE online_playlist SET updated_at = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long)

    @Query("DELETE FROM online_playlist WHERE id = :id")
    suspend fun deletePlaylist(id: String)

    @Insert
    suspend fun insertEntries(entries: List<OnlinePlaylistTrackEntity>)

    @Query("DELETE FROM online_playlist_track WHERE id = :entryId")
    suspend fun deleteEntry(entryId: Long)

    @Query("UPDATE online_playlist_track SET position = :position WHERE id = :entryId")
    suspend fun setPosition(entryId: Long, position: Double)

    // --- History -------------------------------------------------------------------------------------

    @Insert
    suspend fun record(listen: OnlineHistoryEntity)

    /** Most recent listen per song, newest first. */
    @Query(
        "SELECT track.* FROM track JOIN (SELECT track_id, MAX(started_at) AS last_played FROM online_history " +
            "WHERE account_key = :account GROUP BY track_id) h ON h.track_id = track.id ORDER BY h.last_played DESC LIMIT :limit",
    )
    fun recentTracks(account: String, limit: Int): Flow<List<TrackEntity>>

    @Query("SELECT * FROM online_history WHERE account_key = :account ORDER BY started_at DESC, id DESC LIMIT :limit")
    suspend fun listens(account: String, limit: Int): List<OnlineHistoryEntity>

    @Query("SELECT DISTINCT track_id FROM online_history WHERE started_at >= :since")
    suspend fun playedSince(since: Long): List<String>

    @Query("DELETE FROM online_history WHERE account_key = :account")
    suspend fun clearHistory(account: String)

    @Query("SELECT COUNT(*) FROM online_history")
    suspend fun historyCount(): Int
}

data class EntryPosition(val id: Long, val position: Double)
