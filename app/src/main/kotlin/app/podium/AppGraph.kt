package app.podium

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.podium.core.common.Clock
import app.podium.core.common.DiskActivity
import app.podium.core.database.DatabaseFavorites
import app.podium.core.database.DatabaseQueueStore
import app.podium.core.database.LibraryStore
import app.podium.core.database.OnlineLibraryStore
import app.podium.core.database.PodiumDatabase
import app.podium.core.designsystem.shell.BatteryLevel
import app.podium.core.designsystem.shell.BootCheck
import app.podium.core.model.SourceId
import app.podium.player.api.AutoplaySettings
import app.podium.player.api.PlayIntent
import app.podium.player.api.SourceRecommendationEngine
import app.podium.player.api.TrackCatalog
import app.podium.player.service.BuildConfigFlags
import app.podium.player.service.MediaControllerPlaybackController
import app.podium.player.service.PlaybackDependencies
import app.podium.player.service.SystemVolumeController
import app.podium.sources.api.ArtworkResolver
import app.podium.sources.api.SourceHealthMonitor
import app.podium.sources.api.MusicEnvironment
import app.podium.sources.api.SourceRegistry
import app.podium.sources.api.SourceSettings
import app.podium.sources.api.aggregate.MultiSourceCatalog
import app.podium.sources.api.aggregate.SourceFanOut
import app.podium.sources.api.aggregate.TrackGrouper
import app.podium.sources.api.resolve.InMemoryEquivalenceStore
import app.podium.sources.api.matching.TrackMatcher
import app.podium.sources.api.resolve.StreamResolver
import app.podium.sources.audius.AudiusMusicSource
import app.podium.sources.local.LocalMusicSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

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
    /** Which copies on different sources are the same recording (D-35): learnt from searches, used to play a twin. */
    val equivalence = InMemoryEquivalenceStore()
    val resolver = StreamResolver(registry, health, matcher, clock, equivalence = equivalence)
    /** The library database (ADR-004, D-31): library cache, favorites, the saved queue. */
    val database = PodiumDatabase.create(context)
    val libraryStore = LibraryStore(database)
    val catalog = TrackCatalog(registry) { id -> libraryStore.track(id) }
    val queueStore = DatabaseQueueStore(database)
    val artwork = ArtworkResolver(registry)

    val localSource = LocalMusicSource(context, appScope)

    /** The first online source (D-34): Audius' public catalogue, read anonymously. */
    val audius = AudiusMusicSource("Podium/${BuildConfig.VERSION_NAME} (Android music player)", onRequest = DiskActivity::pulse)

    /** Which sources are on and in what order (D-35): stored, applied once every source is registered. */
    val sourceSettings = SourceSettings(registry, SharedPrefsSourcePreferences(context))

    init {
        BuildConfigFlags.checkMirror = BuildConfig.DEBUG
        registry.register(localSource)
        registry.register(audius)
        buildVariantSources(context).forEach { registry.register(it) }
        sourceSettings.apply()
    }

    val environments = Environments(registry)

    /** Debug-only narrowing of the library to one source (see DebugCommands); null = everything. */
    val libraryScope = MutableStateFlow<SourceId?>(null)
    val library = DatabaseLibraryRepository(registry, libraryStore, catalog, appScope, libraryScope)
    val artworkLoader = ResolvingArtworkLoader(artwork)
    val deviceSettings = SharedPrefsDeviceSettings(context)
    /** Local favorites only (Music ▸ Favorites). */
    val localFavorites = DatabaseFavorites(database, appScope).also { LegacyFavorites.moveInto(context, it, appScope) }

    /** ONLINE's own library: liked songs, playlists, history (D-34). */
    val onlineStore = OnlineLibraryStore(database)
    /** Every enabled online source as one catalogue (D-35). */
    val onlineCatalog = MultiSourceCatalog(registry, MusicEnvironment.ONLINE, SourceFanOut(health), TrackGrouper(matcher), equivalence)
    val online = AppOnlineRepository(registry, onlineCatalog, onlineStore, catalog, appScope, TrackGrouper(matcher))

    /** Settings ▸ Online sources. */
    val onlineSources = RegistryOnlineSourceSettings(registry, sourceSettings, appScope)

    /** The heart on Now Playing: routed to local favorites or ONLINE's liked songs by the song's environment. */
    val favorites = EnvironmentFavorites(localFavorites, online, environments, { catalog.cached(it) ?: libraryStore.track(it) }, appScope)

    val recommendations = SourceRecommendationEngine(registry, equivalence::exactEquivalents)

    val autoplaySettings: StateFlow<AutoplaySettings> = combine(
        deviceSettings.autoplay, deviceSettings.onlineRecommendations, deviceSettings.avoidRepeats,
    ) { on, recs, repeats -> AutoplaySettings(on, recs, repeats) }
        .stateIn(appScope, SharingStarted.Eagerly, AutoplaySettings())

    private var listening = false

    /** Start recording online listening history (once the player is connected). */
    fun startListening() {
        if (listening) return
        listening = true
        OnlineHistoryRecorder(playbackController, onlineStore, environments, catalog, libraryStore, appScope).start()
    }
    /** The phone's battery, for the battery LED (D-33). */
    val battery: StateFlow<BatteryLevel?> = BatteryMonitor.levels(context).stateIn(appScope, SharingStarted.Eagerly, null)

    /** The startup chord and the Wheel's clicks (D-32). */
    val sounds = DeviceSounds(context)

    /** Music folders, for whichever library source reads from storage (D-32). */
    val musicFolders = RegistryMusicFolders(registry, appScope)

    /** The boot self-test's lines, with real values; the library count arrives when known. */
    val bootChecks: StateFlow<List<BootCheck>> = libraryStore.songCount()
        .map { count -> BootReport.checks(context, count) }
        .stateIn(appScope, SharingStarted.Eagerly, BootReport.checks(context, null))

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

    /** The mark appears: the startup chord, unless it's off or music is already playing. */
    fun onBootChime() {
        val playing = playbackController.snapshot.value.intent == PlayIntent.PLAY
        if (deviceSettings.startupSound.value && !playing) sounds.playChord()
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
        override val queueStore = this@AppGraph.queueStore
        override val autoplay = this@AppGraph.autoplaySettings
        override val recommendations = this@AppGraph.recommendations
        override val equivalence = this@AppGraph.equivalence
        override suspend fun playedSince(sinceMillis: Long) = onlineStore.playedSince(sinceMillis)

        override fun sessionActivity(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

enum class Power { OFF, BOOTING, ON }
