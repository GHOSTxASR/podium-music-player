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
        "pause" -> graph.playbackController.pause()
        "play" -> if (graph.playbackController.snapshot.value.intent != PlayIntent.PLAY) graph.playbackController.play()
        "next" -> graph.playbackController.next()
        "previous" -> graph.playbackController.previous()
    }
}
