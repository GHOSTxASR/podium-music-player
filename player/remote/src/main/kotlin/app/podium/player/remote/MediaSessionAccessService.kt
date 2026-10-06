package app.podium.player.remote

import android.service.notification.NotificationListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The key Android asks for before an app may see other apps' media sessions
 * (`MediaSessionManager.getActiveSessions`). Podium needs it for one thing: controlling and
 * mirroring the music app that plays remote songs (YOUTUBE_MUSIC_ARCHITECTURE.md §8.2).
 *
 * It deliberately handles no notifications: `onNotificationPosted` and `onNotificationRemoved` are
 * not overridden, nothing is read, stored or logged (security.md, privacy). Its only job is to exist
 * and to say when the grant comes and goes.
 */
class MediaSessionAccessService : NotificationListenerService() {

    override fun onListenerConnected() {
        connectedState.value = true
    }

    override fun onListenerDisconnected() {
        connectedState.value = false
    }

    companion object {
        private val connectedState = MutableStateFlow(false)

        /** Whether the system has the listener bound (the grant is active). */
        val connected: StateFlow<Boolean> = connectedState.asStateFlow()
    }
}
