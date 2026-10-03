package app.podium

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import app.podium.sources.api.CapabilityAction

class MainActivity : ComponentActivity() {

    private val graph: AppGraph get() = (application as PodiumApplication).graph

    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        graph.onPermissionsChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        graph.playbackController.connect()
        handleDebugIntent(intent, graph)
        applyDebugWindowCommand(intent, this)
        setContent {
            PodiumApp(graph, onSourceAction = ::perform)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugIntent(intent, graph)
        applyDebugWindowCommand(intent, this)
    }

    override fun onResume() {
        super.onResume()
        // The user may have changed music access in system settings while we were away.
        graph.recheckPermissions()
    }

    private fun perform(action: CapabilityAction) {
        when (action) {
            is CapabilityAction.RequestPermission -> requestPermission.launch(action.permission)
            // Sign-in and provider apps arrive with remote sources (S4+); nothing offers them yet.
            is CapabilityAction.SignIn, is CapabilityAction.InstallApp -> Unit
        }
    }
}
