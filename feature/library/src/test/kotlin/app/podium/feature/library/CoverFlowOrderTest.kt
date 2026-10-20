package app.podium.feature.library

import app.podium.core.model.AlbumId
import app.podium.core.model.ArtistId
import org.junit.Test
import kotlin.test.assertEquals

/** Cover Flow's order (D-84): by artist, then album, an artist's collaborations right after their own. */
class CoverFlowOrderTest {

    private fun album(title: String, artist: String) = LibraryAlbum(AlbumId("test|$artist|$title"), title, artist, ArtistId("test|$artist"), null, null, 10)

    @Test
    fun `albums stand together by artist, then by title`() {
        val shuffled = listOf(
            album("Views", "Drake"),
            album("Abbey Road", "The Beatles"),
            album("Scorpion", "Drake"),
            album("Positions", "Ariana Grande"),
            album("Please Me", "drake, Ariana Grande"),
            album("After Hours", "The Weeknd"),
            album("Élan", "Émilie"),
            album("Honestly", "Drake & Future"),
        )
        val ordered = coverFlowOrder(shuffled).map { "${it.artist} / ${it.title}" }
        assertEquals(
            listOf(
                "Ariana Grande / Positions",
                "The Beatles / Abbey Road",
                "Drake / Scorpion",
                "Drake / Views",
                "drake, Ariana Grande / Please Me",
                "Drake & Future / Honestly",
                "Émilie / Élan",
                "The Weeknd / After Hours",
            ),
            ordered,
        )
    }

    @Test
    fun `a large library is ordered in a moment`() {
        val albums = (0 until 3_000).map { album("The Album ${it * 31 % 3_000} Édition", "Artist ${(it * 7_919) % 997}") }
        coverFlowOrder(albums) // warm up
        val started = System.nanoTime()
        val ordered = coverFlowOrder(albums)
        val millis = (System.nanoTime() - started) / 1_000_000
        assertEquals(3_000, ordered.size)
        kotlin.test.assertTrue(millis < 500, "took $millis ms")
    }
}
