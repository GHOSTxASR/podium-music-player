package app.podium.feature.settings

import app.podium.core.designsystem.shell.DeviceAppearance
import kotlinx.coroutines.flow.StateFlow

/** The device's own settings: its finish and its startup sound (D-26). */
interface DeviceSettingsRepository {
    val appearance: StateFlow<DeviceAppearance>

    /** A finish being tried on in Settings; the shell shows it instead of [appearance] while set. */
    val preview: StateFlow<DeviceAppearance?>
    val startupSound: StateFlow<Boolean>

    fun setAppearance(appearance: DeviceAppearance)
    fun setPreview(appearance: DeviceAppearance?)
    fun setStartupSound(enabled: Boolean)
}
