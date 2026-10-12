package app.podium.sources.youtubemusic

import kotlin.test.Test
import kotlin.test.assertEquals

class AlbumRankingTest {

    private fun album(id: String, title: String, type: String?) = YtmAlbum(id, title, emptyList(), type = type)

    @Test
    fun `full albums come before EPs and singles, and the catalogue's order holds within each`() {
        val found = listOf(
            album("1", "Night drive (video version)", "Single"),
            album("2", "Neon", "EP"),
            album("3", "Afterglow", "Album"),
            album("4", "Unlabelled", null),
            album("5", "Second album", "Album"),
        )
        assertEquals(listOf("3", "5", "2", "1", "4"), AlbumRanking.rank("night drive", found).map { it.browseId })
    }

    @Test
    fun `an album titled exactly as searched leads, and an album beats a single of the same name`() {
        val found = listOf(
            album("single", "Midnight Sun", "Single"),
            album("other", "Sunrise", "Album"),
            album("album", "Midnight sun", "Album"),
        )
        assertEquals(listOf("album", "single", "other"), AlbumRanking.rank("midnight  sun!", found).map { it.browseId })
    }
}
