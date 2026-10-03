package app.podium

import android.app.ActivityManager
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import app.podium.core.designsystem.shell.BootCheck
import java.text.NumberFormat

/**
 * The boot self-test's lines (D-32), measured from the phone it runs on: processor cores, memory,
 * the display, the current audio output, and the songs in the library. Sentence case throughout.
 */
internal object BootReport {
    fun checks(context: Context, songs: Int?): List<BootCheck> {
        val number = NumberFormat.getIntegerInstance()
        val memoryMb = context.getSystemService(ActivityManager::class.java)?.let { am ->
            ActivityManager.MemoryInfo().also(am::getMemoryInfo).totalMem / (1024 * 1024)
        } ?: 0L
        val metrics = context.resources.displayMetrics
        return listOf(
            BootCheck("Processor", "${Runtime.getRuntime().availableProcessors()} cores"),
            BootCheck("Memory", number.format(memoryMb) + " MB", countTo = memoryMb, unit = " MB"),
            BootCheck("Display", "${metrics.widthPixels} \u00d7 ${metrics.heightPixels}"),
            BootCheck("Click wheel", "ok"),
            BootCheck("Audio", output(context)),
            BootCheck("Library", if (songs == null) "reading" else if (songs == 1) "1 song" else number.format(songs) + " songs"),
        )
    }

    private fun output(context: Context): String {
        val devices = context.getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
        val types = devices.map { it.type }.toSet()
        return when {
            types.any { it == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it == AudioDeviceInfo.TYPE_BLE_HEADSET } -> "bluetooth"
            types.any { it == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it == AudioDeviceInfo.TYPE_WIRED_HEADSET || it == AudioDeviceInfo.TYPE_USB_HEADSET } -> "headphones"
            else -> "speaker"
        }
    }
}
