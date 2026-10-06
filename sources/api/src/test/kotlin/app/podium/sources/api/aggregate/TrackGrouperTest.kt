package app.podium.sources.api.aggregate

import app.podium.core.model.Availability
import app.podium.core.model.Explicitness
import app.podium.core.model.SourceId
import app.podium.core.model.Track
import app.podium.sources.api.matching.MatchTier
import app.podium.sources.api.resolve.InMemoryEquivalenceStore
import app.podium.sources.testing.track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackGrouperTest {
    private val grouper = TrackGrouper()
    private val a = SourceId("a")
    private val b = SourceId("b")
    private val c = SourceId("c")

    private fun ranked(source: SourceId, vararg tracks: Track) = TrackGrouper.Ranked(source, tracks.toList())

    @Test
    fun `the same recording on two sources is one row with both copies`() {
        val onA = track("Song Shared", "Band", source = "a", key = "1")
        val onB = track("Song Shared", "Band", source = "b", key = "77", durationSec = 201.0)
        val rows = grouper.group(listOf(ranked(a, onA), ranked(b, onB)))
        assertEquals(1, rows.size)
        assertEquals(onA, rows[0].track, "the most preferred source's copy is shown")
        assertEquals(listOf(onB), rows[0].alternates)
        assertEquals(MatchTier.EXACT, rows[0].decisions.values.single().tier)
    }

    @Test
    fun `the same provider key on two sources stays two songs`() {
        val onA = track("Alpha", "Band", source = "a", key = "123")
        val onB = track("Gamma", "Other", source = "b", key = "123")
        val rows = grouper.group(listOf(ranked(a, onA), ranked(b, onB)))
        assertEquals(listOf(onA.id, onB.id), rows.map { it.track.id })
        assertTrue(onA.id != onB.id)
    }

    @Test
    fun `different versions never merge`() {
        val studio = track("Song", "Band", source = "a", key = "s")
        listOf("Song (Live)", "Song (Remix)", "Song (Acoustic)", "Song (Sped Up)", "Song (Slowed)", "Song (Instrumental)", "Song (Karaoke)", "Song (Cover)")
            .forEach { title ->
                val other = track(title, "Band", source = "b", key = title)
                assertEquals(2, grouper.group(listOf(ranked(a, studio), ranked(b, other))).size, title)
            }
    }

    @Test
    fun `explicit and clean never merge`() {
        val explicit = track("Song", "Band", source = "a", key = "e", explicitness = Explicitness.EXPLICIT)
        val clean = track("Song", "Band", source = "b", key = "c", explicitness = Explicitness.CLEAN)
        assertEquals(2, grouper.group(listOf(ranked(a, explicit), ranked(b, clean))).size)
    }

    @Test
    fun `a matching length alone never makes two songs one`() {
        val one = track("First Song", "Band", source = "a", key = "1", durationSec = 200.0)
        val two = track("Second Song", "Band", source = "b", key = "2", durationSec = 200.0)
        assertEquals(2, grouper.group(listOf(ranked(a, one), ranked(b, two))).size)
    }

    @Test
    fun `a cover by someone else never merges`() {
        val original = track("Song", "Band", source = "a", key = "o")
        val cover = track("Song", "Someone Else", source = "b", key = "c")
        assertEquals(2, grouper.group(listOf(ranked(a, original), ranked(b, cover))).size)
    }

    @Test
    fun `a close but not exact match stays apart`() {
        val one = track("Song", "Band", source = "a", key = "1", durationSec = 200.0)
        val four = track("Song", "Band", source = "b", key = "2", durationSec = 204.0) // STRONG at best
        assertEquals(2, grouper.group(listOf(ranked(a, one), ranked(b, four))).size)
    }

    @Test
    fun `a source's own results are never merged with each other`() {
        val first = track("Song", "Band", source = "a", key = "1")
        val second = track("Song", "Band", source = "a", key = "2")
        assertEquals(2, grouper.group(listOf(ranked(a, first, second))).size)
    }

    @Test
    fun `two equally good copies on one source make the match ambiguous`() {
        val onA = track("Song", "Band", source = "a", key = "1")
        val b1 = track("Song", "Band", source = "b", key = "x")
        val b2 = track("Song", "Band", source = "b", key = "y")
        val rows = grouper.group(listOf(ranked(a, onA), ranked(b, b1, b2)))
        assertEquals(3, rows.size, "neither copy joins")
        assertTrue(rows.all { it.alternates.isEmpty() })
    }

    @Test
    fun `a copy the preferred source can't play is not the one shown`() {
        val gated = track("Song", "Band", source = "a", key = "1").copy(availability = Availability.Unavailable("Gated"))
        val open = track("Song", "Band", source = "b", key = "2")
        val row = grouper.group(listOf(ranked(a, gated), ranked(b, open))).single()
        assertEquals(open, row.track)
        assertEquals(listOf(gated), row.alternates)
    }

    @Test
    fun `order interleaves sources by rank, preferred source first, whoever answered first`() {
        val a1 = track("A one", source = "a", key = "a1")
        val a2 = track("A two", source = "a", key = "a2")
        val b1 = track("B one", source = "b", key = "b1")
        val c1 = track("C one", source = "c", key = "c1")
        val rows = grouper.group(listOf(ranked(a, a1, a2), ranked(b, b1), ranked(c, c1)))
        assertEquals(listOf(a1, b1, c1, a2), rows.map { it.track })
    }

    @Test
    fun `a later page's copy joins the row already shown`() {
        val onA = track("Song Shared", "Band", source = "a", key = "1")
        val page1 = grouper.group(listOf(ranked(a, onA)))
        val onB = track("Song Shared", "Band", source = "b", key = "2")
        val other = track("Fresh", "Band", source = "b", key = "3")
        val all = grouper.group(listOf(ranked(b, onB, other)), existing = page1)
        assertEquals(2, all.size)
        assertEquals(onA, all[0].track, "the shown copy stays put")
        assertEquals(listOf(onB), all[0].alternates)
        assertEquals(other, all[1].track)
    }

    @Test
    fun `remembered groups tell the resolver which copies are interchangeable`() {
        val onA = track("Song Shared", "Band", source = "a", key = "1")
        val onB = track("Song Shared", "Band", source = "b", key = "2")
        val store = InMemoryEquivalenceStore()
        grouper.group(listOf(ranked(a, onA), ranked(b, onB))).forEach(store::remember)
        assertEquals(listOf(onB), store.exactEquivalents(onA))
        assertEquals(listOf(onA), store.exactEquivalents(onB))
    }

    @Test
    fun `distinct recordings keeps the first copy of each song`() {
        val onA = track("Song Shared", "Band", source = "a", key = "1")
        val onB = track("Song Shared", "Band", source = "b", key = "2")
        val live = track("Song Shared (Live)", "Band", source = "b", key = "3")
        assertEquals(listOf(onB, live), grouper.distinctRecordings(listOf(onB, onA, live)))
    }
}
