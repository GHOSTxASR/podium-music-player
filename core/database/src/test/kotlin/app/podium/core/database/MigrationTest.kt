package app.podium.core.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.model.TrackId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every schema change is migration-tested (ADR-004): a database created exactly as v1 shipped
 * (from the committed schema JSON) opens on the current version with its data intact, and the
 * pre-migration backup is taken.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Create a database file the way schema [version] defines it. */
    private fun createFromSchema(file: File, version: Int, seed: (androidx.sqlite.SQLiteConnection) -> Unit) {
        val schema = Json.parseToJsonElement(File("schemas/app.podium.core.database.PodiumDatabase/$version.json").readText()).jsonObject["database"]!!.jsonObject
        file.parentFile?.mkdirs()
        val connection = BundledSQLiteDriver().open(file.absolutePath)
        try {
            schema["entities"]!!.jsonArray.forEach { entity ->
                val e = entity.jsonObject
                val table = e["tableName"]!!.jsonPrimitive.content
                connection.execSQL(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                e["indices"]?.jsonArray?.forEach { index ->
                    connection.execSQL(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            schema["setupQueries"]!!.jsonArray.forEach { connection.execSQL(it.jsonPrimitive.content) }
            connection.execSQL("PRAGMA user_version = $version")
            seed(connection)
        } finally {
            connection.close()
        }
    }

    @Test
    fun `a v1 database opens on v2 with its library, favorites and queue intact`() = runTest {
        val file = context.getDatabasePath(PodiumDatabase.FILE_NAME)
        file.delete()
        val song = TrackMapping.toEntity(song("Coastline"), inLibrary = true, now = 1)
        createFromSchema(file, 1) { c ->
            c.execSQL("INSERT INTO source_account (id, display_name, last_sync_at) VALUES ('local', 'This phone', 1)")
            insertTrack(c, song, inLibrary = true)
            c.execSQL("INSERT INTO liked_track (track_id, liked_at, sync_state) VALUES ('local|coastline', 5, 'LOCAL_ONLY')")
            c.execSQL("INSERT INTO queue_state (id, current_index, position_ms, repeat_mode, shuffle_enabled, context_label, updated_at) VALUES (1, 0, 4200, 'OFF', 0, 'Woodland', 9)")
            c.execSQL("INSERT INTO queue_item (ordinal, original_ordinal, track_id, origin) VALUES (0, 0, 'local|coastline', 'CONTEXT')")
        }

        val db = PodiumDatabase.create(context)
        try {
            assertEquals("Coastline", LibraryStore(db).track(TrackId("local|coastline"))?.title)
            assertEquals(listOf("local|coastline"), db.likes().likedIds().first())
            val queue = DatabaseQueueStore(db).load()!!
            assertEquals(4200, queue.positionMs)
            // The new ONLINE tables are there and usable.
            OnlineLibraryStore(db).setLiked(song("Online", source = app.podium.core.model.SourceId("audius")), true)
            assertEquals(1, OnlineLibraryStore(db).likedIds().first().size)
            // The search index was built for the existing library on first open.
            assertEquals(1, LibraryStore(db).search("coast", listOf(Local)).size)
        } finally {
            db.close()
        }
        assertTrue(File(file.parentFile, "${file.name}.bak-v1").isFile, "the v1 file was copied aside before migrating")
    }

    @Test
    fun `a v2 database opens on v3 with every online like, playlist and listen intact`() = runTest {
        val file = context.getDatabasePath(PodiumDatabase.FILE_NAME)
        file.delete()
        val audius = app.podium.core.model.SourceId("audius")
        val online = TrackMapping.toEntity(song("Signal", source = audius, key = "123"), inLibrary = false, now = 1)
        createFromSchema(file, 2) { c ->
            c.execSQL("INSERT INTO source_account (id, display_name, last_sync_at) VALUES ('audius', 'audius', NULL)")
            insertTrack(c, online, inLibrary = false)
            c.execSQL("INSERT INTO online_liked_track (account_key, track_id, source_id, provider_id, liked_at, sync_state) VALUES ('', 'audius|123', 'audius', '123', 7, 'LOCAL_ONLY')")
            c.execSQL("INSERT INTO online_playlist (id, source_id, account_key, remote_playlist_id, name, created_at, updated_at) VALUES ('p1', 'audius', '', NULL, 'Night drive', 8, 8)")
            c.execSQL("INSERT INTO online_playlist_track (playlist_id, track_id, position, added_at) VALUES ('p1', 'audius|123', 1.0, 8)")
            c.execSQL(
                "INSERT INTO online_history (source_id, account_key, provider_id, track_id, started_at, played_ms, duration_ms, completion) " +
                    "VALUES ('audius', '', '123', 'audius|123', 9, 60000, 200000, 0.3)",
            )
        }

        val db = PodiumDatabase.create(context)
        try {
            val store = OnlineLibraryStore(db)
            assertEquals(setOf(TrackId("audius|123")), store.likedIds().first())
            assertEquals("Signal", store.likedTracks().first().single().title)
            val playlist = store.playlists().first().single()
            assertEquals("Night drive", playlist.name)
            assertEquals(audius, playlist.sourceId)
            assertEquals("Signal", store.playlist("p1").first()!!.entries.single().track.title)
            val listen = store.listens().single()
            assertEquals("audius", listen.sourceId)
            assertEquals("123", listen.providerId)
            assertEquals(null, listen.servedBy, "old listens were served by their own source")
            assertEquals("Signal", store.recentlyPlayed().first().single().title)
            // The new column takes the serving source of a listen played from another source's copy.
            store.record(song("Signal", source = audius, key = "123"), startedAt = 10, playedMs = 30_000, servedBy = app.podium.core.model.SourceId("other"))
            assertEquals("other", store.listens().first().servedBy)
        } finally {
            db.close()
        }
        assertTrue(File(file.parentFile, "${file.name}.bak-v2").isFile, "the v2 file was copied aside before migrating")
    }

    private fun insertTrack(c: androidx.sqlite.SQLiteConnection, song: TrackEntity, inLibrary: Boolean) {
        c.prepare(
            "INSERT INTO track (id, source_id, source_track_id, title, title_sort, artist_display, artist_id, artists_json, album_id, " +
                "source_album_id, album_title, album_artist_display, track_no, disc_no, release_date, year, duration_ms, isrc, mbid, explicitness, " +
                "version_json, identity_key, provider_uri, provider_data, availability, artwork_source_id, artwork_key, qualities_json, routes, " +
                "in_library, removed_at, content_hash, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { st ->
            val values = listOf(
                song.id, song.sourceId, song.sourceTrackId, song.title, song.titleSort, song.artistDisplay, song.artistId, song.artistsJson, song.albumId,
                song.sourceAlbumId, song.albumTitle, song.albumArtistDisplay, song.trackNo, song.discNo, song.releaseDate, song.year, song.durationMs,
                song.isrc, song.mbid, song.explicitness, song.versionJson, song.identityKey, song.providerUri, song.providerData, song.availability,
                song.artworkSourceId, song.artworkKey, song.qualitiesJson, song.routes, if (inLibrary) 1 else 0, null, song.contentHash, song.updatedAt,
            )
            values.forEachIndexed { i, v ->
                when (v) {
                    null -> st.bindNull(i + 1)
                    is String -> st.bindText(i + 1, v)
                    is Int -> st.bindLong(i + 1, v.toLong())
                    is Long -> st.bindLong(i + 1, v)
                    else -> error("unexpected $v")
                }
            }
            st.step()
        }
    }
}
