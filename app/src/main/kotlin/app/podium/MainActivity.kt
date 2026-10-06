package app.podium

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import app.podium.sources.api.CapabilityAction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val graph: AppGraph get() = (application as PodiumApplication).graph

    private val asks by lazy { PermissionAsks(this) }

    /** The permission being asked for, to read Android's answer about asking again. */
    private var asking: String? = null

    /** When the listener's own press asked (not Podium's first-run ask), and when. */
    private var pressedAt = 0L

    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        graph.onPermissionsChanged()
        val permission = asking
        asking = null
        if (permission == null || granted) return@registerForActivityResult
        val canAskAgain = shouldShowRequestPermissionRationale(permission)
        asks.onAnswer(permission, granted = false, canAskAgain = canAskAgain)
        // Refused at once, with no question shown: Android had already stopped asking. The press
        // still leads somewhere: Podium's page in the system settings.
        val instant = pressedAt > 0 && SystemClock.elapsedRealtime() - pressedAt < NO_DIALOG_MS
        if (!canAskAgain && instant) openAppSettings()
        pressedAt = 0L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        asking = savedInstanceState?.getString(KEY_ASKING)
        graph.localPlayback.connect()
        graph.startListening()
        handleDebugIntent(intent, graph)
        applyDebugWindowCommand(intent, this)
        setContent {
            PodiumApp(graph, onSourceAction = ::perform)
        }
        // Without music access, Podium asks once by itself, as soon as it has switched on (the
        // system's own question, over the device); after that, "Allow music access" on Home asks.
        lifecycleScope.launch {
            graph.power.first { it == Power.ON }
            lifecycle.withResumed {
                val permission = graph.localSource.permission
                if (asks.shouldAskFirstTime(permission, graph.localSource.hasPermission())) {
                    asks.onAsked(permission)
                    ask(permission)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugIntent(intent, graph)
        applyDebugWindowCommand(intent, this)
    }

    override fun onResume() {
        super.onResume()
        // The user may have changed music access in system settings while we were away, or allowed
        // media control, or installed the app that plays online music.
        graph.recheckPermissions()
        if (graph.localSource.hasPermission()) asks.onGranted(graph.localSource.permission)
        graph.remotePlayback.refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        asking?.let { outState.putString(KEY_ASKING, it) }
    }

    private fun ask(permission: String) {
        asking = permission
        requestPermission.launch(permission)
    }

    /** Podium's page in the system settings, where Permissions allows music access. */
    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        runCatching { startActivity(intent) }.onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun perform(action: CapabilityAction) {
        when (action) {
            is CapabilityAction.RequestPermission -> {
                val granted = checkSelfPermission(action.permission) == PackageManager.PERMISSION_GRANTED
                // Android no longer shows the question: the system settings page is the only way.
                if (asks.isBlocked(action.permission, granted)) {
                    openAppSettings()
                } else {
                    pressedAt = SystemClock.elapsedRealtime()
                    ask(action.permission)
                }
            }
            is CapabilityAction.SignIn -> graph.onlineService.signIn()
            is CapabilityAction.InstallApp -> graph.onlineService.installApp()
        }
    }

    private companion object {
        const val KEY_ASKING = "asking_permission"

        /** An answer quicker than this came without a dialog (a person can't read and tap so fast). */
        const val NO_DIALOG_MS = 350L
    }
}
