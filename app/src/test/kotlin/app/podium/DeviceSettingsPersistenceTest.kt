package app.podium

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.podium.core.designsystem.shell.DeviceAppearance
import app.podium.core.designsystem.shell.FinishPreset
import app.podium.core.designsystem.shell.Glitter
import app.podium.core.designsystem.theme.DisplayBackground
import app.podium.core.designsystem.theme.DisplayTheme
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
    }

    @Test
    fun `values from a damaged or older file are read safely`() {
        context.getSharedPreferences("device", Context.MODE_PRIVATE).edit()
            .clear()
            .putString("display_font", "COMIC")
            .putString("display_background", "VIDEO")
            .putFloat("glitter_opacity", 7f)
            .putInt("display_solid", 0x00112233)
            .commit()
        val read = SharedPrefsDeviceSettings(context).appearance.value
        assertEquals(DisplayFont.CLASSIC, read.screen.font)
        assertEquals(DisplayBackground.NONE, read.screen.background)
        assertEquals(1f, read.glitter.opacity)
        assertEquals(0xFF112233.toInt(), read.screen.solidArgb, "always opaque")
    }
}
