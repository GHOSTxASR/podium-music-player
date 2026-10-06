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

    fun setAppearance(appearance: DeviceAppearance)
    fun setPreview(appearance: DeviceAppearance?)
    fun setStartupSound(enabled: Boolean)
    fun setHaptics(enabled: Boolean)
    fun setClicks(enabled: Boolean)
    fun setAutoplay(enabled: Boolean)
    fun setOnlineRecommendations(enabled: Boolean)
    fun setAvoidRepeats(enabled: Boolean)
}
