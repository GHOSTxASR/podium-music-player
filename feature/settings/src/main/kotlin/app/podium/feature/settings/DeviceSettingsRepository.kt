package app.podium.feature.settings

import app.podium.core.designsystem.shell.DeviceAppearance
import kotlinx.coroutines.flow.StateFlow

/** The device's own settings: its finish, startup sound and touch feedback (D-26, D-32). */
interface DeviceSettingsRepository {
    val appearance: StateFlow<DeviceAppearance>

    /** A finish being tried on in Settings; the shell shows it instead of [appearance] while set. */
    val preview: StateFlow<DeviceAppearance?>
    val startupSound: StateFlow<Boolean>

    /** Haptics on the Wheel and buttons (on top of the system's own touch-feedback setting). */
    val haptics: StateFlow<Boolean>

    /** The Wheel's audible click. */
    val clicks: StateFlow<Boolean>

    fun setAppearance(appearance: DeviceAppearance)
    fun setPreview(appearance: DeviceAppearance?)
    fun setStartupSound(enabled: Boolean)
    fun setHaptics(enabled: Boolean)
    fun setClicks(enabled: Boolean)
}
