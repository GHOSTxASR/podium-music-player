package app.podium.core.database

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.RoomRawQuery
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import java.io.File

@Database(
    entities = [
        SourceAccountEntity::class,
        TrackEntity::class,
        AlbumEntity::class,
        ArtistEntity::class,
        LikedTrackEntity::class,
        QueueStateEntity::class,
        QueueItemEntity::class,
    ],
    version = PodiumDatabase.VERSION,
    exportSchema = true,
)
abstract class PodiumDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
    abstract fun likes(): LikeDao
    abstract fun queue(): QueueDao

    companion object {
        const val VERSION = 1
        const val FILE_NAME = "podium.db"

        /**
         * The app's database (ADR-004): Room 3 on bundled SQLite, so FTS5 is there on every API
         * level. Before a schema upgrade the file is copied aside (see [DatabaseBackup]).
         */
        fun create(context: Context): PodiumDatabase {
            val file = context.getDatabasePath(FILE_NAME)
            DatabaseBackup.beforeOpen(file, VERSION)
            return Room.databaseBuilder(context, PodiumDatabase::class.java, file.absolutePath)
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .addCallback(Fts.Callback)
                .build()
        }

        /** The same database in memory, for tests: same driver, same search index. */
        fun createInMemory(context: Context): PodiumDatabase =
            Room.inMemoryDatabaseBuilder(context, PodiumDatabase::class.java)
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .addCallback(Fts.Callback)
                .build()
    }
}

/**
 * The search index (data-model.md §3, `track_fts`). FTS5 isn't modelled by Room, so the table and
 * the triggers that keep it current live here. It's a self-contained FTS5 table keyed by track id
 * (not external content on `track`'s implicit rowid, which VACUUM may renumber).
 */
internal object Fts {
    private val schema = listOf(
        """CREATE VIRTUAL TABLE IF NOT EXISTS track_fts USING fts5(
            track_id UNINDEXED, title, artist, album,
            tokenize = 'unicode61 remove_diacritics 2', prefix = '2 3')""",
        """CREATE TRIGGER IF NOT EXISTS track_fts_insert AFTER INSERT ON track WHEN new.in_library = 1 BEGIN
            INSERT INTO track_fts(track_id, title, artist, album) VALUES (new.id, new.title, new.artist_display, new.album_title);
        END""",
        """CREATE TRIGGER IF NOT EXISTS track_fts_update AFTER UPDATE ON track BEGIN
            DELETE FROM track_fts WHERE track_id = old.id;
            INSERT INTO track_fts(track_id, title, artist, album)
                SELECT new.id, new.title, new.artist_display, new.album_title WHERE new.in_library = 1;
        END""",
        """CREATE TRIGGER IF NOT EXISTS track_fts_delete AFTER DELETE ON track BEGIN
            DELETE FROM track_fts WHERE track_id = old.id;
        END""",
    )

    object Callback : RoomDatabase.Callback() {
        override suspend fun onOpen(connection: SQLiteConnection) {
            val existed = connection.prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'track_fts'").use { it.step() }
            schema.forEach { connection.execSQL(it) }
            // An index created for a database that already has songs starts out filled.
            if (!existed) {
                connection.execSQL(
                    "INSERT INTO track_fts(track_id, title, artist, album) " +
                        "SELECT id, title, artist_display, album_title FROM track WHERE in_library = 1",
                )
            }
        }
    }

    /**
     * Turns what someone typed into an FTS5 query: every word must match the start of a word in
     * the title, artist or album ("beat rev" finds "Revolver" by The Beatles). Accents and case
     * don't matter. Returns null when there's nothing searchable.
     */
    fun matchExpression(text: String): String? {
        val words = text.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        return words.joinToString(" ") { "\"${it.replace("\"", "")}\"*" }
    }

    fun searchQuery(match: String, sources: List<String>, limit: Int): RoomRawQuery {
        val placeholders = sources.joinToString(",") { "?" }
        return RoomRawQuery(
            "SELECT track.* FROM track_fts JOIN track ON track.id = track_fts.track_id " +
                "WHERE track_fts MATCH ? AND track.in_library = 1 AND track.source_id IN ($placeholders) " +
                "ORDER BY bm25(track_fts, 0.0, 10.0, 5.0, 2.0), track.title_sort LIMIT ?",
        ) { statement ->
            var i = 1
            statement.bindText(i++, match)
            sources.forEach { statement.bindText(i++, it) }
            statement.bindLong(i, limit.toLong())
        }
    }
}

/**
 * Pre-migration safety (ADR-004): before opening a database whose schema will change, copy the file
 * to `podium.db.bak-v<old>` so a failed migration can be undone. Reads the version from the SQLite
 * header without opening the database.
 */
internal object DatabaseBackup {
    fun beforeOpen(file: File, targetVersion: Int) {
        val current = userVersion(file) ?: return
        if (current in 1 until targetVersion) {
            file.copyTo(File(file.parentFile, "${file.name}.bak-v$current"), overwrite = true)
        }
    }

    /** SQLite stores `user_version` big-endian at byte offset 60 of the header. */
    fun userVersion(file: File): Int? {
        if (!file.isFile || file.length() < 100) return null
        val header = ByteArray(100)
        file.inputStream().use { if (it.read(header) < 100) return null }
        if (String(header, 0, 15, Charsets.US_ASCII) != "SQLite format 3") return null
        return ((header[60].toInt() and 0xFF) shl 24) or ((header[61].toInt() and 0xFF) shl 16) or
            ((header[62].toInt() and 0xFF) shl 8) or (header[63].toInt() and 0xFF)
    }
}
