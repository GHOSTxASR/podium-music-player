package app.podium

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.podium.core.common.Clock
import app.podium.core.model.SourceId
import app.podium.player.api.PlayIntent
import app.podium.player.api.TrackCatalog
import app.podium.player.service.BuildConfigFlags
import app.podium.player.service.MediaControllerPlaybackController
import app.podium.player.service.PlaybackDependencies
import app.podium.player.service.SystemVolumeController
import app.podium.sources.api.ArtworkResolver
import app.podium.sources.api.SourceHealthMonitor
import app.podium.sources.api.SourceRegistry
import app.podium.sources.api.matching.TrackMatcher
import app.podium.sources.api.resolve.StreamResolver
import app.podium.sources.local.LocalMusicSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The composition root (ADR-009): every long-lived object is built here, once. Nothing below this
 * file knows which concrete sources exist.
 */
class AppGraph(private val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val clock: Clock = Clock.System

    val health = SourceHealthMonitor(clock)
    val registry = SourceRegistry(health)
    val matcher = TrackMatcher()
    val resolver = StreamResolver(registry, health, matcher, clock)
    val catalog = TrackCatalog(registry)
    val artwork = ArtworkResolver(registry)

    val localSource = LocalMusicSource(context, appScope)

    init {
        BuildConfigFlags.checkMirror = BuildConfig.DEBUG
        registry.register(localSource)
        buildVariantSources(context).forEach { registry.register(it) }
    }

    /** Debug-only narrowing of the library to one source (see DebugCommands); null = everything. */
    val libraryScope = MutableStateFlow<SourceId?>(null)
    val library = RegistryLibraryRepository(registry, catalog, appScope, libraryScope)
    val artworkLoader = ResolvingArtworkLoader(artwork)
    val deviceSettings = SharedPrefsDeviceSettings(context)
    val favorites = SharedPrefsFavorites(context)
    private val chime = BootChime(context)

    /** The virtual device's power (D-26). A fresh process starts by booting; the activity coming
     *  and going doesn't reboot it. */
    val power = MutableStateFlow(Power.BOOTING)

    fun togglePower() {
        when (power.value) {
            Power.OFF -> power.value = Power.BOOTING
            Power.BOOTING, Power.ON -> {
                // Like the original: switching off pauses the music.
                playbackController.pause()
                power.value = Power.OFF
            }
        }
    }

    /** Any Wheel press wakes a switched-off device. */
    fun wake() {
        if (power.value == Power.OFF) power.value = Power.BOOTING
    }

    fun onBootStarted() {
        val playing = playbackController.snapshot.value.intent == PlayIntent.PLAY
        if (deviceSettings.startupSound.value && !playing) chime.play()
    }

    fun onBootFinished() {
        if (power.value == Power.BOOTING) power.value = Power.ON
    }

    /** The UI's PlaybackController — Media3 MediaController to the PlaybackService. Main thread only. */
    val playbackController by lazy { MediaControllerPlaybackController(context, appScope) }
    val volume by lazy { SystemVolumeController(context) }

    private var musicAccess = localSource.hasPermission()

    /** The user granted or revoked a runtime permission (e.g. music access). */
    fun onPermissionsChanged() {
        musicAccess = localSource.hasPermission()
        localSource.onPermissionChanged()
    }

    /** Cheap check on resume; rescans only when access actually changed. */
    fun recheckPermissions() {
        if (localSource.hasPermission() != musicAccess) onPermissionsChanged()
    }

    val playbackDependencies = object : PlaybackDependencies {
        override val registry = this@AppGraph.registry
        override val health = this@AppGraph.health
        override val resolver = this@AppGraph.resolver
        override val catalog = this@AppGraph.catalog
        override val artwork = this@AppGraph.artwork
        override val clock = this@AppGraph.clock

        override fun sessionActivity(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

enum class Power { OFF, BOOTING, ON }
