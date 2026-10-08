package app.podium

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.podium.core.common.Clock
import app.podium.core.common.DiskActivity
import app.podium.core.database.DatabaseEquivalenceStore
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
import app.podium.sources.api.matching.TrackMatcher
import app.podium.sources.api.resolve.StreamResolver
import app.podium.sources.local.LocalMusicSource
import app.podium.sources.youtubemusic.YouTubeMusicSource
import app.podium.player.api.OwnerAwarePlaybackController
import app.podium.player.remote.MediaSessionRemotePlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The composition root (ADR-009): every long-lived object is built here, once. Nothing below this
 * file knows which concrete sources exist.
 */
class AppGraph(val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val clock: Clock = Clock.System

    val health = SourceHealthMonitor(clock)
    val registry = SourceRegistry(health)
    val matcher = TrackMatcher()
    /** The library database (ADR-004, D-31): library cache, favorites, the saved queue. */
    val database = PodiumDatabase.create(context)

    /**
     * Which copies on different sources are the same recording (D-35, D-36): EXACT matches learnt from
     * searches and the listener's "not the same song", kept across restarts; used to play a twin.
     */
    val equivalence = DatabaseEquivalenceStore(database, appScope).also { store -> appScope.launch { store.load() } }
    val resolver = StreamResolver(registry, health, matcher, clock, equivalence = equivalence)

    val libraryStore = LibraryStore(database)
    val catalog = TrackCatalog(registry) { id -> libraryStore.track(id) }
    val queueStore = DatabaseQueueStore(database)
    val artwork = ArtworkResolver(registry)

    val localSource = LocalMusicSource(context, appScope)

    /** Which sources are on (D-35): stored, applied once every source is registered. */
    val sourceSettings = SourceSettings(registry, SharedPrefsSourcePreferences(context))

    /** Secrets for sources the listener signs in to (D-37): sealed with a Keystore key, never logged. */
    val credentials = KeystoreCredentialStore(context)

    /** Where Podium hands YouTube Music songs to play (YOUTUBE_MUSIC_ARCHITECTURE §8): the official app. */
    val remotePlayback = MediaSessionRemotePlayback(
        context,
        appScope,
        YouTubeMusicSource.PROVIDER_APP_PACKAGE,
        returnToPodium = {
            if (deviceSettings.stayInPodium.value) Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) else null
        },
    )

    /**
     * ONLINE (D-38, D-48): YouTube Music — its catalogue and the listener's account read here, its
     * songs played by Podium itself. The device's own browser user agent, warmed off the main thread.
     */
    val youtubeMusic = YouTubeMusicSource(
        credentials,
        userAgent = { webUserAgent },
        region = { java.util.Locale.getDefault().country.takeIf { it.length == 2 } ?: "US" },
    )

    private val webUserAgent: String by lazy {
        runCatching { android.webkit.WebSettings.getDefaultUserAgent(context) }.getOrNull()
            ?: "Mozilla/5.0 (Linux; Android ${android.os.Build.VERSION.RELEASE}) AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36"
    }

    init {
        BuildConfigFlags.checkMirror = BuildConfig.DEBUG
        registry.register(localSource)
        registry.register(youtubeMusic)
        buildVariantSources(context).forEach { registry.register(it) }
        sourceSettings.apply()
        RetiredSources.cleanUp(context, credentials)
        appScope.launch(Dispatchers.IO) { webUserAgent }
    }

    val environments = Environments(registry)

    /** Whether a song's source can only play it in another app (provider-neutral: by route). */
    fun playsRemotely(track: app.podium.core.model.Track): Boolean {
        val routes = registry.get(track.source.sourceId)?.playback?.routes ?: return false
        return app.podium.core.model.PlaybackRoute.REMOTE in routes && app.podium.core.model.PlaybackRoute.DIRECT !in routes
    }

    /** Debug-only narrowing of the library to one source (see DebugCommands); null = everything. */
    val libraryScope = MutableStateFlow<SourceId?>(null)
    val library = DatabaseLibraryRepository(registry, libraryStore, catalog, appScope, libraryScope)
    val artworkLoader = ResolvingArtworkLoader(artwork)
    val deviceSettings = SharedPrefsDeviceSettings(context)
    /** Local favorites only (Music ▸ Favorites). */
    val localFavorites = DatabaseFavorites(database, appScope).also { LegacyFavorites.moveInto(context, it, appScope) }

    /** ONLINE's own library: liked songs, playlists, history (D-34), per account (D-38). */
    val onlineStore = OnlineLibraryStore(database)
    val online = OnlineMusicRepository(registry, health, onlineStore, catalog, appScope)

    /** Settings ▸ YouTube Music: the account, the app that plays, and how hand-off behaves. */
    val onlineService = AppOnlineServiceSettings(this)

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
        OnlineHistoryRecorder(playbackController, onlineStore, environments, catalog, libraryStore, appScope, accountKey = { online.accountKey.value }).start()
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

    /**
     * Lyrics (LYRICS_ARCHITECTURE.md): LRCLIB by the song's metadata, only with the listener's consent
     * (D-11), kept in the app's cache directory.
     */
    val lyrics = app.podium.core.lyrics.LyricsRepository(
        provider = app.podium.core.lyrics.LrclibProvider(app.podium.core.lyrics.UrlConnectionLyricsHttp(LYRICS_USER_AGENT)),
        cache = app.podium.core.lyrics.FileLyricsCache(java.io.File(context.cacheDir, "lyrics")),
        consent = { deviceSettings.onlineLyrics.value },
    )

    val lyricsGateway = object : app.podium.feature.nowplaying.LyricsGateway {
        override val attribution get() = lyrics.attribution
        override suspend fun lyrics(request: app.podium.core.lyrics.LyricsRequest) = this@AppGraph.lyrics.lyrics(request)
        override fun allow() = deviceSettings.setOnlineLyrics(true)
    }

    /** The display's background picture, read from the URI the listener chose (D-41). */
    val displayImages = DisplayImages(context, appScope)

    /** Podium's own player — Media3 MediaController to the PlaybackService. Main thread only. */
    val localPlayback by lazy { MediaControllerPlaybackController(context, appScope) }

    /**
     * The UI's PlaybackController: Podium's own player for music it plays itself, the official app for
     * YouTube Music — one owner at a time, hard cuts between them, the local queue never touched by
     * remote playback (YOUTUBE_MUSIC_ARCHITECTURE §8.3). Main thread only.
     */
    val playbackController by lazy { OwnerAwarePlaybackController(localPlayback, remotePlayback, catalog, registry, appScope) }
    val volume by lazy { SystemVolumeController(context) }

    /** Debug builds: open (true) or close (false) Podium's space from adb (device tests, D-54). */
    val debugSpace = MutableStateFlow<Boolean?>(null)

    /** The listener's stickers and where they're stuck (D-55): files in the app's own storage. */
    val stickers by lazy { app.podium.stickers.StickerStore(java.io.File(context.filesDir, "stickers")) }

    /** Finds a picture's subject on the phone (D-55); the model loads on first use. */
    val cutter by lazy { app.podium.stickers.SubjectCutter(context) }

    /** Whether the hands-on guide was finished or skipped (D-57). */
    val guide by lazy { app.podium.guide.GuideStore(context) }

    /** Debug builds: make a sticker from this picture (device tests, without the photo picker). */
    val debugStickerSource = MutableStateFlow<android.net.Uri?>(null)

    /** Debug builds: start the hands-on guide (true) as on a first launch. */
    val debugGuide = MutableStateFlow(false)

    /** Debug builds: open the space and arrange stickers. */
    val debugArrange = MutableStateFlow(false)

    /**
     * Turn off Podium (D-56): the music stops and everything is kept (stickers written, the
     * player's place saved as it pauses); the caller then closes the app.
     */
    fun prepareToTurnOff() {
        playbackController.pause()
        stickers.flush()
        localPlayback.release()
    }

    /** Where the music is heard and whether it's muted, for the status bar (D-52). */
    val audioOutput by lazy { app.podium.player.service.AudioOutputMonitor(context) }

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

/** LRCLIB asks clients to name themselves. */
private val LYRICS_USER_AGENT = "Podium/${BuildConfig.VERSION_NAME} (Android music player)"
