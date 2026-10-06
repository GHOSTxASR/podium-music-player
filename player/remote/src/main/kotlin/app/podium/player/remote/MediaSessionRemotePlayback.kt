package app.podium.player.remote

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import app.podium.player.api.RemoteAccess
import app.podium.player.api.RemoteActions
import app.podium.player.api.RemotePlayback
import app.podium.player.api.RemoteQueueItem
import app.podium.player.api.RemoteSessionState
import app.podium.player.api.RemoteStart
import app.podium.sources.api.PlaybackTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Remote playback through Android's own media-session interface (PLAYBACK_TARGETS.md §5.2,
 * YOUTUBE_MUSIC_ARCHITECTURE.md §8): the app named by the target plays, under the listener's own
 * account and entitlement; Podium starts it and then uses the app's media session like any other
 * controller (headphones, a car, the lock screen) — play, pause, seek, skip, its queue — and mirrors
 * what the session reports. Nothing is extracted and nothing is bypassed: Podium only ever asks the
 * app to do what its session advertises.
 *
 * Starting a song: through the session (`playFromUri`) when the app advertises it — Podium stays in
 * front — otherwise by opening the app on the song's link ("hand-off"); with [returnToPodium] the app
 * starts behind Podium, which comes straight back to the front.
 *
 * Generic over the app ([appPackage]); the provider's identity comes from the target.
 */
class MediaSessionRemotePlayback(
    private val context: Context,
    private val scope: CoroutineScope,
    private val appPackage: String,
    /** Podium's own activity, brought back over the app after a hand-off; null = show the app. */
    private val returnToPodium: () -> Intent?,
) : RemotePlayback {

    private val main = Handler(Looper.getMainLooper())
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(context, MediaSessionAccessService::class.java)

    private val _access = MutableStateFlow(RemoteAccess.NO_APP)
    override val access: StateFlow<RemoteAccess> = _access.asStateFlow()

    private val _state = MutableStateFlow(RemoteSessionState())
    override val state: StateFlow<RemoteSessionState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var listening = false

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onQueueChanged(queue: MutableList<android.media.session.MediaSession.QueueItem>?) = publish()
        override fun onSessionDestroyed() = attach(null)
    }

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { controllers -> attach(controllers) }

    init {
        refresh()
        // The grant can arrive while Podium runs (the listener comes back from Settings).
        scope.launch { MediaSessionAccessService.connected.drop(1).collect { refresh() } }
    }

    // --- Access ------------------------------------------------------------------------------------------

    override fun refresh() {
        val next = when {
            !installed() -> RemoteAccess.NO_APP
            !granted() -> RemoteAccess.NEEDS_ACCESS
            else -> RemoteAccess.READY
        }
        _access.value = next
        if (next == RemoteAccess.READY) listen() else stopListening()
    }

    private fun installed(): Boolean = try {
        context.packageManager.getPackageInfo(appPackage, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun granted(): Boolean = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    private fun listen() {
        if (listening) return
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsChanged, listenerComponent, main)
            listening = true
            attach(sessions.getActiveSessions(listenerComponent))
        } catch (e: SecurityException) {
            // The grant was withdrawn between the check and the call.
            Log.w(TAG, "media sessions not accessible")
            _access.value = RemoteAccess.NEEDS_ACCESS
        }
    }

    private fun stopListening() {
        if (listening) runCatching { sessions.removeOnActiveSessionsChangedListener(sessionsChanged) }
        listening = false
        attach(null)
    }

    private fun attach(controllers: List<MediaController>?) {
        val next = controllers?.firstOrNull { it.packageName == appPackage }
        if (next?.sessionToken == controller?.sessionToken && next != null) return
        controller?.unregisterCallback(callback)
        controller = next
        next?.registerCallback(callback, main)
        publish()
    }

    // --- Mirroring ---------------------------------------------------------------------------------------

    private fun publish() {
        val c = controller
        if (c == null) {
            _state.value = RemoteSessionState()
            return
        }
        val ps = c.playbackState
        val md = c.metadata
        val actions = ps?.actions ?: 0L
        fun has(flag: Long) = actions and flag != 0L
        _state.value = RemoteSessionState(
            connected = true,
            playing = ps?.state == PlaybackState.STATE_PLAYING,
            buffering = ps?.state == PlaybackState.STATE_BUFFERING || ps?.state == PlaybackState.STATE_CONNECTING,
            positionMs = ps?.position?.coerceAtLeast(0) ?: 0L,
            positionUpdatedAt = ps?.lastPositionUpdateTime ?: 0L,
            speed = ps?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 },
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.description?.title?.toString(),
            artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.description?.subtitle?.toString(),
            album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            mediaId = md?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID) ?: md?.description?.mediaId,
            actions = RemoteActions(
                play = has(PlaybackState.ACTION_PLAY) || has(PlaybackState.ACTION_PLAY_PAUSE),
                pause = has(PlaybackState.ACTION_PAUSE) || has(PlaybackState.ACTION_PLAY_PAUSE),
                seek = has(PlaybackState.ACTION_SEEK_TO),
                next = has(PlaybackState.ACTION_SKIP_TO_NEXT),
                previous = has(PlaybackState.ACTION_SKIP_TO_PREVIOUS),
                skipToQueueItem = has(PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM),
                playFromUri = has(PlaybackState.ACTION_PLAY_FROM_URI),
            ),
            queue = c.queue?.map { q ->
                RemoteQueueItem(
                    id = q.queueId,
                    title = q.description.title?.toString().orEmpty(),
                    subtitle = q.description.subtitle?.toString(),
                    mediaId = q.description.mediaId,
                )
            },
            activeQueueItemId = ps?.activeQueueItemId?.takeIf { it != android.media.session.MediaSession.QueueItem.UNKNOWN_ID.toLong() },
            error = ps?.state == PlaybackState.STATE_ERROR,
        )
    }

    override fun positionMs(): Long = _state.value.positionAt(SystemClock.elapsedRealtime())

    // --- Commands ------------------------------------------------------------------------------------------

    override suspend fun start(target: PlaybackTarget.RemoteProvider): RemoteStart {
        refresh()
        if (_access.value == RemoteAccess.NO_APP) return RemoteStart.Failed(RemoteAccess.NO_APP)
        val link = Uri.parse(target.providerItemRef)
        val c = controller
        if (c != null && _state.value.actions.playFromUri) {
            c.transportControls.playFromUri(link, Bundle())
            return RemoteStart.Controlled
        }
        return if (open(Intent(Intent.ACTION_VIEW, link).setPackage(appPackage))) RemoteStart.OpenedApp else RemoteStart.Failed(null)
    }

    private fun open(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val back = returnToPodium()?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            if (back != null) context.startActivities(arrayOf(intent, back)) else context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "the app can't open that link")
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "not allowed to open the app now")
            false
        }
    }

    private inline fun controls(block: (MediaController.TransportControls) -> Unit) {
        controller?.transportControls?.let(block)
    }

    override fun play() = controls { it.play() }
    override fun pause() = controls { it.pause() }
    override fun seekTo(positionMs: Long) = controls { it.seekTo(positionMs.coerceAtLeast(0)) }
    override fun next() = controls { it.skipToNext() }
    override fun previous() = controls { it.skipToPrevious() }
    override fun skipToQueueItem(id: Long) = controls { it.skipToQueueItem(id) }

    override fun openApp() {
        val launch = context.packageManager.getLaunchIntentForPackage(appPackage) ?: return
        try {
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: RuntimeException) {
            Log.w(TAG, "couldn't open the app")
        }
    }

    companion object {
        private const val TAG = "PodiumRemote"

        /**
         * Where the listener allows Podium to see media sessions. On Android 11+ this opens Podium's
         * own entry directly; before that, the list of all such apps.
         */
        fun accessSettingsIntent(context: Context): Intent {
            val component = ComponentName(context, MediaSessionAccessService::class.java)
            return if (android.os.Build.VERSION.SDK_INT >= 30) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString())
            } else {
                Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        /** Podium's own App info page: where a sideloaded app's restricted settings are allowed. */
        fun appInfoIntent(context: Context): Intent =
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Where to get the app (its store page). */
        fun installIntent(appPackage: String): Intent =
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$appPackage")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
