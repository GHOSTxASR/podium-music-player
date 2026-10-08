package app.podium

import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * Device probe for the motion pass (D-65): drives the real app on the phone with real two-finger
 * touches — which `adb shell input` can't make — so the pinch into Podium's space, the spread back
 * and the Wheel can be watched and timed on the device (`dumpsys gfxinfo`, screen recordings).
 * Screenshots land in the app's external files (`Android/data/app.podium.debug/files/probe/`). Not a unit test: run it by hand on a
 * phone with the app installed, through `am instrument`, which keeps the listener's data
 * (`connectedAndroidTest` would uninstall the app):
 *
 * ```
 * adb shell am instrument -w -e class app.podium.MotionProbe [-e record true] app.podium.debug.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class MotionProbe {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation: UiAutomation = instrumentation.uiAutomation
    private val out: File get() = requireNotNull(instrumentation.targetContext.getExternalFilesDir("probe"))

    @Test
    fun pinchAndWheel() {
        val metrics = instrumentation.targetContext.resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        // The test library only, and the screen kept on, before anything is shown (as every device test).
        launch("library-test-only")
        SystemClock.sleep(9_000) // the start-up screen
        launch("library-test-only")
        launch("keep-screen-on")
        SystemClock.sleep(800)
        // Recorded only now that Podium is in front (-e record true): /sdcard/podium_probe.mp4.
        val recording = if (InstrumentationRegistry.getArguments().getString("record") == "true") {
            automation.executeShellCommand("screenrecord --bit-rate 20000000 --time-limit 14 /sdcard/podium_probe.mp4")
        } else null

        val cx = w / 2f
        val cy = h * 0.36f
        // In: fingers from 46 % of the width apart to 12 %, over 450 ms; hold; screenshot.
        pinch(cx, cy, from = w * 0.46f, to = w * 0.12f, millis = 450)
        SystemClock.sleep(900)
        shot("1-in-space")
        // Out: a spread on the object brings it back.
        pinch(w * 0.22f, h * 0.45f, from = w * 0.16f, to = w * 0.42f, millis = 400)
        SystemClock.sleep(900)
        shot("2-back-flat")
        // A little way in (recognised, short of a third) and held still before letting go: it springs back.
        pinch(cx, cy, from = w * 0.46f, to = w * 0.33f, millis = 250, holdMillis = 400)
        SystemClock.sleep(900)
        shot("3-small-pinch-back")
        // A quick, short flick in — let go at a tenth of the way: its speed carries it into the space.
        pinch(cx, cy, from = w * 0.46f, to = w * 0.34f, millis = 60)
        SystemClock.sleep(900)
        shot("4-flick-in")
        launch("space-leave")
        SystemClock.sleep(900)

        // The Wheel (the device's own layout: its centre is the touch probe's to find on screen).
        val wheel = floatArrayOf(w / 2f, h * 0.8253f)
        val r = w * 0.213f
        turn(wheel, r, fromDeg = -60.0, toDeg = 60.0, millis = 900) // slowly, 6 detents
        turn(wheel, r, fromDeg = 60.0, toDeg = -60.0, millis = 180) // fast back
        turn(wheel, r, fromDeg = -60.0, toDeg = 40.0, millis = 300, reverseAtDeg = 20.0) // reverse mid-turn
        SystemClock.sleep(600)
        shot("5-wheel")
        // Let the recording finish (its output closes when screenrecord exits).
        recording?.let { ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } }
    }

    private fun launch(command: String) {
        val intent = Intent().setClassName(instrumentation.targetContext.packageName, "app.podium.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("podium.debug", command)
        instrumentation.targetContext.startActivity(intent)
        SystemClock.sleep(300)
    }

    /** Two fingers either side of ([cx], [cy]), [from] px apart to [to] px apart, every 8 ms. */
    private fun pinch(cx: Float, cy: Float, from: Float, to: Float, millis: Long, holdMillis: Long = 0) {
        val down = SystemClock.uptimeMillis()
        fun at(gap: Float) = listOf(cx - gap / 2f to cy, cx + gap / 2f to cy)
        send(MotionEvent.ACTION_DOWN, at(from).take(1), down)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), at(from), down)
        val steps = (millis / 8).coerceAtLeast(2)
        for (i in 1..steps) {
            SystemClock.sleep(8)
            send(MotionEvent.ACTION_MOVE, at(from + (to - from) * i / steps), down)
        }
        if (holdMillis > 0) SystemClock.sleep(holdMillis)
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), at(to), down)
        send(MotionEvent.ACTION_UP, at(to).take(1), down)
    }

    /** One finger around the Wheel's ring at radius [r], clockwise degrees from 12 o'clock. */
    private fun turn(centre: FloatArray, r: Float, fromDeg: Double, toDeg: Double, millis: Long, reverseAtDeg: Double? = null) {
        fun at(deg: Double) = listOf((centre[0] + r * sin(Math.toRadians(deg))).toFloat() to (centre[1] - r * cos(Math.toRadians(deg))).toFloat())
        val down = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, at(fromDeg), down)
        val path = if (reverseAtDeg == null) listOf(fromDeg to toDeg) else listOf(fromDeg to toDeg, toDeg to reverseAtDeg)
        val total = path.sumOf { kotlin.math.abs(it.second - it.first) }
        var last = fromDeg
        for ((a, b) in path) {
            val steps = ((millis * kotlin.math.abs(b - a) / total) / 8).toInt().coerceAtLeast(2)
            for (i in 1..steps) {
                SystemClock.sleep(8)
                last = a + (b - a) * i / steps
                send(MotionEvent.ACTION_MOVE, at(last), down)
            }
        }
        send(MotionEvent.ACTION_UP, at(last), down)
        SystemClock.sleep(300)
    }

    private fun send(action: Int, pointers: List<Pair<Float, Float>>, downTime: Long) {
        val props = Array(pointers.size) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(pointers.size) { i -> MotionEvent.PointerCoords().apply { x = pointers[i].first; y = pointers[i].second; pressure = 1f; size = 1f } }
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, pointers.size, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        automation.injectInputEvent(event, true)
        event.recycle()
    }

    private fun shot(name: String) {
        val bitmap = automation.takeScreenshot() ?: return
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
    }
}
