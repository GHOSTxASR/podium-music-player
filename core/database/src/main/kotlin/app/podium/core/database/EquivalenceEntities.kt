package app.podium.core.database

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

/*
 * Schema v4 (D-36): cross-source identity decisions (data-model.md `track_equivalence`). One row per
 * pair of copies, ordered (track_a < track_b). Only two kinds are ever stored: an EXACT match the
 * matcher made (AUTO), and a "not the same song" the listener said (USER, tier REJECTED). Anything
 * below EXACT isn't kept. Pairs, not groups: EXACT is not transitive (a 2 s length window on each
 * side is 4 s across), so every stored claim is one the matcher actually made about those two.
 */
@Entity(
    tableName = "track_equivalence",
    primaryKeys = ["track_a", "track_b"],
    indices = [Index(value = ["track_b"])],
)
data class TrackEquivalenceEntity(
    @ColumnInfo(name = "track_a") val trackA: String,
    @ColumnInfo(name = "track_b") val trackB: String,
    /** `EXACT` or `REJECTED`. */
    val tier: String,
    val confidence: Float,
    /** The matcher's reasons, as a JSON array of sentences; empty for the listener's own decision. */
    @ColumnInfo(name = "evidence_json") val evidenceJson: String,
    /** `AUTO` (the matcher) or `USER` (the listener; always wins). */
    @ColumnInfo(name = "decided_by") val decidedBy: String,
    @ColumnInfo(name = "decided_at") val decidedAt: Long,
) {
    companion object {
        const val EXACT = "EXACT"
        const val REJECTED = "REJECTED"
        const val AUTO = "AUTO"
        const val USER = "USER"
    }
}

@Dao
interface EquivalenceDao {
    @Query("SELECT * FROM track_equivalence")
    suspend fun all(): List<TrackEquivalenceEntity>

    @Query("SELECT * FROM track_equivalence WHERE track_a = :a AND track_b = :b")
    suspend fun get(a: String, b: String): TrackEquivalenceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(decision: TrackEquivalenceEntity)
}
