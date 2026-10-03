package app.podium.player.service

import android.app.PendingIntent
import android.content.Context
import app.podium.core.common.Clock
import app.podium.player.api.QueueStore
import app.podium.player.api.TrackCatalog
import app.podium.sources.api.ArtworkResolver
import app.podium.sources.api.SourceHealthMonitor
import app.podium.sources.api.SourceRegistry
import app.podium.sources.api.resolve.StreamResolver

/**
 * What the playback service needs from the app's composition root (ADR-009). The Application
 * implements [Provider]; the service never constructs sources itself.
 */
interface PlaybackDependencies {
    val registry: SourceRegistry
    val health: SourceHealthMonitor
    val resolver: StreamResolver
    val catalog: TrackCatalog
    val artwork: ArtworkResolver
    val clock: Clock

    /** Where the queue is kept between runs (D-31); null keeps it in memory only. */
    val queueStore: QueueStore? get() = null

    /** Opens the app when the user taps the media notification. */
    fun sessionActivity(context: Context): PendingIntent?

    interface Provider {
        val playbackDependencies: PlaybackDependencies
    }
}
