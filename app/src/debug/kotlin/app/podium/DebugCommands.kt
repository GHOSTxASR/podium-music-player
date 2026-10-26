package app.podium

import android.content.Intent
import app.podium.core.model.SourceId
import app.podium.core.model.TrackId
import app.podium.player.api.PlayIntent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Debug builds only: drive the player from adb, so device tests can use the generated test tones
 * as their own queue without touching the listener's library. For example:
 * `adb shell am start -n app.podium.debug/app.podium.MainActivity --es podium.debug play-test-tones --ei podium.index 2 --ez podium.paused true`
 */
/**
 * Window-level debug commands: `keep-screen-on` holds the display on while Podium is in front (for
 * unattended device testing, without touching the phone's own settings); `allow-screen-off` undoes it.
 */
internal fun applyDebugWindowCommand(intent: Intent?, activity: android.app.Activity) {
    val flag = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
    when (intent?.getStringExtra("podium.debug")) {
        "keep-screen-on" -> activity.window.addFlags(flag)
        "allow-screen-off" -> activity.window.clearFlags(flag)
    }
}

/**
 * Debug builds: how each online song's stream was found, in logcat under `PodiumStreams` (D-98) —
 * per app asked, its HTTP code, playability status and the check's codes. Never an address, a cookie
 * or a visitor id.
 */
internal fun installDebugDiagnostics() {
    app.podium.sources.youtubemusic.StreamWalkDebug.trace = { android.util.Log.i("PodiumStreams", it) }
}

internal fun handleDebugIntent(intent: Intent?, graph: AppGraph) {
    when (intent?.getStringExtra("podium.debug")) {
        "play-test-tones" -> graph.appScope.launch {
            val tracks = graph.registry.get(SourceId("test"))?.library?.tracks()?.first().orEmpty()
            if (tracks.isEmpty()) return@launch
            graph.catalog.remember(tracks)
            val start = intent.getIntExtra("podium.index", 0).coerceIn(0, tracks.lastIndex)
            val controller = graph.playbackController
            controller.playContext(tracks.map { it.id }, start, "Test tones")
            if (intent.getBooleanExtra("podium.paused", false)) {
                // Pause as soon as the new item is current — before any audio is buffered.
                withTimeoutOrNull(5_000) { controller.snapshot.first { it.item?.trackId == tracks[start].id } }
                controller.pause()
            }
        }
        // Show only the test tones in the library (in memory; a restart or library-all restores it).
        "library-test-only" -> graph.libraryScope.value = SourceId("test")
        "library-all" -> graph.libraryScope.value = null
        // ONLINE switched off and on again (local music unaffected).
        "online-off" -> graph.sourceSettings.setEnabled(graph.youtubeMusic.descriptor.id, false)
        "online-on" -> graph.sourceSettings.setEnabled(graph.youtubeMusic.descriptor.id, true)
        // What the app that plays online songs exposes through its media session (device acceptance,
        // YOUTUBE_MUSIC_DEVICE_ACCEPTANCE.md U1–U6). Logs facts only: no titles beyond the current one.
        "remote-probe" -> {
            graph.remotePlayback.refresh()
            val s = graph.remotePlayback.state.value
            val owner = (graph.playbackController as? app.podium.player.api.OwnerAwarePlaybackController)?.owner?.value
            android.util.Log.i(
                "PodiumDebug",
                "remote access=${graph.remotePlayback.access.value} connected=${s.connected} playing=${s.playing} " +
                    "buffering=${s.buffering} position=${graph.remotePlayback.positionMs()} duration=${s.durationMs} " +
                    "mediaIdShape=${s.mediaId?.let { if (Regex("[A-Za-z0-9_-]{11}").matches(it)) "video-id" else "other(${it.length})" }} " +
                    "actions=${s.actions} queue=${s.queue?.size ?: "not exposed"} activeQueueItem=${s.activeQueueItemId} owner=$owner",
            )
        }
        // Hand one online song to the app: --es podium.track ytmusic|<id>
        "remote-play" -> intent.getStringExtra("podium.track")?.takeIf { '|' in it }?.let { id ->
            graph.playbackController.playContext(listOf(TrackId(id)), 0, "Debug")
        }
        "remote-pause" -> graph.playbackController.pause()
        "back-to-local" -> graph.playbackController.resumeLocal()
        // Provenance of what's playing (D-36: kept for diagnostics, never shown on Now Playing).
        "now-playing-source" -> graph.playbackController.snapshot.value.item.let { item ->
            android.util.Log.i(
                "PodiumDebug",
                if (item == null) "Nothing playing"
                else "Now playing ${item.trackId}: served by ${item.servedByDisplayName} (${item.servedBy}) via ${item.resolutionPath}",
            )
        }
        // "Not the same song" for two copies (the infrastructure a future confirmation UI will use):
        // --es podium.a <trackId> --es podium.b <trackId>
        "not-same" -> {
            val a = intent.getStringExtra("podium.a")
            val b = intent.getStringExtra("podium.b")
            if (a != null && b != null && '|' in a && '|' in b) graph.equivalence.reject(TrackId(a), TrackId(b))
        }
        // Make a sticker from a picture in the app's own files (device tests, no photo picker):
        // --es podium.file sticker-test.png
        "sticker-make" -> intent.getStringExtra("podium.file")?.let { name ->
            graph.debugStickerSource.value = android.net.Uri.fromFile(java.io.File(graph.context.filesDir, name))
        }
        // The hands-on guide, as on a first launch.
        "guide" -> {
            graph.guide.forget()
            graph.debugGuide.value = true
        }
        "guide-done" -> graph.guide.markSeen()
        // Arrange stickers straight away (device tests).
        "stickers-arrange" -> graph.debugArrange.value = true
        // Podium's space without the pinch (adb can't pinch): --es podium.debug space-enter / space-leave
        "space-enter" -> graph.debugSpace.value = true
        "space-leave" -> graph.debugSpace.value = false
        // Invented synced lyrics for the generated test tones only (device tests of the lyric screen):
        // lines through each tone with a long instrumental gap (about a third of the tone);
        // --es podium.kind instrumental | notfound answers that instead; intro starts the words late. "lyrics-demo-off" goes back
        // to the service.
        "lyrics-demo" -> {
            val kind = intent.getStringExtra("podium.kind")
            val credit = app.podium.core.lyrics.LyricsAttribution("Podium test lyrics", "https://example.invalid")
            graph.debugLyrics.value = { request ->
                if (!request.key.startsWith("test|")) null else when (kind) {
                    "instrumental" -> app.podium.core.lyrics.LyricsResult.Found(app.podium.core.lyrics.Lyrics.Instrumental, credit)
                    "notfound" -> app.podium.core.lyrics.LyricsResult.NotFound
                    else -> {
                        val length = request.durationMs ?: 30_000L
                        val lines = DEMO_LINES.mapIndexed { i, text ->
                            // Half the lines, a long gap (the orb, or the line drawing on its own), then the
                            // rest; "intro": a long introduction first, then the lines close together.
                            val share = if (kind == "intro") 0.40 + i * 0.1 else if (i < 3) 0.06 + i * 0.12 else 0.75 + (i - 3) * 0.08
                            app.podium.core.lyrics.LyricsLine((length * share).toLong(), text)
                        }
                        app.podium.core.lyrics.LyricsResult.Found(app.podium.core.lyrics.Lyrics.Synced(lines), credit)
                    }
                }
            }
        }
        "lyrics-demo-off" -> graph.debugLyrics.value = null
        // Open a screen without the Wheel: --es podium.place lyrics | now-playing | home
        "open" -> graph.debugOpen.value = intent.getStringExtra("podium.place")
        // The finish and display theme, as Settings sets them (device tests of each look's cost):
        // --es podium.finish GLASS | CARBON | ... --es podium.display GLASS | CARBON | BONE | CUSTOM
        "appearance" -> {
            val current = graph.deviceSettings.appearance.value
            val finish = app.podium.core.designsystem.shell.FinishPreset.entries.firstOrNull { it.name == intent.getStringExtra("podium.finish") } ?: current.preset
            val display = app.podium.core.designsystem.theme.DisplayTheme.entries.firstOrNull { it.name == intent.getStringExtra("podium.display") } ?: current.display
            graph.deviceSettings.setAppearance(current.copy(preset = finish, display = display))
        }
        // The lyrics theme, as Settings sets it: --es podium.theme CLASSIC | CONTINUOUS_LINE
        "lyrics-theme" -> app.podium.core.designsystem.theme.LyricsTheme.entries.firstOrNull { it.name == intent.getStringExtra("podium.theme") }?.let { theme ->
            val current = graph.deviceSettings.appearance.value
            graph.deviceSettings.setAppearance(current.copy(screen = current.screen.copy(lyricsTheme = theme)))
        }
        // Apps the online stream walk passes over (D-98), to prove the others serve songs:
        // --es podium.apps VISIONOS,ANDROID_VR (none: every app again). In memory until a restart.
        "stream-skip" -> app.podium.sources.youtubemusic.StreamWalkDebug.skipped =
            intent.getStringExtra("podium.apps").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        "pause" -> graph.playbackController.pause()
        "play" -> if (graph.playbackController.snapshot.value.intent != PlayIntent.PLAY) graph.playbackController.play()
        "next" -> graph.playbackController.next()
        "previous" -> graph.playbackController.previous()
    }
}

/** Invented words for the test tones (no song's lyrics are ever bundled). */
private val DEMO_LINES = listOf(
    "Quiet lights along the water",
    "every window keeps a song",
    "carry me across the evening",
    "Slow and steady goes the night",
    "hold the line and let it run",
    "till the morning finds us home",
)
