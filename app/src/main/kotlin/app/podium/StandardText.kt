package app.podium

import android.content.res.Configuration
import android.os.Build

/**
 * Podium draws at its own text size and weight (D-84): the phone's font size and Bold text settings
 * don't apply inside it. The whole interface is a device with a fixed layout (D-26) — a Wheel,
 * a display, a power button — sized by Podium's own type scale. Scaled by the system, its words
 * outgrew their places ("MENU" broke onto two lines at 200 %) and, on a listener's phone set to large
 * text, the Wheel's and the power button's symbols turned into empty rectangles.
 *
 * Applied to the main activity as an override of its configuration, so Compose sees a font scale
 * of 1 and no weight adjustment. Changing the system's text size recreates the activity, which
 * applies it again. Screen readers are unaffected.
 */
internal object StandardText {

    /** The override that sets [base]'s text back to standard, or null when it already is. */
    fun override(base: Configuration): Configuration? {
        val scaled = base.fontScale != 1f
        val bolder = Build.VERSION.SDK_INT >= 31 && base.fontWeightAdjustment != 0 &&
            base.fontWeightAdjustment != Configuration.FONT_WEIGHT_ADJUSTMENT_UNDEFINED
        if (!scaled && !bolder) return null
        return Configuration().apply {
            fontScale = 1f
            if (Build.VERSION.SDK_INT >= 31) fontWeightAdjustment = 0
        }
    }
}
