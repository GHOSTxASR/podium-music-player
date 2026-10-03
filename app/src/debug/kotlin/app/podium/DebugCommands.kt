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
        "pause" -> graph.playbackController.pause()
        "play" -> if (graph.playbackController.snapshot.value.intent != PlayIntent.PLAY) graph.playbackController.play()
        "next" -> graph.playbackController.next()
        "previous" -> graph.playbackController.previous()
    }
}
