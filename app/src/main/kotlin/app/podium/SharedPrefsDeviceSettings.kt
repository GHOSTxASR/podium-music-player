package app.podium

import android.content.Context
import androidx.core.content.edit
import app.podium.core.designsystem.shell.DeviceAppearance
import app.podium.core.designsystem.shell.FinishPreset
import app.podium.core.designsystem.theme.DisplayTheme
import app.podium.feature.settings.DeviceSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Device settings in a small private SharedPreferences file (a few scalars; no database needed). */
class SharedPrefsDeviceSettings(context: Context) : DeviceSettingsRepository {

    private val prefs = context.getSharedPreferences("device", Context.MODE_PRIVATE)

    private val _appearance = MutableStateFlow(
        DeviceAppearance(
            preset = prefs.getString(KEY_FINISH, null)
                ?.let { name -> FinishPreset.entries.firstOrNull { it.name == name } }
                ?: FinishPreset.GLASS,
            customArgb = prefs.getInt(KEY_CUSTOM, DeviceAppearance.DEFAULT_CUSTOM),
            grain = prefs.getFloat(KEY_GRAIN, DeviceAppearance.DEFAULT_GRAIN),
            display = prefs.getString(KEY_DISPLAY, null)
                ?.let { name -> DisplayTheme.entries.firstOrNull { it.name == name } }
                ?: DisplayTheme.GLASS,
        ),
    )
    override val appearance: StateFlow<DeviceAppearance> = _appearance.asStateFlow()

    private val _preview = MutableStateFlow<DeviceAppearance?>(null)
    override val preview: StateFlow<DeviceAppearance?> = _preview.asStateFlow()

    private val _startupSound = MutableStateFlow(prefs.getBoolean(KEY_SOUND, true))
    override val startupSound: StateFlow<Boolean> = _startupSound.asStateFlow()

    private val _haptics = MutableStateFlow(prefs.getBoolean(KEY_HAPTICS, true))
    override val haptics: StateFlow<Boolean> = _haptics.asStateFlow()

    private val _clicks = MutableStateFlow(prefs.getBoolean(KEY_CLICKS, false))
    override val clicks: StateFlow<Boolean> = _clicks.asStateFlow()

    override fun setHaptics(enabled: Boolean) {
        _haptics.value = enabled
        prefs.edit { putBoolean(KEY_HAPTICS, enabled) }
    }

    override fun setClicks(enabled: Boolean) {
        _clicks.value = enabled
        prefs.edit { putBoolean(KEY_CLICKS, enabled) }
    }

    override fun setAppearance(appearance: DeviceAppearance) {
        _appearance.value = appearance
        prefs.edit {
            putString(KEY_FINISH, appearance.preset.name)
            putInt(KEY_CUSTOM, appearance.customArgb)
            putFloat(KEY_GRAIN, appearance.grain)
            putString(KEY_DISPLAY, appearance.display.name)
        }
    }

    override fun setPreview(appearance: DeviceAppearance?) {
        _preview.value = appearance
    }

    override fun setStartupSound(enabled: Boolean) {
        _startupSound.value = enabled
        prefs.edit { putBoolean(KEY_SOUND, enabled) }
    }

    private companion object {
        const val KEY_FINISH = "finish"
        const val KEY_CUSTOM = "custom_argb"
        const val KEY_GRAIN = "grain"
        const val KEY_SOUND = "startup_sound"
        const val KEY_DISPLAY = "display_theme"
        const val KEY_HAPTICS = "haptics"
        const val KEY_CLICKS = "clicks"
    }
}
