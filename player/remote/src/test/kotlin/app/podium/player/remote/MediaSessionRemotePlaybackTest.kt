package app.podium.player.remote

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageInfo
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.model.SourceId
import app.podium.core.model.TrackId
import app.podium.player.api.RemoteAccess
import app.podium.player.api.RemoteStart
import app.podium.sources.api.ControlsOwner
import app.podium.sources.api.PlaybackTarget
import app.podium.sources.api.QueueOwnership
import app.podium.sources.api.RemotePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class MediaSessionRemotePlaybackTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val appPackage = "com.example.musicapp"

    @After
    fun tearDown() = scope.cancel()

    private fun install() {
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = appPackage })
    }

    private fun grant() {
        val component = ComponentName(app, MediaSessionAccessService::class.java)
        Settings.Secure.putString(app.contentResolver, "enabled_notification_listeners", component.flattenToString())
    }

    private fun target(link: String) = PlaybackTarget.RemoteProvider(
        TrackId.of(SourceId("src"), "v1"),
        appPackage,
        link,
        RemotePolicy(QueueOwnership.PROVIDER, false, false, ControlsOwner.PROVIDER, requiresProviderApp = appPackage),
    )

    /** A session owned by the other app, as the session manager would list it. */
    private fun appSession(): Pair<MediaSession, MediaController> {
        val session = MediaSession(app, "other-app")
        val controller = MediaController(app, session.sessionToken)
        shadowOf(controller).setPackageName(appPackage)
        val manager = app.getSystemService(MediaSessionManager::class.java)
        shadowOf(manager).addController(controller)
        return session to controller
    }

    private fun playback(back: Intent? = null) = MediaSessionRemotePlayback(app, scope, appPackage) { back }

    @Test
    fun `access follows the app being installed and the listener's grant`() {
        assertEquals(RemoteAccess.NO_APP, playback().access.value)
        install()
        assertEquals(RemoteAccess.NEEDS_ACCESS, playback().access.value)
        grant()
        assertEquals(RemoteAccess.READY, playback().access.value)
    }

    @Test
    fun `without the app nothing is opened`() = runBlocking {
        val p = playback()
        assertEquals(RemoteStart.Failed(RemoteAccess.NO_APP), p.start(target("https://example.com/song/1")))
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test
    fun `a session that can't take links gets the app opened on the song, Podium coming back on top`() = runBlocking {
        install()
        val back = Intent(app, javaClass).setAction("podium.back")
        val p = playback(back)
        assertIs<RemoteStart.OpenedApp>(p.start(target("https://example.com/song/1")))
        val started = listOfNotNull(shadowOf(app).nextStartedActivity, shadowOf(app).nextStartedActivity)
        val opened = assertNotNull(started.firstOrNull { it.action == Intent.ACTION_VIEW })
        assertEquals("https://example.com/song/1", opened.dataString)
        assertEquals(appPackage, opened.`package`)
        assertTrue(started.any { it.action == "podium.back" })
    }

    @Test
    fun `the app's session is mirrored with its state, metadata and actions`() {
        install()
        grant()
        val (session, controller) = appSession()
        shadowOf(controller).setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "A Song")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "An Artist")
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "abcdefghijk")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, 200_000)
                .build(),
        )
        shadowOf(controller).setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 12_000, 1f)
                .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_SKIP_TO_NEXT)
                .setActiveQueueItemId(7)
                .build(),
        )
        val p = playback()
        ShadowLooper.idleMainLooper()
        val s = p.state.value
        assertTrue(s.connected)
        assertTrue(s.playing)
        assertEquals("A Song", s.title)
        assertEquals("abcdefghijk", s.mediaId)
        assertEquals(200_000L, s.durationMs)
        assertTrue(s.actions.seek && s.actions.next && s.actions.play && s.actions.pause)
        assertFalse(s.actions.previous)
        assertFalse(s.actions.playFromUri)
        assertEquals(7L, s.activeQueueItemId)
        session.release()
    }
}
