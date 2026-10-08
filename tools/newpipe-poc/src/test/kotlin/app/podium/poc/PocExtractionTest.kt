package app.podium.poc

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PocExtractionTest {

    @Test
    fun test1_initialization() {
        PocExtractor.init()
        // If no exception, initialization passed
    }

    @Test
    fun test2_urlNormalization() {
        val bare = PocExtractor.normalizeUrl("kJQP7kiw5Fk")
        assertEquals("https://www.youtube.com/watch?v=kJQP7kiw5Fk", bare)

        val full = PocExtractor.normalizeUrl("https://music.youtube.com/watch?v=kJQP7kiw5Fk")
        assertEquals("https://www.youtube.com/watch?v=kJQP7kiw5Fk", full)

        val share = PocExtractor.normalizeUrl("https://music.youtube.com/watch?v=kJQP7kiw5Fk&si=share123")
        assertEquals("https://www.youtube.com/watch?v=kJQP7kiw5Fk", share)
    }

    @Test
    fun test3_and_4_extraction() {
        val result = PocExtractor.extract("kJQP7kiw5Fk")
        assertNotNull(result.id)
        assertNotNull(result.title)
        assertTrue(result.durationSeconds > 0)
        assertTrue(result.allAudioStreamsCount > 0)
        assertNotNull(result.bestAudioStreamUrl)
        assertTrue(result.bestAudioStreamUrl.startsWith("https://"))
        println("Extracted Track: ${result.title}")
        println("Uploader: ${result.uploader}")
        println("Duration: ${result.durationSeconds}s")
        println("Audio Streams Count: ${result.allAudioStreamsCount}")
        println("Format: ${result.format}, Bitrate: ${result.averageBitrate} kbps, itag: ${result.itag}")
        println("Expires in: ${result.expiresAtEpochSeconds}")
        println("Has pot: ${result.hasPoToken}, Has n: ${result.hasNParameter}")

        // Test HTTP reachability of stream URL (F2 verification: 200/206 vs 403)
        val client = okhttp3.OkHttpClient()
        val request = okhttp3.Request.Builder()
            .url(result.bestAudioStreamUrl)
            .addHeader("User-Agent", PocDownloader.USER_AGENT)
            .addHeader("Range", "bytes=0-1024")
            .build()
        client.newCall(request).execute().use { response ->
            println("Stream HTTP Status: ${response.code} (expected 206 or 200)")
            assertTrue(response.isSuccessful, "Stream fetch should succeed, got ${response.code}")
            val bytesRead = response.body?.bytes()?.size ?: 0
            println("Stream Initial Chunk Bytes Read: $bytesRead bytes")
            assertTrue(bytesRead > 0, "Should read non-zero audio bytes")
        }
    }

    @Test
    fun test_diverse_catalog_tracks() {
        val tracks = listOf(
            "fJ9rUzIMcZQ" to "Queen - Bohemian Rhapsody",
            "JGwWNGJdvx8" to "Ed Sheeran - Shape of You",
            "OPf0YbXqDm0" to "Mark Ronson - Uptown Funk",
            "9bZkp7q19f0" to "PSY - Gangnam Style"
        )
        val client = okhttp3.OkHttpClient()

        for ((id, expected) in tracks) {
            val t0 = System.currentTimeMillis()
            val result = PocExtractor.extract(id)
            val duration = System.currentTimeMillis() - t0
            println("Track $id ($expected): extracted in ${duration}ms, streams: ${result.allAudioStreamsCount}, itag: ${result.itag}, format: ${result.format}, bitrate: ${result.averageBitrate} kbps")
            assertTrue(result.allAudioStreamsCount > 0, "Streams must be found for $id")

            // Test HTTP reachability for audio stream
            val request = okhttp3.Request.Builder()
                .url(result.bestAudioStreamUrl)
                .addHeader("User-Agent", PocDownloader.USER_AGENT)
                .addHeader("Range", "bytes=0-1024")
                .build()
            client.newCall(request).execute().use { response ->
                println("  -> Stream HTTP Status for $id: ${response.code}")
                assertTrue(response.isSuccessful, "Stream fetch for $id failed with HTTP ${response.code}")
            }
        }
    }
}
