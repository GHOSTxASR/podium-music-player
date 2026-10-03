package app.podium.sources.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FolderSelectionTest {

    @Test
    fun `nothing chosen reads everything`() {
        assertTrue(FolderSelection.Everything.includes("Music/Albums/"))
        assertTrue(FolderSelection.Everything.includes(""))
    }

    @Test
    fun `the default skips where apps keep their own audio`() {
        val d = FolderSelection.Default
        assertTrue(d.includes("Music/"))
        assertTrue(d.includes("Download/"))
        assertFalse(d.includes("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/202340/"))
        assertFalse(d.includes("WhatsApp/Media/WhatsApp Audio/"))
        assertFalse(d.includes("Recordings/Call/"))
    }

    @Test
    fun `including a folder narrows the library to it`() {
        val s = FolderSelection.Everything.toggled("Download/").toggled("Download/") // off, then on again
        assertEquals(FolderSelection.Everything, s)
        val onlyMusic = FolderSelection(included = setOf("Music/"))
        assertTrue(onlyMusic.includes("Music/Albums/Coastline/"))
        assertFalse(onlyMusic.includes("Download/"))
    }

    @Test
    fun `excluding inside an included folder keeps the rest of it`() {
        val s = FolderSelection(included = setOf("Music/")).toggled("Music/Podcasts/")
        assertTrue(s.includes("Music/Albums/"))
        assertFalse(s.includes("Music/Podcasts/Episode 1/"))
        assertTrue(s.isMixedBelow("Music/"))
        assertFalse(s.isMixedBelow("Music/Albums/"))
    }

    @Test
    fun `toggling a folder replaces any finer choices inside it`() {
        val s = FolderSelection(included = setOf("Music/"), excluded = setOf("Music/Podcasts/"))
        val off = s.toggled("Music/")
        assertFalse(off.includes("Music/Albums/"))
        assertFalse(off.includes("Music/Podcasts/"))
        val on = off.toggled("Music/")
        assertTrue(on.includes("Music/Podcasts/"), "turning the folder back on includes all of it")
    }

    @Test
    fun `paths are compared as folders, not as text prefixes`() {
        val s = FolderSelection(included = setOf("Music/"))
        assertFalse(s.includes("Musicals/"))
        assertEquals("Music/", FolderSelection.normalize("/Music"))
        assertEquals("", FolderSelection.normalize("/"))
    }

    @Test
    fun `turning on a folder the default skips brings it back alone`() {
        val s = FolderSelection.Default.toggled("Recordings/")
        assertTrue(s.includes("Recordings/"))
        assertFalse(s.includes("WhatsApp/"))
        assertTrue(s.includes("Music/"))
    }
}
