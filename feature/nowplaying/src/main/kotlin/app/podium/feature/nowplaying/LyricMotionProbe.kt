package app.podium.feature.nowplaying

import android.util.Log
import kotlin.math.abs

/**
 * Diagnostics for the lyrics' motion (device testing): how evenly the drawing moves frame by frame,
 * from the raw position and from the [LyricClock], and how often the bass was measured where the
 * lyrics asked. Off unless `adb shell setprop log.tag.PodiumLyricsClock DEBUG`; then a summary every
 * few seconds, numbers only. Allocates nothing per frame.
 */
internal class LyricMotionProbe {
    private val enabled = Log.isLoggable(TAG, Log.DEBUG)
    private var lastNanos = -1L
    private var lastRaw = 0L
    private var lastSmooth = 0.0
    private var frames = 0
    private var rawError = 0.0
    private var smoothError = 0.0
    private var rawWorst = 0.0
    private var smoothWorst = 0.0
    private var rawUneven = 0
    private var smoothUneven = 0
    private var hits = 0
    private var misses = 0
    private var swellMax = 0f
    private var windowStart = 0L

    fun record(frameNanos: Long, rawMs: Long, smoothMs: Double, advancing: Boolean, jumped: Boolean, bass: Float, swell: Float) {
        if (!enabled) return
        if (lastNanos >= 0 && advancing && !jumped) {
            val frame = (frameNanos - lastNanos) / 1e6
            val raw = abs((rawMs - lastRaw) - frame)
            val smooth = abs((smoothMs - lastSmooth) - frame)
            rawError += raw
            smoothError += smooth
            rawWorst = maxOf(rawWorst, raw)
            smoothWorst = maxOf(smoothWorst, smooth)
            if (raw > frame * UNEVEN) rawUneven++
            if (smooth > frame * UNEVEN) smoothUneven++
            if (bass >= 0f) hits++ else misses++
            swellMax = maxOf(swellMax, swell)
            frames++
        }
        if (windowStart == 0L) windowStart = frameNanos
        lastNanos = frameNanos
        lastRaw = rawMs
        lastSmooth = smoothMs
        if (frames > 0 && frameNanos - windowStart > WINDOW_NANOS) {
            Log.d(
                TAG,
                "frames=$frames step error ms: raw mean=%.2f worst=%.1f uneven=%d | clock mean=%.2f worst=%.1f uneven=%d | bass hits=%d misses=%d swell max=%.2f"
                    .format(rawError / frames, rawWorst, rawUneven, smoothError / frames, smoothWorst, smoothUneven, hits, misses, swellMax),
            )
            frames = 0
            rawError = 0.0
            smoothError = 0.0
            rawWorst = 0.0
            smoothWorst = 0.0
            rawUneven = 0
            smoothUneven = 0
            hits = 0
            misses = 0
            swellMax = 0f
            windowStart = frameNanos
        }
    }

    private companion object {
        const val TAG = "PodiumLyricsClock"
        const val WINDOW_NANOS = 5_000_000_000L

        /** A frame whose step is off by more than a quarter of the frame is uneven. */
        const val UNEVEN = 0.25
    }
}
