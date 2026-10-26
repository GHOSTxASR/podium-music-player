package app.podium

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.designsystem.shell.DeviceAppearance
import app.podium.core.designsystem.shell.FinishPreset
import app.podium.core.designsystem.shell.Glitter
import app.podium.core.designsystem.theme.DisplayBackground
import app.podium.core.designsystem.theme.DisplayTheme
import app.podium.core.designsystem.theme.LyricsBackground
import app.podium.core.designsystem.theme.LyricsTheme
import app.podium.core.designsystem.theme.TextContrast
import app.podium.core.designsystem.theme.VirtualDisplay
import app.podium.core.designsystem.type.DisplayFont
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Appearance survives a new process (D-41, PODIUM_CUSTOMIZATION.md §7). */
@RunWith(AndroidJUnit4::class)
class DeviceSettingsPersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `every appearance choice is kept across a restart`() {
        val chosen = DeviceAppearance(
            preset = FinishPreset.BURGUNDY,
            grain = 0.4f,
            display = DisplayTheme.CUSTOM,
            glitter = Glitter(enabled = true, amount = 0.6f, density = 0.7f, size = 0.2f, opacity = 0.35f, glow = 0.8f, tilt = false),
            screen = VirtualDisplay(
                font = DisplayFont.PIXEL,
                lyricsTheme = LyricsTheme.CONTINUOUS_LINE,
                lyricsBackground = LyricsBackground.BLACK,
                background = DisplayBackground.IMAGE,
                solidArgb = 0xFF305060.toInt(),
                imageUri = "content://media/picker/0/com.android.providers.media.photopicker/media/1000",
                imageOpacity = 0.25f,
                contrast = TextContrast.HIGH,
            ),
        )
        SharedPrefsDeviceSettings(context).setAppearance(chosen)
        assertEquals(chosen, SharedPrefsDeviceSettings(context).appearance.value)

        // Clearing the picture removes its reference; nothing else changes.
        val noPicture = chosen.copy(screen = chosen.screen.copy(background = DisplayBackground.SOLID, imageUri = null))
        SharedPrefsDeviceSettings(context).setAppearance(noPicture)
        assertEquals(noPicture, SharedPrefsDeviceSettings(context).appearance.value)
    }

    @Test
    fun `a fresh install looks exactly as before, with no glitter, the Classic font and the theme own display`() {
        context.getSharedPreferences("device", Context.MODE_PRIVATE).edit().clear().commit()
        val fresh = SharedPrefsDeviceSettings(context).appearance.value
        assertEquals(DeviceAppearance(), fresh)
        assertFalse(fresh.glitter.enabled)
        assertEquals(DisplayFont.CLASSIC, fresh.screen.font)
        assertEquals(DisplayBackground.NONE, fresh.screen.background)
        // The lyrics keep their classic look until the listener chooses another (D-81), turning
        // with each line (D-100).
        assertEquals(LyricsTheme.CLASSIC, fresh.screen.lyricsTheme)
        assertEquals(LyricsBackground.AUTOMATIC, fresh.screen.lyricsBackground)
    }

    @Test
    fun `the lyrics theme is kept on its own and restored after a restart`() {
        context.getSharedPreferences("device", Context.MODE_PRIVATE).edit().clear().commit()
        val settings = SharedPrefsDeviceSettings(context)
        val before = settings.appearance.value
        settings.setAppearance(before.copy(screen = before.screen.copy(lyricsTheme = LyricsTheme.CONTINUOUS_LINE)))
        val restored = SharedPrefsDeviceSettings(context).appearance.value
        assertEquals(LyricsTheme.CONTINUOUS_LINE, restored.screen.lyricsTheme)
        // Nothing else changed with it.
        assertEquals(before.copy(screen = before.screen.copy(lyricsTheme = LyricsTheme.CONTINUOUS_LINE)), restored)
        // And back to Classic.
        SharedPrefsDeviceSettings(context).setAppearance(restored.copy(screen = restored.screen.copy(lyricsTheme = LyricsTheme.CLASSIC)))
        assertEquals(LyricsTheme.CLASSIC, SharedPrefsDeviceSettings(context).appearance.value.screen.lyricsTheme)
    }

    @Test
    fun `values from a damaged or older file are read safely`() {
        context.getSharedPreferences("device", Context.MODE_PRIVATE).edit()
            .clear()
            .putString("display_font", "COMIC")
            .putString("display_background", "VIDEO")
            .putString("lyrics_theme", "NEON")
            .putString("lyrics_background", "PURPLE")
            .putFloat("glitter_opacity", 7f)
            .putInt("display_solid", 0x00112233)
            .commit()
        val read = SharedPrefsDeviceSettings(context).appearance.value
        assertEquals(DisplayFont.CLASSIC, read.screen.font)
        assertEquals(DisplayBackground.NONE, read.screen.background)
        assertEquals(LyricsTheme.CLASSIC, read.screen.lyricsTheme)
        assertEquals(LyricsBackground.AUTOMATIC, read.screen.lyricsBackground)
        assertEquals(1f, read.glitter.opacity)
        assertEquals(0xFF112233.toInt(), read.screen.solidArgb, "always opaque")
    }

    @Test
    fun `full screen and the title bar's battery are kept across a restart, off and shown at first`() {
        val fresh = SharedPrefsDeviceSettings(context)
        assertFalse(fresh.immersive.value, "full screen is off until chosen")
        assertEquals(true, fresh.titleBarBattery.value, "the battery shows once full screen is on")
        fresh.setImmersive(true)
        fresh.setTitleBarBattery(false)
        val again = SharedPrefsDeviceSettings(context)
        assertEquals(true, again.immersive.value)
        assertFalse(again.titleBarBattery.value)
    }
}
