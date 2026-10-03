package app.podium.feature.settings

import app.podium.sources.api.FolderSelection
import app.podium.sources.api.MusicFolder
import kotlin.test.Test
import kotlin.test.assertEquals

class MusicFoldersTest {

    private val folders = listOf(
        MusicFolder("Music/Albums/Coastline/", 4),
        MusicFolder("Music/Albums/Woodland/", 6),
        MusicFolder("Music/", 2),
        MusicFolder("Download/", 3),
        MusicFolder("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio/", 40),
    )

    @Test
    fun `the top level lists each top folder with every song inside it`() {
        val top = foldersInside("", folders)
        assertEquals(listOf("Android", "Download", "Music"), top.map { it.name })
        assertEquals(12, top.single { it.name == "Music" }.songCount)
        assertEquals(true, top.single { it.name == "Music" }.hasSubfolders)
        assertEquals(false, top.single { it.name == "Download" }.hasSubfolders)
    }

    @Test
    fun `inside a folder, its subfolders`() {
        val albums = foldersInside("Music/Albums/", folders)
        assertEquals(listOf("Coastline", "Woodland"), albums.map { it.name })
        assertEquals("Music/Albums/Coastline/", albums.first().path)
        assertEquals(listOf("Albums"), foldersInside("Music", folders).map { it.name })
    }

    @Test
    fun `the song count follows the choice`() {
        assertEquals(55, songsIncluded(folders, FolderSelection.Everything))
        assertEquals(15, songsIncluded(folders, FolderSelection.Default))
        assertEquals(4, songsIncluded(folders, FolderSelection(included = setOf("Music/Albums/Coastline/"))))
    }
}
