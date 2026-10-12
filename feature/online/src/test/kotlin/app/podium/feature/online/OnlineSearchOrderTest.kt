package app.podium.feature.online

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Albums lead the search results when the search is an album's own title (D-74). */
class OnlineSearchOrderTest {

    @Test
    fun `an album's exact title puts albums first`() {
        assertTrue(albumsLead("midnight sun", listOf("Midnight Sun", "Sunrise")))
        assertTrue(albumsLead("  Midnight-Sun! ", listOf("midnight sun")))
    }

    @Test
    fun `anything else keeps songs first`() {
        assertFalse(albumsLead("midnight", listOf("Midnight Sun")))
        assertFalse(albumsLead("midnight sun", listOf("Sunrise", "Midnight Sun")), "only the top album counts")
        assertFalse(albumsLead("midnight sun", emptyList()))
        assertFalse(albumsLead("!!", listOf("!!")))
    }
}
