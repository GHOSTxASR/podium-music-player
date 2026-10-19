package app.podium

import android.content.res.Configuration
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Podium's own text size and weight (D-84), whatever the phone's font size and Bold text say. */
@RunWith(AndroidJUnit4::class)
class StandardTextTest {

    @Test
    fun `a large font size and bold text are set back to standard`() {
        val phone = Configuration().apply {
            setToDefaults()
            fontScale = 2f
            fontWeightAdjustment = 300
        }
        val override = assertNotNull(StandardText.override(phone))
        val applied = Configuration(phone).apply { updateFrom(override) }
        assertEquals(1f, applied.fontScale)
        assertEquals(0, applied.fontWeightAdjustment)
    }

    @Test
    fun `standard settings need nothing`() {
        val phone = Configuration().apply {
            setToDefaults()
            fontScale = 1f
            fontWeightAdjustment = 0
        }
        assertNull(StandardText.override(phone))
    }

    @Test
    fun `either one alone is set back`() {
        val large = Configuration().apply { setToDefaults(); fontScale = 1.3f }
        assertEquals(1f, Configuration(large).apply { updateFrom(StandardText.override(large)!!) }.fontScale)
        val bold = Configuration().apply { setToDefaults(); fontWeightAdjustment = 300 }
        assertEquals(0, Configuration(bold).apply { updateFrom(StandardText.override(bold)!!) }.fontWeightAdjustment)
    }
}
