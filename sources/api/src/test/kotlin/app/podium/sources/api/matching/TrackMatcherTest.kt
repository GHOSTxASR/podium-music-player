package app.podium.sources.api.matching

import app.podium.core.model.Explicitness
import app.podium.sources.testing.track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TrackMatcherTest {

    private val matcher = TrackMatcher()

    private fun tier(a: app.podium.core.model.Track, b: app.podium.core.model.Track): MatchTier {
        val ab = matcher.match(a, b)
        val ba = matcher.match(b, a)
        assertEquals(ab.tier, ba.tier, "matching must be symmetric: ${ab.explain()} / ${ba.explain()}")
        return ab.tier
    }

    // 1. Same song, different source --------------------------------------------------------------

    @Test
    fun `same song on two sources is EXACT`() {
        val a = track("Blinding Lights", "The Weeknd", source = "a", album = "After Hours", durationSec = 200.0)
        val b = track("Blinding Lights", "The Weeknd", source = "b", album = "After Hours", durationSec = 200.4)
        assertEquals(MatchTier.EXACT, tier(a, b))
        assertTrue(matcher.match(a, b).isSafeForFallback)
    }

    @Test
    fun `packaging such as a soundtrack attribution does not change identity`() {
        val a = track("Paniyon Sa (From \"Satyamev Jayate\")", "Atif Aslam", source = "a", durationSec = 239.0)
        val b = track("Paniyon Sa", "Atif Aslam", source = "b", durationSec = 239.5)
        assertEquals(MatchTier.EXACT, tier(a, b))
    }

    @Test
    fun `official audio is packaging, official video is a different version`() {
        val album = track("Levitating", "Dua Lipa", source = "a", durationSec = 203.0)
        val audio = track("Levitating (Official Audio)", "Dua Lipa", source = "b", durationSec = 203.0)
        val video = track("Levitating (Official Music Video)", "Dua Lipa", source = "b", key = "v", durationSec = 203.0)
        assertEquals(MatchTier.EXACT, tier(album, audio))
        assertEquals(MatchTier.NO_MATCH, tier(album, video))
    }

    @Test
    fun `artist - title upload shape matches the plain title`() {
        val a = track("Daft Punk - Get Lucky", "Daft Punk", source = "a", durationSec = 248.0)
        val b = track("Get Lucky", "Daft Punk", source = "b", durationSec = 248.0)
        assertEquals(MatchTier.EXACT, tier(a, b))
    }

    // 2. Same title, different recording -------------------------------------------------------------

    @Test
    fun `same title by different artists is not a match`() {
        val cohen = track("Hallelujah", "Leonard Cohen", source = "a", durationSec = 279.0)
        val buckley = track("Hallelujah", "Jeff Buckley", source = "b", durationSec = 279.0)
        assertEquals(MatchTier.NO_MATCH, tier(cohen, buckley))
    }

    @Test
    fun `re-recordings are different recordings`() {
        val original = track("Love Story", "Taylor Swift", source = "a", durationSec = 235.0)
        val rerecord = track("Love Story (Taylor's Version)", "Taylor Swift", source = "b", durationSec = 235.0)
        assertEquals(MatchTier.NO_MATCH, tier(original, rerecord))
    }

    @Test
    fun `part one is not part two`() {
        val one = track("Bohemian Suite (Part 1)", "Artist", source = "a")
        val two = track("Bohemian Suite (Part 2)", "Artist", source = "b")
        assertEquals(MatchTier.NO_MATCH, tier(one, two))
    }

    // 3. Live vs studio ----------------------------------------------------------------------------------

    @Test
    fun `live is never studio, in either direction`() {
        val studio = track("Yellow", "Coldplay", source = "a", durationSec = 269.0)
        val live = track("Yellow (Live at Glastonbury)", "Coldplay", source = "b", durationSec = 269.0)
        val dashLive = track("Yellow - Live", "Coldplay", source = "b", key = "dash", durationSec = 269.0)
        assertEquals(MatchTier.NO_MATCH, tier(studio, live))
        assertEquals(MatchTier.NO_MATCH, tier(studio, dashLive))
    }

    @Test
    fun `two different live recordings are not exact`() {
        val a = track("Yellow (Live at Glastonbury)", "Coldplay", source = "a", durationSec = 270.0)
        val b = track("Yellow (Live in Buenos Aires)", "Coldplay", source = "b", durationSec = 270.0)
        assertFalse(matcher.match(a, b).isSafeForFallback)
    }

    // 4. Remix vs original -------------------------------------------------------------------------------

    @Test
    fun `remix is never the original`() {
        val original = track("Midnight City", "M83", source = "a", durationSec = 243.0)
        val remix = track("Midnight City (Eric Prydz Remix)", "M83", source = "b", durationSec = 243.0)
        assertEquals(MatchTier.NO_MATCH, tier(original, remix))
    }

    @Test
    fun `different remixes of the same song are not exact`() {
        val a = track("Midnight City (Eric Prydz Remix)", "M83", source = "a", durationSec = 300.0)
        val b = track("Midnight City (Trentemøller Remix)", "M83", source = "b", durationSec = 300.0)
        val result = matcher.match(a, b)
        assertFalse(result.isSafeForFallback, result.explain())
    }

    @Test
    fun `extended mix is a different version but original mix is the same`() {
        val plain = track("Strobe", "deadmau5", source = "a", durationSec = 637.0)
        val originalMix = track("Strobe (Original Mix)", "deadmau5", source = "b", durationSec = 637.0)
        val extended = track("Strobe (Extended Mix)", "deadmau5", source = "b", key = "ext", durationSec = 637.0)
        assertEquals(MatchTier.EXACT, tier(plain, originalMix))
        assertEquals(MatchTier.NO_MATCH, tier(plain, extended))
    }

    // 5. Explicit vs clean -------------------------------------------------------------------------------

    @Test
    fun `explicit and clean editions never match`() {
        val explicit = track("HUMBLE.", "Kendrick Lamar", source = "a", durationSec = 177.0, explicitness = Explicitness.EXPLICIT)
        val clean = track("HUMBLE.", "Kendrick Lamar", source = "b", durationSec = 177.0, explicitness = Explicitness.CLEAN)
        val cleanByTitle = track("HUMBLE. (Clean)", "Kendrick Lamar", source = "b", key = "c", durationSec = 177.0)
        assertEquals(MatchTier.NO_MATCH, tier(explicit, clean))
        assertEquals(MatchTier.NO_MATCH, tier(explicit, cleanByTitle))
    }

    @Test
    fun `explicit and clean conflict even with the same ISRC`() {
        val explicit = track("Song", "A", source = "a", isrc = "USAAA2400001", explicitness = Explicitness.EXPLICIT)
        val clean = track("Song", "A", source = "b", isrc = "USAAA2400001", explicitness = Explicitness.CLEAN)
        assertEquals(MatchTier.NO_MATCH, tier(explicit, clean))
    }

    // 6. Album vs single ---------------------------------------------------------------------------------

    @Test
    fun `same recording on an album and on a single is strong, not exact, without identifiers`() {
        val albumCut = track("Shape of You", "Ed Sheeran", source = "a", album = "Divide", durationSec = 233.7)
        val single = track("Shape of You", "Ed Sheeran", source = "b", album = "Shape of You", durationSec = 233.7)
        assertEquals(MatchTier.STRONG, tier(albumCut, single))
    }

    @Test
    fun `single and radio edits are different versions`() {
        val album = track("Song", "Artist", source = "a", durationSec = 250.0)
        val radio = track("Song (Radio Edit)", "Artist", source = "b", durationSec = 249.0)
        val singleVersion = track("Song (Single Version)", "Artist", source = "b", key = "s", durationSec = 250.0)
        val shortEdit = track("Song", "Artist", source = "b", key = "short", durationSec = 210.0)
        assertEquals(MatchTier.NO_MATCH, tier(album, radio))
        assertEquals(MatchTier.NO_MATCH, tier(album, singleVersion))
        assertEquals(MatchTier.NO_MATCH, tier(album, shortEdit), "a 40 s shorter cut is not the same recording")
    }

    @Test
    fun `album version marker is the ordinary release`() {
        val a = track("Song (Album Version)", "Artist", source = "a", durationSec = 250.0)
        val b = track("Song", "Artist", source = "b", durationSec = 250.0)
        assertEquals(MatchTier.EXACT, tier(a, b))
    }

    // 7–9. Acoustic, sped-up, slowed --------------------------------------------------------------

    @Test
    fun `acoustic is not the original`() {
        val a = track("Photograph", "Ed Sheeran", source = "a", durationSec = 258.0)
        val b = track("Photograph (Acoustic)", "Ed Sheeran", source = "b", durationSec = 258.0)
        assertEquals(MatchTier.NO_MATCH, tier(a, b))
    }

    @Test
    fun `sped up is not the original`() {
        val a = track("Snowfall", "Øneheart", source = "a", durationSec = 120.0)
        val b = track("Snowfall (Sped Up)", "Øneheart", source = "b", durationSec = 120.0)
        assertEquals(MatchTier.NO_MATCH, tier(a, b))
    }

    @Test
    fun `slowed and reverb is not the original`() {
        val a = track("Snowfall", "Øneheart", source = "a", durationSec = 120.0)
        val b = track("Snowfall (Slowed + Reverb)", "Øneheart", source = "b", durationSec = 120.0)
        val c = track("Snowfall - Slowed", "Øneheart", source = "b", key = "c", durationSec = 120.0)
        assertEquals(MatchTier.NO_MATCH, tier(a, b))
        assertEquals(MatchTier.NO_MATCH, tier(a, c))
    }

    // 10. Different artist, same title ----------------------------------------------------------------

    @Test
    fun `covers by other artists are rejected`() {
        val nin = track("Hurt", "Nine Inch Nails", source = "a", durationSec = 373.0)
        val cash = track("Hurt", "Johnny Cash", source = "b", durationSec = 218.0)
        assertEquals(MatchTier.NO_MATCH, tier(nin, cash))
    }

    @Test
    fun `band names containing ampersands are not split into false matches`() {
        val duo = track("The Boxer", "Simon & Garfunkel", source = "a", durationSec = 308.0)
        val solo = track("The Boxer", "Paul Simon", source = "b", durationSec = 308.0)
        val sameDuo = track("The Boxer", "Simon and Garfunkel", source = "b", key = "duo", durationSec = 308.0)
        assertEquals(MatchTier.NO_MATCH, tier(duo, solo))
        assertEquals(MatchTier.EXACT, tier(duo, sameDuo))
    }

    // 11. Featuring artists ------------------------------------------------------------------------

    @Test
    fun `featuring in the title equals a structured featured credit`() {
        val inTitle = track("Stay (feat. Mikky Ekko)", "Rihanna", source = "a", durationSec = 240.0)
        val structured = track("Stay", "Rihanna", source = "b", durationSec = 240.0, featured = listOf("Mikky Ekko"))
        assertEquals(MatchTier.EXACT, tier(inTitle, structured))
    }

    @Test
    fun `different featured artists are different recordings`() {
        val a = track("Despacito (feat. Daddy Yankee)", "Luis Fonsi", source = "a", durationSec = 228.0)
        val b = track("Despacito (feat. Justin Bieber)", "Luis Fonsi", source = "b", durationSec = 229.0)
        assertEquals(MatchTier.NO_MATCH, tier(a, b))
    }

    @Test
    fun `a featured credit missing on one side is not exact`() {
        val a = track("Stay (feat. Mikky Ekko)", "Rihanna", source = "a", durationSec = 240.0)
        val b = track("Stay", "Rihanna", source = "b", durationSec = 240.0)
        assertEquals(MatchTier.STRONG, tier(a, b))
    }

    // 12. Duration -----------------------------------------------------------------------------------------

    @Test
    fun `duration differences step tiers down and eventually veto`() {
        val base = track("Song", "Artist", source = "a", durationSec = 200.0)
        fun other(sec: Double) = track("Song", "Artist", source = "b", key = "$sec", durationSec = sec)
        assertEquals(MatchTier.EXACT, tier(base, other(201.0)))
        assertEquals(MatchTier.STRONG, tier(base, other(204.0)))
        assertEquals(MatchTier.POSSIBLE, tier(base, other(208.0)))
        assertEquals(MatchTier.NO_MATCH, tier(base, other(215.0)))
    }

    @Test
    fun `duration alone never establishes identity`() {
        val a = track("One Song", "Artist A", source = "a", durationSec = 200.0)
        assertEquals(MatchTier.NO_MATCH, tier(a, track("Another Song", "Artist A", source = "b", durationSec = 200.0)))
        assertEquals(MatchTier.NO_MATCH, tier(a, track("One Song", "Artist B", source = "b", durationSec = 200.0)))
    }

    @Test
    fun `unknown duration caps at possible`() {
        val a = track("Song", "Artist", source = "a", durationSec = null)
        val b = track("Song", "Artist", source = "b", durationSec = 200.0)
        assertEquals(MatchTier.POSSIBLE, tier(a, b))
    }

    // 13. ISRC match -------------------------------------------------------------------------------------

    @Test
    fun `equal ISRC is exact, whatever the formatting`() {
        val a = track("Despacito", "Luis Fonsi", source = "a", isrc = "US-UM7-17-00001", durationSec = 229.0)
        val b = track("Despacito", "Luis Fonsi", source = "b", isrc = "usum71700001", durationSec = 229.0, album = "Vida")
        val result = matcher.match(a, b)
        assertEquals(MatchTier.EXACT, result.tier, result.explain())
        assertTrue(result.evidence.any { it is Evidence.IsrcEqual })
    }

    @Test
    fun `equal ISRC with a localized title is strong, not exact`() {
        val a = track("Song Title", "Artist", source = "a", isrc = "GBAAA2400001")
        val b = track("Titre de la chanson", "Artist", source = "b", isrc = "GBAAA2400001")
        assertEquals(MatchTier.STRONG, tier(a, b))
    }

    @Test
    fun `equal ISRC does not override a version marker`() {
        val a = track("Song", "Artist", source = "a", isrc = "GBAAA2400001")
        val b = track("Song (Live)", "Artist", source = "b", isrc = "GBAAA2400001")
        assertFalse(matcher.match(a, b).isSafeForFallback)
    }

    @Test
    fun `remaster on one side is exact only with an identifier`() {
        val a = track("Here Comes the Sun", "The Beatles", source = "a", durationSec = 185.0)
        val b = track("Here Comes the Sun - Remastered 2009", "The Beatles", source = "b", durationSec = 185.0)
        assertEquals(MatchTier.STRONG, tier(a, b))
        val ai = track("Here Comes the Sun", "The Beatles", source = "a", key = "i", isrc = "GBAYE0601690", durationSec = 185.0)
        val bi = track("Here Comes the Sun - Remastered 2009", "The Beatles", source = "b", key = "i", isrc = "GBAYE0601690", durationSec = 185.0)
        assertEquals(MatchTier.EXACT, tier(ai, bi))
    }

    // 14. ISRC conflict ---------------------------------------------------------------------------------

    @Test
    fun `different ISRCs cap at possible even when everything else agrees`() {
        val a = track("Song", "Artist", source = "a", album = "Album", isrc = "USAAA2400001", durationSec = 200.0)
        val b = track("Song", "Artist", source = "b", album = "Album", isrc = "USBBB2400002", durationSec = 200.0)
        val result = matcher.match(a, b)
        assertEquals(MatchTier.POSSIBLE, result.tier, result.explain())
        assertTrue(result.evidence.any { it is Evidence.IsrcConflict })
    }

    // Ambiguity ------------------------------------------------------------------------------------------

    @Test
    fun `several equally good candidates are ambiguous, never guessed`() {
        val target = track("Song", "Artist", source = "a", durationSec = 200.0)
        val onAlbum = track("Song", "Artist", source = "b", key = "1", album = "First Album", durationSec = 200.0)
        val onCompilation = track("Song", "Artist", source = "b", key = "2", album = "Greatest Hits", durationSec = 200.0)
        val best = assertNotNull(matcher.best(target, listOf(onAlbum, onCompilation)))
        assertFalse(best.second.isSafeForFallback, best.second.explain())
    }

    @Test
    fun `best picks the single exact candidate`() {
        val target = track("Song", "Artist", source = "a", durationSec = 200.0)
        val live = track("Song (Live)", "Artist", source = "b", key = "live", durationSec = 200.0)
        val exact = track("Song", "Artist", source = "b", key = "studio", durationSec = 200.5)
        val best = assertNotNull(matcher.best(target, listOf(live, exact)))
        assertEquals(exact.id, best.first.id)
        assertEquals(MatchTier.EXACT, best.second.tier)
    }

    @Test
    fun `results explain themselves`() {
        val result = matcher.match(
            track("Song", "Artist", source = "a"),
            track("Song (Live)", "Artist", source = "b"),
        )
        assertTrue(result.explain().contains("Different versions"), result.explain())
    }
}
