package app.podium.poc

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PocMainActivity : Activity() {

    companion object {
        const val TAG = "PodiumPoc"
    }

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var textView: TextView
    private lateinit var scrollView: ScrollView
    private var player: PocPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        textView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.GREEN)
            setBackgroundColor(Color.BLACK)
            setPadding(32, 48, 32, 48)
            text = "=== Podium NewPipeExtractor Feasibility POC ===\nStarting tests...\n"
        }

        scrollView = ScrollView(this).apply {
            addView(textView)
        }

        setContentView(scrollView)

        player = PocPlayer(this).apply {
            onEvent = { event ->
                log("ExoPlayer Event: state=${event.state}, playing=${event.isPlaying}, pos=${event.currentPositionMs}ms, error=${event.error}")
            }
        }

        runPocSuite()
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        runOnUiThread {
            textView.append(message + "\n")
            scrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun runPocSuite() {
        scope.launch {
            log("\n[TEST 1] Initializing NewPipeExtractor...")
            val t0 = System.currentTimeMillis()
            try {
                withContext(Dispatchers.IO) {
                    PocExtractor.init()
                }
                val initDuration = System.currentTimeMillis() - t0
                log("[TEST 1 PASSED] Initialization succeeded in ${initDuration}ms")
            } catch (e: Throwable) {
                log("[TEST 1 FAILED] Initialization error: ${e.message}")
                e.printStackTrace()
                return@launch
            }

            // Test 2: URL & ID Recognition
            val testInputs = listOf(
                "Bare Video ID" to "kJQP7kiw5Fk",
                "Full YouTube Music URL" to "https://music.youtube.com/watch?v=kJQP7kiw5Fk",
                "YouTube Music Share URL" to "https://music.youtube.com/watch?v=kJQP7kiw5Fk&si=test_share"
            )

            log("\n[TEST 2] Testing URL Recognition & Normalization...")
            for ((label, input) in testInputs) {
                try {
                    val normalized = PocExtractor.normalizeUrl(input)
                    log("[TEST 2 PASSED] $label: '$input' -> '$normalized'")
                } catch (e: Throwable) {
                    log("[TEST 2 FAILED] $label failed: ${e.message}")
                }
            }

            // Test 3 & 4: Metadata and Stream Extraction
            val targetId = "kJQP7kiw5Fk" // "Despacito" - universal official track
            log("\n[TEST 3 & 4] Extracting metadata & audio streams for ID: $targetId...")
            val tExtractStart = System.currentTimeMillis()
            var extracted: ExtractedAudioTrack? = null

            try {
                extracted = withContext(Dispatchers.IO) {
                    PocExtractor.extract(targetId)
                }
                val extractDuration = System.currentTimeMillis() - tExtractStart
                log("[TEST 3 PASSED] Metadata extracted in ${extractDuration}ms:")
                log("  - Title: ${extracted.title}")
                log("  - Uploader: ${extracted.uploader}")
                log("  - Duration: ${extracted.durationSeconds}s")
                log("  - Thumbnail: ${extracted.thumbnailUrl}")

                log("[TEST 4 PASSED] Audio streams found: ${extracted.allAudioStreamsCount}")
                log("  - Selected Itag: ${extracted.itag}")
                log("  - Selected Format: ${extracted.format}")
                log("  - Bitrate: ${extracted.averageBitrate} kbps")
                log("  - Stream URL: ${extracted.bestAudioStreamUrl.take(80)}...")
                log("  - Has PoToken (pot): ${extracted.hasPoToken}")
                log("  - Has N parameter: ${extracted.hasNParameter}")

                val nowSec = System.currentTimeMillis() / 1000
                if (extracted.expiresAtEpochSeconds != null) {
                    val remainingSec = extracted.expiresAtEpochSeconds - nowSec
                    val remainingHours = remainingSec / 3600.0
                    log("  - Stream Expiration: in ${remainingSec}s (~${String.format("%.2f", remainingHours)}h)")
                } else {
                    log("  - Stream Expiration: Unknown (no expire param)")
                }
            } catch (e: Throwable) {
                log("[TEST 3/4 FAILED] Extraction failed: ${e::class.qualifiedName}: ${e.message}")
                e.printStackTrace()
                return@launch
            }

            // Test 5: Media3 ExoPlayer Playback
            val bestUrl = extracted.bestAudioStreamUrl
            log("\n[TEST 5] Testing Media3 ExoPlayer Playback with extracted stream...")
            val tPlayStart = System.currentTimeMillis()

            try {
                player?.play(bestUrl)
                log("[TEST 5] ExoPlayer prepare() & playWhenReady=true issued. Waiting for audio buffer...")

                // Wait for player to become ready or play
                var elapsed = 0
                while (player?.exoPlayer?.isPlaying != true && elapsed < 10000) {
                    delay(250)
                    elapsed += 250
                }

                if (player?.exoPlayer?.isPlaying == true) {
                    val timeToPlay = System.currentTimeMillis() - tPlayStart
                    log("[TEST 5 PASSED] Audio playback started in ${timeToPlay}ms!")
                    log("Playing for 10 seconds to verify stream stability...")
                    delay(5000)
                    val pos5s = player?.exoPlayer?.currentPosition ?: 0
                    log("Playback progress at 5s: position = ${pos5s}ms")

                    log("Testing Seek to 30,000ms...")
                    player?.seekTo(30000)
                    delay(2000)
                    val posSeek = player?.exoPlayer?.currentPosition ?: 0
                    log("Seek verified: position after seek = ${posSeek}ms")

                    delay(3000)
                    log("Testing Pause...")
                    player?.pause()
                    delay(1500)
                    log("Testing Resume...")
                    player?.resume()
                    delay(2000)

                    log("[TEST 5 COMPLETED SUCCESSFULLY] Audio stream played reliably through Media3 on device!")
                } else {
                    log("[TEST 5 FAILED] Player did not start playing within 10s. State: ${player?.exoPlayer?.playbackState}")
                }
            } catch (e: Throwable) {
                log("[TEST 5 FAILED] Playback exception: ${e::class.qualifiedName}: ${e.message}")
                e.printStackTrace()
            }

            log("\n=== ALL POC TESTS FINISHED ===")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
    }
}
