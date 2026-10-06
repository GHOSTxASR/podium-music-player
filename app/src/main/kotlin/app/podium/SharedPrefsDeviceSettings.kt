package app.podium

import android.content.Context
import androidx.core.content.edit
import app.podium.core.designsystem.shell.DeviceAppearance
import app.podium.core.designsystem.shell.FinishPreset
import app.podium.core.designsystem.shell.Glitter
import app.podium.core.designsystem.theme.DisplayBackground
import app.podium.core.designsystem.theme.DisplayTheme
import app.podium.core.designsystem.theme.TextContrast
import app.podium.core.designsystem.theme.VirtualDisplay
import app.podium.core.designsystem.type.DisplayFont
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
            glitter = Glitter(
                enabled = prefs.getBoolean(KEY_GLITTER, false),
                amount = prefs.getFloat(KEY_GLITTER_AMOUNT, Glitter.DEFAULT_AMOUNT).coerceIn(0f, 1f),
                density = prefs.getFloat(KEY_GLITTER_DENSITY, Glitter.DEFAULT_DENSITY).coerceIn(0f, 1f),
                size = prefs.getFloat(KEY_GLITTER_SIZE, Glitter.DEFAULT_SIZE).coerceIn(0f, 1f),
                opacity = prefs.getFloat(KEY_GLITTER_OPACITY, Glitter.DEFAULT_OPACITY).coerceIn(0f, 1f),
                glow = prefs.getFloat(KEY_GLITTER_GLOW, Glitter.DEFAULT_GLOW).coerceIn(0f, 1f),
                tilt = prefs.getBoolean(KEY_GLITTER_TILT, true),
            ),
            screen = VirtualDisplay(
                font = enumOf(prefs.getString(KEY_DISPLAY_FONT, null), DisplayFont.CLASSIC),
                background = enumOf(prefs.getString(KEY_DISPLAY_BACKGROUND, null), DisplayBackground.NONE),
                solidArgb = prefs.getInt(KEY_DISPLAY_SOLID, VirtualDisplay.DEFAULT_SOLID) or OPAQUE,
                imageUri = prefs.getString(KEY_DISPLAY_IMAGE, null),
                imageOpacity = prefs.getFloat(KEY_DISPLAY_IMAGE_OPACITY, VirtualDisplay.DEFAULT_IMAGE_OPACITY).coerceIn(0f, 1f),
                contrast = enumOf(prefs.getString(KEY_DISPLAY_CONTRAST, null), TextContrast.STANDARD),
            ),
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

    private val _autoplay = MutableStateFlow(prefs.getBoolean(KEY_AUTOPLAY, true))
    override val autoplay: StateFlow<Boolean> = _autoplay.asStateFlow()
    private val _recommendations = MutableStateFlow(prefs.getBoolean(KEY_RECOMMENDATIONS, true))
    override val onlineRecommendations: StateFlow<Boolean> = _recommendations.asStateFlow()
    private val _avoidRepeats = MutableStateFlow(prefs.getBoolean(KEY_AVOID_REPEATS, true))
    override val avoidRepeats: StateFlow<Boolean> = _avoidRepeats.asStateFlow()

    private val _stayInPodium = MutableStateFlow(prefs.getBoolean(KEY_STAY_IN_PODIUM, true))
    override val stayInPodium: StateFlow<Boolean> = _stayInPodium.asStateFlow()

    override fun setStayInPodium(enabled: Boolean) {
        _stayInPodium.value = enabled
        prefs.edit { putBoolean(KEY_STAY_IN_PODIUM, enabled) }
    }

    private val _onlineLyrics = MutableStateFlow(prefs.getBoolean(KEY_ONLINE_LYRICS, false))
    override val onlineLyrics: StateFlow<Boolean> = _onlineLyrics.asStateFlow()

    override fun setOnlineLyrics(enabled: Boolean) {
        _onlineLyrics.value = enabled
        prefs.edit { putBoolean(KEY_ONLINE_LYRICS, enabled) }
    }

    private val _podiumKeyboard = MutableStateFlow(prefs.getBoolean(KEY_PODIUM_KEYBOARD, true))
    override val podiumKeyboard: StateFlow<Boolean> = _podiumKeyboard.asStateFlow()

    override fun setPodiumKeyboard(enabled: Boolean) {
        _podiumKeyboard.value = enabled
        prefs.edit { putBoolean(KEY_PODIUM_KEYBOARD, enabled) }
    }

    override fun setAutoplay(enabled: Boolean) {
        _autoplay.value = enabled
        prefs.edit { putBoolean(KEY_AUTOPLAY, enabled) }
    }

    override fun setOnlineRecommendations(enabled: Boolean) {
        _recommendations.value = enabled
        prefs.edit { putBoolean(KEY_RECOMMENDATIONS, enabled) }
    }

    override fun setAvoidRepeats(enabled: Boolean) {
        _avoidRepeats.value = enabled
        prefs.edit { putBoolean(KEY_AVOID_REPEATS, enabled) }
    }

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
            with(appearance.glitter) {
                putBoolean(KEY_GLITTER, enabled)
                putFloat(KEY_GLITTER_AMOUNT, amount)
                putFloat(KEY_GLITTER_DENSITY, density)
                putFloat(KEY_GLITTER_SIZE, size)
                putFloat(KEY_GLITTER_OPACITY, opacity)
                putFloat(KEY_GLITTER_GLOW, glow)
                putBoolean(KEY_GLITTER_TILT, tilt)
                remove(KEY_GLITTER_ANIMATED) // replaced by tilt (D-44)
            }
            with(appearance.screen) {
                putString(KEY_DISPLAY_FONT, font.name)
                putString(KEY_DISPLAY_BACKGROUND, background.name)
                putInt(KEY_DISPLAY_SOLID, solidArgb)
                // A reference only, never the picture itself (PODIUM_CUSTOMIZATION.md §7).
                if (imageUri != null) putString(KEY_DISPLAY_IMAGE, imageUri) else remove(KEY_DISPLAY_IMAGE)
                putFloat(KEY_DISPLAY_IMAGE_OPACITY, imageOpacity)
                putString(KEY_DISPLAY_CONTRAST, contrast.name)
            }
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
        const val KEY_AUTOPLAY = "autoplay"
        const val KEY_RECOMMENDATIONS = "online_recommendations"
        const val KEY_AVOID_REPEATS = "avoid_repeats"
        const val KEY_STAY_IN_PODIUM = "stay_in_podium"
        const val KEY_ONLINE_LYRICS = "online_lyrics"
        const val KEY_PODIUM_KEYBOARD = "podium_keyboard"
        const val KEY_GLITTER = "glitter"
        const val KEY_GLITTER_AMOUNT = "glitter_amount"
        const val KEY_GLITTER_DENSITY = "glitter_density"
        const val KEY_GLITTER_SIZE = "glitter_size"
        const val KEY_GLITTER_OPACITY = "glitter_opacity"
        const val KEY_GLITTER_ANIMATED = "glitter_animated"
        const val KEY_GLITTER_GLOW = "glitter_glow"
        const val KEY_GLITTER_TILT = "glitter_tilt"
        const val KEY_DISPLAY_FONT = "display_font"
        const val KEY_DISPLAY_BACKGROUND = "display_background"
        const val KEY_DISPLAY_SOLID = "display_solid"
        const val KEY_DISPLAY_IMAGE = "display_image_uri"
        const val KEY_DISPLAY_IMAGE_OPACITY = "display_image_opacity"
        const val KEY_DISPLAY_CONTRAST = "display_contrast"
        const val OPAQUE = 0xFF000000.toInt()

        inline fun <reified E : Enum<E>> enumOf(name: String?, default: E): E =
            name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
    }
}
