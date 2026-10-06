package app.podium.core.lyrics

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.net.UnknownHostException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LrcParserTest {

    @Test
    fun `timestamps of every common form become milliseconds`() {
        val lines = LrcParser.parse(
            """
            [ar:Someone]
            [ti:Something]
            [00:12.50] line one
            [00:16.2]line two
            [00:19.805] line three
            [01:02:03.04] an hour in
            [00:20] whole seconds
            [00:21:50] colon fraction
            """.trimIndent(),
        )
        assertEquals(
            listOf(12_500L to "line one", 16_200L to "line two", 19_805L to "line three", 20_000L to "whole seconds", 21_500L to "colon fraction", 3_723_040L to "an hour in"),
            lines.map { it.startMs to it.text },
        )
    }

    @Test
    fun `a line with several stamps repeats, in time order`() {
        val lines = LrcParser.parse("[00:10.00][00:30.00] chorus\n[00:20.00] verse")
        assertEquals(listOf(10_000L, 20_000L, 30_000L), lines.map { it.startMs })
        assertEquals(listOf("chorus", "verse", "chorus"), lines.map { it.text })
    }

    @Test
    fun `the offset tag moves every line, never before zero`() {
        val lines = LrcParser.parse("[offset:+500]\n[00:00.20] early\n[00:10.00] later")
        assertEquals(listOf(0L, 9_500L), lines.map { it.startMs })
        assertEquals(listOf(10_250L), LrcParser.parse("[offset:-250]\n[00:10.00] x").map { it.startMs })
    }

    @Test
    fun `malformed, empty and word-stamped lines are handled without guessing`() {
        assertTrue(LrcParser.parse(null).isEmpty())
        assertTrue(LrcParser.parse("").isEmpty())
        assertTrue(LrcParser.parse("no stamps here\n[aa:bb] nope\n[00:75.00] bad seconds").isEmpty())
        assertNull(LrcParser.synced("[00:01.00]\n[00:02.00]   "))
        val words = LrcParser.parse("[00:01.00] <00:01.00>Hello <00:01.50>there")
        assertEquals("Hello there", words.single().text)
        assertEquals(listOf(LyricsWord(1_000, "Hello"), LyricsWord(1_500, "there")), words.single().words)
        // Gaps are kept once, not repeated.
        val gaps = LrcParser.parse("[00:01.00] a\n[00:02.00]\n[00:03.00]\n[00:04.00] b")
        assertEquals(listOf("a", "", "b"), gaps.map { it.text })
    }

    @Test
    fun `plain lyrics keep their lines without blank ends`() {
        val plain = LrcParser.plain("\n\nfirst\n\nsecond\n\n")!!
        assertEquals(listOf("first", "", "second"), plain.lines)
        assertNull(LrcParser.plain("   \n  "))
    }
}

class WordTimingTest {

    @Test
    fun `enhanced LRC word stamps become the line's words, syllable stamps included`() {
        val line = LrcParser.parse("[offset:+200]\n[00:12.00]<00:12.00>Hel<00:12.30>lo <00:12.60>world, <00:13.40>again").single()
        assertEquals("Hello world, again", line.text)
        // The offset moves the words with the line; a word takes its first syllable's stamp.
        assertEquals(listOf(11_800L, 12_400L, 13_200L), line.words.map { it.startMs })
        assertEquals(listOf("Hello", "world,", "again"), line.words.map { it.text })
        assertFalse(WordTiming.isEstimated(line))
        // A repeated line repeats its words' timing from each of its starts.
        val chorus = LrcParser.parse("[00:10.00][01:10.00]<00:10.00>la <00:10.50>la")
        assertEquals(listOf(70_000L, 70_500L), chorus[1].words.map { it.startMs })
        // Some words without stamps: the line's words can't be trusted, so none are kept.
        assertTrue(LrcParser.parse("[00:01.00] untimed <00:01.50>half").single().words.isEmpty())
    }

    @Test
    fun `words appear inside the line's real window, the first exactly on time`() {
        val line = LyricsLine(10_000, "I wish that I could be like the cool kids")
        val starts = WordTiming.starts(line, nextStartMs = 14_000)
        assertEquals(10, starts.size)
        assertEquals(10_000L, starts.first())
        assertEquals(starts.sorted(), starts)
        // All in before the next line (85 % of the gap), none before the line.
        assertTrue(starts.last() < 14_000 * 1L && starts.last() <= 10_000 + (4_000 * 0.85).toLong())
        // A longer word takes longer than a short one.
        val gapAfterI = starts[1] - starts[0]
        val gapAfterCould = starts[5] - starts[4]
        assertTrue(gapAfterI <= gapAfterCould)
    }

    @Test
    fun `an instrumental break after a line doesn't stretch its words`() {
        val line = LyricsLine(5_000, "hold on")
        val starts = WordTiming.starts(line, nextStartMs = 60_000)
        // Two syllables at most 380 ms each: "on" comes within a second, not across the break.
        assertTrue(starts[1] - starts[0] <= WordTiming.MAX_MS_PER_SYLLABLE)
        // An end time from the provider bounds the line instead of the next line's start.
        val ended = WordTiming.starts(LyricsLine(5_000, "a b c d", endMs = 5_400), nextStartMs = 60_000)
        assertTrue(ended.last() < 5_400)
    }

    @Test
    fun `provider word times are used in order and never before the line`() {
        val line = LyricsLine(1_000, "one two three", words = listOf(LyricsWord(900, "one"), LyricsWord(1_600, "two"), LyricsWord(1_500, "three")))
        assertEquals(listOf(1_000L, 1_600L, 1_600L), WordTiming.starts(line, 9_000))
        assertEquals(0, WordTiming.shown(WordTiming.starts(line, 9_000), 999))
        assertEquals(1, WordTiming.shown(WordTiming.starts(line, 9_000), 1_000))
        assertEquals(1_600L, WordTiming.nextWordMs(WordTiming.starts(line, 9_000), 1_200))
        assertNull(WordTiming.nextWordMs(WordTiming.starts(line, 9_000), 2_000))
    }

    @Test
    fun `syllables are counted roughly but sensibly`() {
        assertEquals(1, WordTiming.syllables("I"))
        assertEquals(1, WordTiming.syllables("love"))
        assertEquals(2, WordTiming.syllables("little"))
        assertEquals(3, WordTiming.syllables("beautiful"))
        // A guess, not a dictionary: close is enough to pace a line.
        assertTrue(WordTiming.syllables("everything") in 3..4)
        assertEquals(1, WordTiming.syllables("♪"))
        assertEquals(2, WordTiming.syllables("Jóga"))
    }

    @Test
    fun `LRCLIB's lyricsfile adds end times and words to the LRC`() {
        val file = """
            version: '1.0'
            metadata:
              title: Song
            lines:
            - text: Yeah
              start_ms: 13130
              end_ms: 16560
            - text: 'It''s a ♪ line'
              start_ms: 27160
              end_ms: 29960
              words:
              - text: It's
                start_ms: 27160
                end_ms: 27400
              - text: a
                start_ms: 27400
              - text: ♪
                start_ms: 27900
              - text: line
                start_ms: 28300
            other: x
        """.trimIndent()
        val parsed = LyricsFile.parse(file)
        assertEquals(listOf("Yeah", "It's a ♪ line"), parsed.map { it.text })
        assertEquals(16_560L, parsed[0].endMs)
        assertEquals(listOf(27_160L, 27_400L, 27_900L, 28_300L), parsed[1].words.map { it.startMs })
        val lrc = LrcParser.parse("[00:13.13] Yeah\n[00:27.16] It's a ♪ line\n[00:31.00] next")
        val enriched = LyricsFile.enrich(lrc, parsed)
        assertEquals(16_560L, enriched[0].endMs)
        assertEquals(4, enriched[1].words.size)
        assertNull(enriched[2].endMs)
        assertTrue(LyricsFile.parse("not: yaml: at all").isEmpty())
        assertTrue(LyricsFile.parse(null).isEmpty())
    }
}

class LyricsTimingTest {
    private val lines = listOf(LyricsLine(12_500, "one"), LyricsLine(16_200, "two"), LyricsLine(19_800, "three"))

    @Test
    fun `the active line follows the position, boundaries included`() {
        assertEquals(-1, LyricsTiming.activeIndex(lines, 0))
        assertEquals(-1, LyricsTiming.activeIndex(lines, 12_499))
        assertEquals(0, LyricsTiming.activeIndex(lines, 12_500))
        assertEquals(0, LyricsTiming.activeIndex(lines, 16_199))
        assertEquals(1, LyricsTiming.activeIndex(lines, 16_200))
        assertEquals(2, LyricsTiming.activeIndex(lines, 600_000))
        assertEquals(-1, LyricsTiming.activeIndex(emptyList(), 5_000))
    }

    @Test
    fun `before the first line the first line waits on screen`() {
        assertEquals(0, LyricsTiming.displayIndex(lines, 0))
        assertEquals(0, LyricsTiming.displayIndex(lines, 5_000))
        assertEquals(0, LyricsTiming.displayIndex(lines, 12_500))
        assertEquals(1, LyricsTiming.displayIndex(lines, 16_300))
    }

    @Test
    fun `the next change is the next line's start, and seeking back moves it back`() {
        assertEquals(12_500L, LyricsTiming.nextChangeMs(lines, 0))
        assertEquals(19_800L, LyricsTiming.nextChangeMs(lines, 17_000))
        assertNull(LyricsTiming.nextChangeMs(lines, 19_800))
        assertEquals(16_200L, LyricsTiming.nextChangeMs(lines, 13_000))
    }
}

class LyricsMatcherTest {
    private val request = LyricsRequest("local|1", "Coastline", "Northern Sines", "Woodland", 200_000)

    private fun c(title: String, artist: String = "Northern Sines", duration: Long? = 200_000, album: String? = null) =
        LyricsMatcher.Candidate(title, artist, album, duration)

    @Test
    fun `the same recording matches through packaging and featured credits`() {
        assertTrue(LyricsMatcher.matches(request, c("Coastline")))
        assertTrue(LyricsMatcher.matches(request, c("Coastline (Remastered 2021)")))
        assertTrue(LyricsMatcher.matches(request, c("coastline", "northern sines")))
        assertTrue(LyricsMatcher.matches(request, c("Coastline", "Northern Sines & Friends", duration = 202_500)))
        assertTrue(LyricsMatcher.matches(request, c("Coastline", duration = null)))
    }

    @Test
    fun `other versions, other artists and other lengths never match`() {
        assertFalse(LyricsMatcher.matches(request, c("Coastline (Live)")))
        assertFalse(LyricsMatcher.matches(request, c("Coastline - Acoustic")))
        assertFalse(LyricsMatcher.matches(request, c("Coastline (Remix)")))
        assertFalse(LyricsMatcher.matches(request, c("Coastlines")))
        assertFalse(LyricsMatcher.matches(request, c("Coastline", "Someone Else")))
        assertFalse(LyricsMatcher.matches(request, c("Coastline", duration = 260_000)))
        val live = request.copy(title = "Coastline (Live)")
        assertFalse(LyricsMatcher.matches(live, c("Coastline")))
        assertTrue(LyricsMatcher.matches(live, c("Coastline (Live)")))
    }

    @Test
    fun `the closest length wins among matches`() {
        val best = LyricsMatcher.best(request, listOf(c("Coastline", duration = 202_000), c("Coastline", duration = 200_400), c("Coastline (Live)", duration = 200_000))) { it }
        assertEquals(200_400L, best?.durationMs)
    }
}

class LrclibProviderTest {
    private val request = LyricsRequest("ytmusic|abc", "Coastline", "Northern Sines", "Woodland", 200_000)
    private val synced = """{"id":1,"trackName":"Coastline","artistName":"Northern Sines","albumName":"Woodland","duration":200.0,"instrumental":false,"plainLyrics":"one\ntwo","syncedLyrics":"[00:01.00] one\n[00:02.00] two"}"""

    private class Script(val answers: List<() -> Pair<Int, String>>) : LyricsHttp {
        val urls = mutableListOf<String>()
        override suspend fun get(url: String): Pair<Int, String> {
            urls += url
            return answers[urls.size - 1]()
        }
    }

    @Test
    fun `the lyricsfile's end times come with the synced lyrics`() = runTest {
        val withFile = synced.dropLast(1) + ""","lyricsfile":"lines:\n- text: one\n  start_ms: 1000\n  end_ms: 1700\n- text: two\n  start_ms: 2000\n  end_ms: 2900\n"}"""
        val lyrics = assertIs<Lyrics.Synced>(assertIs<ProviderAnswer.Found>(LrclibProvider(Script(listOf({ 200 to withFile }))).lookup(request)).lyrics)
        assertEquals(listOf(1_700L, 2_900L), lyrics.lines.map { it.endMs })
    }

    @Test
    fun `an exact match gives synced lyrics, asked by metadata only`() = runTest {
        val http = Script(listOf({ 200 to synced }))
        val answer = LrclibProvider(http).lookup(request)
        val lyrics = assertIs<Lyrics.Synced>(assertIs<ProviderAnswer.Found>(answer).lyrics)
        assertEquals(listOf("one", "two"), lyrics.lines.map { it.text })
        val url = http.urls.single()
        assertTrue(url.startsWith("https://lrclib.net/api/get?track_name=Coastline&artist_name=Northern+Sines&album_name=Woodland&duration=200"))
        assertFalse("abc" in url)
    }

    @Test
    fun `without an exact match, search finds only a confident candidate`() = runTest {
        val wrong = """[{"trackName":"Coastline (Live)","artistName":"Northern Sines","duration":200.0,"plainLyrics":"live words"}]"""
        assertEquals(ProviderAnswer.NotFound, LrclibProvider(Script(listOf({ 404 to "{}" }, { 200 to wrong }))).lookup(request))
        val right = """[{"trackName":"Coastline (Live)","artistName":"Northern Sines","duration":200.0,"plainLyrics":"live words"},{"trackName":"Coastline","artistName":"Northern Sines","duration":201.0,"plainLyrics":"studio words"}]"""
        val found = LrclibProvider(Script(listOf({ 404 to "{}" }, { 200 to right }))).lookup(request)
        assertEquals(listOf("studio words"), (assertIs<ProviderAnswer.Found>(found).lyrics as Lyrics.Plain).lines)
    }

    @Test
    fun `an exact answer for another version isn't believed`() = runTest {
        val other = synced.replace("\"Coastline\"", "\"Coastline (Acoustic)\"")
        assertEquals(ProviderAnswer.NotFound, LrclibProvider(Script(listOf({ 200 to other }, { 200 to "[]" }))).lookup(request))
    }

    @Test
    fun `instrumental, failures and limits are classified`() = runTest {
        val instrumental = """{"trackName":"Coastline","artistName":"Northern Sines","duration":200.0,"instrumental":true}"""
        assertEquals(ProviderAnswer.Found(Lyrics.Instrumental), LrclibProvider(Script(listOf({ 200 to instrumental }))).lookup(request))
        assertEquals(ProviderAnswer.RateLimited, LrclibProvider(Script(listOf({ 429 to "" }))).lookup(request))
        assertEquals(ProviderAnswer.Failed, LrclibProvider(Script(listOf({ 500 to "" }))).lookup(request))
        assertEquals(ProviderAnswer.Failed, LrclibProvider(Script(listOf({ 200 to "not json" }))).lookup(request))
        assertEquals(ProviderAnswer.Failed, LrclibProvider(Script(listOf({ throw IOException("SocketTimeoutException") }))).lookup(request))
        assertEquals(ProviderAnswer.Offline, LrclibProvider(Script(listOf({ throw UnknownHostException() }))).lookup(request))
        assertEquals(ProviderAnswer.NotFound, LrclibProvider(Script(emptyList())).lookup(request.copy(title = " ")))
    }
}

class LyricsRepositoryTest {
    private val request = LyricsRequest("local|1", "Coastline", "Northern Sines", "Woodland", 200_000)

    private class FakeProvider(var answer: ProviderAnswer) : LyricsProvider {
        var calls = 0
        override val id = "fake"
        override val attribution = LyricsAttribution("Fake", "https://example.com")
        override suspend fun lookup(request: LyricsRequest): ProviderAnswer {
            calls++
            delay(10)
            return answer
        }
    }

    private val found = ProviderAnswer.Found(Lyrics.Plain(listOf("words")))

    @Test
    fun `nothing is sent without consent`() = runTest {
        val provider = FakeProvider(found)
        val repo = LyricsRepository(provider, InMemoryLyricsCache(), consent = { false })
        assertEquals(LyricsResult.NeedsConsent, repo.lyrics(request))
        assertEquals(0, provider.calls)
    }

    @Test
    fun `answers are kept, misses for a shorter time, failures not at all`() = runTest {
        var now = 0L
        val provider = FakeProvider(found)
        val repo = LyricsRepository(provider, InMemoryLyricsCache(), consent = { true }, now = { now }, foundTtlMs = 1_000, missTtlMs = 100)
        assertIs<LyricsResult.Found>(repo.lyrics(request))
        assertIs<LyricsResult.Found>(repo.lyrics(request))
        assertEquals(1, provider.calls)
        now = 2_000
        provider.answer = ProviderAnswer.NotFound
        assertEquals(LyricsResult.NotFound, repo.lyrics(request))
        assertEquals(LyricsResult.NotFound, repo.lyrics(request))
        assertEquals(2, provider.calls)
        now = 2_200
        provider.answer = ProviderAnswer.Failed
        assertEquals(LyricsResult.Failed, repo.lyrics(request))
        assertEquals(LyricsResult.Failed, repo.lyrics(request))
        assertEquals(4, provider.calls)
        provider.answer = ProviderAnswer.Offline
        assertEquals(LyricsResult.Offline, repo.lyrics(request))
    }

    @Test
    fun `two screens asking at once make one request`() = runTest {
        val provider = FakeProvider(found)
        val repo = LyricsRepository(provider, InMemoryLyricsCache(), consent = { true })
        val results = (1..3).map { async { repo.lyrics(request) } }.awaitAll()
        assertTrue(results.all { it is LyricsResult.Found })
        assertEquals(1, provider.calls)
    }

    @Test
    fun `keys change with the song and the provider, and carry no metadata`() {
        val repo = LyricsRepository(FakeProvider(found), InMemoryLyricsCache(), consent = { true })
        val a = repo.keyOf(request)
        assertTrue(a.startsWith("fake-"))
        assertFalse("coastline" in a.lowercase())
        assertTrue(a != repo.keyOf(request.copy(title = "Other")))
        assertTrue(a != repo.keyOf(request.copy(durationMs = 260_000)))
        assertEquals(a, repo.keyOf(request.copy(durationMs = 200_500)))
    }

    @Test
    fun `the file cache round-trips every kind and survives damage`() {
        val dir = Files.createTempDirectory("lyrics").toFile()
        val cache = FileLyricsCache(dir)
        val synced = Lyrics.Synced(
            listOf(
                LyricsLine(1_000, "one two", endMs = 1_800, words = listOf(LyricsWord(1_000, "one"), LyricsWord(1_400, "two"))),
                LyricsLine(2_000, ""),
            ),
        )
        cache.write("fake-0123456789abcdef0123456789abcdef", LyricsCache.Entry(5, synced))
        cache.write("fake-ffffffffffffffffffffffffffffffff", LyricsCache.Entry(6, null))
        cache.write("fake-eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", LyricsCache.Entry(7, Lyrics.Instrumental))
        val fresh = FileLyricsCache(dir)
        assertEquals(synced, fresh.read("fake-0123456789abcdef0123456789abcdef")?.lyrics)
        assertEquals(LyricsCache.Entry(6, null), fresh.read("fake-ffffffffffffffffffffffffffffffff"))
        assertEquals(Lyrics.Instrumental, fresh.read("fake-eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee")?.lyrics)
        // A key that isn't one of ours never becomes a path.
        cache.write("../../evil", LyricsCache.Entry(1, null))
        assertFalse(java.io.File(dir.parentFile, "evil.json").exists())
        // A damaged file reads as nothing and is removed.
        java.io.File(dir, "fake-dddddddddddddddddddddddddddddddd.json").writeText("{broken")
        assertNull(FileLyricsCache(dir).read("fake-dddddddddddddddddddddddddddddddd"))
        assertFalse(java.io.File(dir, "fake-dddddddddddddddddddddddddddddddd.json").exists())
        fresh.clear()
        assertTrue(dir.listFiles().isNullOrEmpty())
    }
}
