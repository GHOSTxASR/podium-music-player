package app.podium

import android.content.Intent
import app.podium.core.model.SourceId
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
        // ONLINE as if the network were gone (local music unaffected); and back.
        "online-offline" -> graph.audius.simulateOffline = true
        "online-online" -> graph.audius.simulateOffline = false
        "pause" -> graph.playbackController.pause()
        "play" -> if (graph.playbackController.snapshot.value.intent != PlayIntent.PLAY) graph.playbackController.play()
        "next" -> graph.playbackController.next()
        "previous" -> graph.playbackController.previous()
    }
}
