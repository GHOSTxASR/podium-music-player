package app.podium.feature.settings

import app.podium.core.designsystem.shell.DeviceAppearance
import app.podium.player.api.AudioEffects
import app.podium.player.api.EqualizerSettings
import kotlinx.coroutines.flow.MutableStateFlow
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

    /** Autoplay (D-34): keep online music going, from the source's recommendations, avoiding repeats. */
    val autoplay: StateFlow<Boolean>
    val onlineRecommendations: StateFlow<Boolean>
    val avoidRepeats: StateFlow<Boolean>

    /**
     * After handing a song to the app that plays online music, bring Podium straight back to the
     * front (true) or leave the app showing (false). YOUTUBE_MUSIC_ARCHITECTURE §8.2.
     */
    val stayInPodium: StateFlow<Boolean>

    fun setStayInPodium(enabled: Boolean)

    /**
     * Online lyrics (D-11): whether Podium may send a song's title, artist, album and length to the
     * lyrics service. Asked once, the first time lyrics are opened; changeable in Settings.
     */
    val onlineLyrics: StateFlow<Boolean>

    fun setOnlineLyrics(enabled: Boolean)

    /**
     * Typing (D-45): the Podium keyboard, which the Wheel turns into (true), or the phone's own
     * keyboard (false).
     */
    val podiumKeyboard: StateFlow<Boolean>

    fun setPodiumKeyboard(enabled: Boolean)

    /** Settings ▸ Equaliser and Crossfade (D-76, D-77): how Podium's own player shapes the sound. */
    val effects: StateFlow<AudioEffects>

    fun setEqualizer(settings: EqualizerSettings)

    /** Seconds of overlap between songs; 0 is off. */
    fun setCrossfade(seconds: Int)

    /** Settings ▸ Equaliser ▸ Effect (D-89). */
    fun setSoundEffect(effect: app.podium.player.api.SoundEffect) {}

    /**
     * Full screen (D-85): Android's status and navigation bars hidden (a swipe from the edge shows
     * them for a moment), and the device's own title bar shows the time and, when [titleBarBattery],
     * the battery.
     */
    val immersive: StateFlow<Boolean> get() = Off

    fun setImmersive(enabled: Boolean) {}

    /** In full screen, whether the title bar shows the battery (the time always shows). */
    val titleBarBattery: StateFlow<Boolean> get() = On

    fun setTitleBarBattery(shown: Boolean) {}

    fun setAppearance(appearance: DeviceAppearance)
    fun setPreview(appearance: DeviceAppearance?)
    fun setStartupSound(enabled: Boolean)
    fun setHaptics(enabled: Boolean)
    fun setClicks(enabled: Boolean)
    fun setAutoplay(enabled: Boolean)
    fun setOnlineRecommendations(enabled: Boolean)
    fun setAvoidRepeats(enabled: Boolean)
}

private val Off: StateFlow<Boolean> = MutableStateFlow(false)
private val On: StateFlow<Boolean> = MutableStateFlow(true)
