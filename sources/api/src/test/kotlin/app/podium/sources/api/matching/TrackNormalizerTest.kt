package app.podium.sources.api.matching

import app.podium.core.model.Explicitness
import app.podium.core.model.VariantNote
import app.podium.core.model.VersionTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackNormalizerTest {

    private fun analyze(title: String, artist: String = "") = TrackNormalizer.analyze(title, artist)

    @Test
    fun `version, variant and packaging segments are classified, not stripped`() {
        val a = analyze("Yellow (Live at Glastonbury) [Remastered 2011] (From \"The Film\")")
        assertEquals("yellow", a.version.identityKey)
        assertEquals(setOf(VersionTag.LIVE), a.version.tags)
        assertEquals(setOf(VariantNote.REMASTER), a.version.variants)
        assertEquals(listOf("From \"The Film\""), a.version.packaging)
    }

    @Test
    fun `leading bracket is part of the identity`() {
        assertEquals("i can t get no satisfaction", analyze("(I Can't Get No) Satisfaction").version.identityKey)
    }

    @Test
    fun `dash suffixes are classified, unknown ones stay in the name`() {
        assertEquals(setOf(VersionTag.LIVE), analyze("Song - Live").version.tags)
        assertEquals(setOf(VariantNote.REMASTER), analyze("Song - 2009 Remaster").version.variants)
        assertEquals("song the beginning", analyze("Song - The Beginning").version.identityKey)
    }

    @Test
    fun `part numbers are identity`() {
        assertEquals("suite part 2", analyze("Suite (Part 2)").version.identityKey)
        assertEquals("suite part 2", analyze("Suite (Pt. 2)").version.identityKey)
    }

    @Test
    fun `featuring credits are extracted from the title`() {
        val a = analyze("Stay (feat. Mikky Ekko)")
        assertEquals("stay", a.version.identityKey)
        assertEquals(listOf("Mikky Ekko"), a.featuredArtists)
        assertEquals(listOf("A", "B"), analyze("Song feat. A & B").featuredArtists)
    }

    @Test
    fun `explicitness markers become hints`() {
        assertEquals(Explicitness.CLEAN, analyze("Song (Clean)").explicitnessHint)
        assertEquals(Explicitness.EXPLICIT, analyze("Song [Explicit]").explicitnessHint)
    }

    @Test
    fun `mix qualifiers distinguish remixes from ordinary mixes`() {
        assertEquals(setOf(VersionTag.REMIX), analyze("Song (Club Mix)").version.tags)
        assertTrue(analyze("Song (Original Mix)").version.tags.isEmpty())
        assertEquals(setOf(VersionTag.RADIO_EDIT), analyze("Song (Radio Mix)").version.tags)
        assertEquals(setOf(VersionTag.EXTENDED), analyze("Song (Extended Mix)").version.tags)
    }

    @Test
    fun `speed and mood edits are versions`() {
        assertEquals(setOf(VersionTag.SPED_UP), analyze("Song (Sped Up)").version.tags)
        assertEquals(setOf(VersionTag.SLOWED, VersionTag.REVERB), analyze("Song (Slowed + Reverb)").version.tags)
        assertEquals(setOf(VersionTag.NIGHTCORE), analyze("Song [Nightcore]").version.tags)
    }

    @Test
    fun `release-type words alone are packaging`() {
        assertTrue(analyze("Song (Single)").version.tags.isEmpty())
        assertEquals(setOf(VersionTag.SINGLE_EDIT), analyze("Song (Single Version)").version.tags)
    }

    @Test
    fun `latin diacritics fold, other scripts are preserved`() {
        assertEquals("beyonce", TrackNormalizer.identityKey("Beyoncé"))
        assertEquals("øneheart", TrackNormalizer.identityKey("Øneheart"))
        val devanagari = "तुम ही हो"
        assertEquals(devanagari, TrackNormalizer.identityKey(devanagari))
    }

    @Test
    fun `quotes and dashes are unified`() {
        assertEquals(analyze("Don't Stop").version.identityKey, analyze("Don’t Stop").version.identityKey)
        assertEquals(setOf(VersionTag.LIVE), analyze("Song – Live").version.tags)
    }

    @Test
    fun `artist forms keep band names whole`() {
        val forms = ArtistNames.comparableForms("Simon & Garfunkel")
        assertTrue("simon and garfunkel" in forms)
        assertTrue("paul simon" !in forms)
    }
}
