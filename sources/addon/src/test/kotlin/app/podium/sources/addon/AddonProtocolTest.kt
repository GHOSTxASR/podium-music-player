package app.podium.sources.addon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddonProtocolTest {

    @Test
    fun `reads a BitChord manifest with a quality choice`() {
        val m = AddonProtocol.manifest(Fixtures.MANIFEST.toByteArray())!!
        assertEquals("com.example.lossless", m.id)
        assertEquals("Lossless test", m.name)
        assertEquals("1.0.0", m.version)
        assertTrue(m.canSearch && m.canStream)
        assertEquals(false, m.allowsDownloads)
        val quality = m.settings.single()
        assertEquals("quality", quality.key)
        assertEquals("Quality", quality.label, "a setting without a label is named from its key")
        assertEquals(AddonSetting.Type.SELECT, quality.type)
        assertEquals(listOf("lossless", "high", "low"), quality.options.map { it.value })
        assertEquals("Lossless", quality.describe("lossless"))
    }

    @Test
    fun `settings settle on what the addon accepts`() {
        val m = AddonProtocol.manifest(Fixtures.MANIFEST.toByteArray())!!
        assertEquals(mapOf("quality" to "lossless"), m.settle(emptyMap()), "the default")
        assertEquals(mapOf("quality" to "low"), m.settle(mapOf("quality" to "low")))
        assertEquals(mapOf("quality" to "lossless"), m.settle(mapOf("quality" to "ultra")), "not an option: the default")
        assertEquals(mapOf("quality" to "lossless"), m.settle(mapOf("other" to "x")), "keys it didn't declare never travel")
    }

    @Test
    fun `toggles and text settings, and kinds it can't show are left out`() {
        val m = AddonProtocol.manifest(
            """{"name":"Mixed","settings":[
                {"key":"hires","type":"boolean","label":"Hi-res","default":true},
                {"key":"region","type":"text","default":"US"},
                {"key":"colour","type":"color"},
                {"key":"bad key!","type":"text"},
                {"key":"empty","type":"select","options":[]}
            ]}""".toByteArray(),
        )!!
        assertEquals("Mixed", m.id, "a manifest with a name and no id is named by it")
        assertEquals(listOf("hires", "region"), m.settings.map { it.key })
        assertEquals(mapOf("hires" to "true", "region" to "US"), m.settle(emptyMap()))
        assertEquals("On", m.settings[0].describe("true"))
        assertEquals(mapOf("hires" to "false", "region" to "EU"), m.settle(mapOf("hires" to "false", "region" to "EU")))
    }

    @Test
    fun `a manifest without resources is taken to search and stream`() {
        val m = AddonProtocol.manifest("""{"id":"x","name":"X"}""".toByteArray())!!
        assertTrue(m.canSearch && m.canStream)
        val streamsOnly = AddonProtocol.manifest("""{"id":"x","name":"X","resources":[{"name":"stream"}]}""".toByteArray())!!
        assertTrue(!streamsOnly.canSearch && streamsOnly.canStream)
    }

    @Test
    fun `anything that isn't a manifest is refused`() {
        assertNull(AddonProtocol.manifest("<html>Not found</html>".toByteArray()))
        assertNull(AddonProtocol.manifest("[1,2]".toByteArray()))
        assertNull(AddonProtocol.manifest("""{"resources":["search"]}""".toByteArray()), "no id and no name")
    }

    @Test
    fun `reads search answers`() {
        val tracks = AddonProtocol.search(Fixtures.SEARCH.toByteArray())!!
        assertEquals(3, tracks.size)
        val first = tracks[0]
        assertEquals("lt_001", first.id)
        assertEquals("One More Time", first.title)
        assertEquals("Daft Punk", first.artist)
        assertEquals("Discovery", first.album)
        assertEquals(320_000L, first.durationMs)
        assertNull(first.explicit)
    }

    @Test
    fun `search answers in other shapes`() {
        val tracks = AddonProtocol.search(
            """[
                {"id":42,"name":"Song","artists":[{"name":"A"},"B"],"album":{"title":"LP"},"duration":"3:21","explicit":true,"isrc":"USAAA0000001"},
                {"id":"ms","title":"Long","artist":{"name":"C"},"durationMs":200500},
                {"id":"big","title":"Ms in duration","artist":"D","duration":215000},
                {"title":"No id"}
            ]""".toByteArray(),
        )!!
        assertEquals(listOf("42", "ms", "big"), tracks.map { it.id })
        assertEquals("A, B", tracks[0].artist)
        assertEquals("LP", tracks[0].album)
        assertEquals(201_000L, tracks[0].durationMs)
        assertEquals(true, tracks[0].explicit)
        assertEquals("USAAA0000001", tracks[0].isrc)
        assertEquals("C", tracks[1].artist)
        assertEquals(200_500L, tracks[1].durationMs)
        assertEquals(215_000L, tracks[2].durationMs, "more than ten hours of seconds is milliseconds")
        assertEquals(emptyList(), AddonProtocol.search("""{"results":[]}""".toByteArray()))
        assertNull(AddonProtocol.search("""{"error":"boom"}""".toByteArray()))
    }

    @Test
    fun `reads stream answers`() {
        val s = AddonProtocol.stream(Fixtures.STREAM_DASH.toByteArray())!!
        assertEquals("https://cdn.example.com/manifest/abc.mpd", s.url)
        assertEquals("dash", s.format)
        assertEquals("Hi-Res Lossless · 24-bit / 44.1 kHz", s.quality)
        assertEquals(false, s.encrypted)
        assertNull(s.expiresAtMillis)
    }

    @Test
    fun `stream headers are kept only when safe, expiry in seconds or millis`() {
        val s = AddonProtocol.stream(
            """{"streamUrl":"https://x/a.flac","encrypted":"false","expiresAt":1900000000,
                "headers":{"Referer":"https://x/","Bad Header":"v","X-Inject":"a\r\nb","Number":5}}""".toByteArray(),
        )!!
        assertEquals("https://x/a.flac", s.url)
        assertEquals(mapOf("Referer" to "https://x/", "Number" to "5"), s.headers)
        assertEquals(1_900_000_000_000L, s.expiresAtMillis)
        assertEquals(1_900_000_000_123L, AddonProtocol.stream("""{"url":"u","expires":1900000000123}""".toByteArray())!!.expiresAtMillis)
        assertTrue(AddonProtocol.stream("""{"url":"u","drm":true}""".toByteArray())!!.encrypted)
        assertNull(AddonProtocol.stream("""{"quality":"lossless"}""".toByteArray()), "no address")
    }
}
