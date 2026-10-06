package app.podium.player.service

import android.app.PendingIntent
import android.content.Context
import app.podium.core.common.Clock
import app.podium.core.model.TrackId
import app.podium.player.api.AutoplaySettings
import app.podium.player.api.QueueStore
import app.podium.player.api.RecommendationEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import app.podium.player.api.TrackCatalog
import app.podium.sources.api.ArtworkResolver
import app.podium.sources.api.SourceHealthMonitor
import app.podium.sources.api.SourceRegistry
import app.podium.sources.api.resolve.EquivalenceStore
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

    /** Settings ▸ Autoplay (D-34). */
    val autoplay: StateFlow<AutoplaySettings> get() = DefaultAutoplay

    /** Suggestions for autoplay; null turns autoplay off. */
    val recommendations: RecommendationEngine? get() = null

    /** Copies already known to be the same recording on other sources (D-35), for autoplay's de-duplication. */
    val equivalence: EquivalenceStore? get() = null

    /** Songs played since a time (for avoid-repeats), from ONLINE history. */
    suspend fun playedSince(sinceMillis: Long): Set<TrackId> = emptySet()

    /** Opens the app when the user taps the media notification. */
    fun sessionActivity(context: Context): PendingIntent?

    interface Provider {
        val playbackDependencies: PlaybackDependencies
    }
}

private val DefaultAutoplay: StateFlow<AutoplaySettings> = MutableStateFlow(AutoplaySettings(enabled = false))
