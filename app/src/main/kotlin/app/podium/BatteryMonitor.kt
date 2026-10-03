package app.podium

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import app.podium.core.designsystem.shell.BatteryLevel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** The battery's level and charging state, from the system's sticky battery broadcast (no permission needed). */
internal object BatteryMonitor {
    fun levels(context: Context): Flow<BatteryLevel> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                levelOf(intent)?.let { trySend(it) }
            }
        }
        val sticky = ContextCompat.registerReceiver(
            context.applicationContext,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        sticky?.let { levelOf(it)?.let(::trySend) }
        awaitClose { context.applicationContext.unregisterReceiver(receiver) }
    }.distinctUntilChanged()

    private fun levelOf(intent: Intent): BatteryLevel? {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return BatteryLevel(level * 100 / scale, charging)
    }
}
