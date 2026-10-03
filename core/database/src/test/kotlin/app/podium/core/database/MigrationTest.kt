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
                    song.artworkSourceId, song.artworkKey, song.qualitiesJson, song.routes, 1, null, song.contentHash, song.updatedAt,
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
}
