@file:OptIn(UnstableApi::class)

package app.podium.player.service

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.podium.player.api.CrossfadeRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Crossfade (D-77): the end of one song and the start of the next overlap by [seconds].
 *
 * The queue, the session and the notification stay with the engine's one player ([main]); a
 * second, silent player ([helper]) only ever carries the outgoing song's last seconds:
 *
 * 1. A few seconds before the overlap, the helper starts the same song, silent, just ahead, and is
 *    brought exactly level with [main] by playing a little slower or faster. (Silent, so the
 *    change in pitch is never heard.)
 * 2. At the overlap, the same audio swaps from [main] to the helper in a few milliseconds, which
 *    nobody hears; [main] moves on to the next song, silent, and the queue moves with it.
 * 3. The helper fades the old song out while [main] fades the new one in from its start, on
 *    equal-power curves; then the helper stops.
 *
 * Anything the listener does meanwhile (pause, skip, seek, a call) ends the crossfade at once:
 * the helper stops and [main] plays at full volume. When it can't be done cleanly (the song is too
 * short, the next song is the album's next track, the second player can't keep up) the songs
 * simply follow each other as before.
 *
 * Main thread only, like the players.
 */
internal class Crossfader(
    private val main: ExoPlayer,
    /** Builds the second player: the same media pipeline, no audio focus, no notification. */
    private val newHelper: () -> ExoPlayer,
    /** What the rules need to know about the song at a queue position of [main]. */
    private val songAt: (Int) -> CrossfadeRules.Song?,
    /** The crossfade's part of [main]'s volume (the engine multiplies in the sleep timer's). */
    private val setMainShare: (Float) -> Unit,
    /** The crossfade's part of the helper's volume. */
    private val setHelperShare: (ExoPlayer, Float) -> Unit,
    private val scope: CoroutineScope,
    /** True while something else owns the end of this song (the sleep timer pausing there). */
    private val blocked: () -> Boolean = { false },
) {
    var seconds: Int = 0
        set(value) {
            field = value
            if (value <= 0) release() else watch()
        }

    private var helper: ExoPlayer? = null
    private var watcher: Job? = null
    private var phase = Phase.IDLE

    /** Set while [main] moves to the next song itself, so the move isn't taken for the listener's. */
    private var movingOn = false

    private enum class Phase { IDLE, LEVELLING, LEVEL, FADING }

    init {
        main.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady) stop()
                else watch()
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                // The listener skipped or sought: whatever was under way ends.
                if (reason == Player.DISCONTINUITY_REASON_SEEK && !movingOn) stop()
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) = stop()
        })
    }

    /** The helper player, while one exists (the engine keeps its equaliser in step). */
    val helperPlayer: ExoPlayer? get() = helper

    /** Look, while music plays, for the moment to start. */
    private fun watch() {
        if (seconds <= 0 || watcher?.isActive == true) return
        watcher = scope.launch {
            while (isActive && seconds > 0) {
                tick()
                delay(if (phase == Phase.FADING || phase == Phase.LEVELLING) FAST_TICK_MS else TICK_MS)
            }
        }
    }

    private var fadeStartedAt = 0L
    private var fadeLengthMs = 0L
    private var nextStartedAt = 0L

    private fun tick() {
        if (!main.playWhenReady) return
        when (phase) {
            Phase.IDLE -> {
                val remaining = remainingMs() ?: return
                val overlap = seconds * 1_000L
                if (remaining > overlap + PREPARE_AHEAD_MS || remaining < MIN_FADE_MS) return
                if (!applies()) return
                startLevelling()
            }
            Phase.LEVELLING, Phase.LEVEL -> {
                val h = helper ?: return stop()
                if (blocked()) return stop() // e.g. the sleep timer now ends with this song
                val remaining = remainingMs() ?: return stop()
                if (main.currentMediaItemIndex != levellingIndex) return stop()
                if (h.isPlaying) {
                    val drift = h.currentPosition - main.currentPosition
                    if (kotlin.math.abs(drift) <= CrossfadeRules.IN_STEP_MS) {
                        if (h.playbackParameters.speed != 1f) h.playbackParameters = PlaybackParameters.DEFAULT
                        phase = Phase.LEVEL
                    } else {
                        h.playbackParameters = PlaybackParameters(CrossfadeRules.catchUpSpeed(drift))
                        phase = Phase.LEVELLING
                    }
                }
                when {
                    phase == Phase.LEVEL && remaining <= seconds * 1_000L -> beginFade(remaining)
                    remaining < MIN_FADE_MS -> stop() // couldn't get level in time: the songs just follow each other
                }
            }
            Phase.FADING -> {
                val h = helper ?: return stop()
                val now = SystemClock.elapsedRealtime()
                // The old song fades out from the moment of the swap (and is silent once it ends)…
                val p = ((now - fadeStartedAt).toFloat() / fadeLengthMs).coerceIn(0f, 1f)
                setHelperShare(h, if (h.playbackState == Player.STATE_ENDED) 0f else CrossfadeRules.fadeOut(p))
                // …the new one fades in from when it really starts playing, not from the seek.
                if (nextStartedAt == 0L && main.isPlaying) nextStartedAt = now
                val q = if (nextStartedAt == 0L) 0f else ((now - nextStartedAt).toFloat() / fadeLengthMs).coerceIn(0f, 1f)
                setMainShare(CrossfadeRules.fadeIn(q))
                // Done once the new song is at full volume (by then the old one has faded: p ≥ q),
                // or if the new one still hasn't started long after the old one ended.
                if (q >= 1f || (nextStartedAt == 0L && now - fadeStartedAt > fadeLengthMs * 2)) finish()
            }
        }
    }

    private var levellingIndex = C.INDEX_UNSET

    private fun remainingMs(): Long? {
        val duration = main.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return null
        return duration - main.currentPosition
    }

    private fun applies(): Boolean {
        if (blocked()) return false
        val index = main.currentMediaItemIndex
        val next = main.nextMediaItemIndex
        if (index == C.INDEX_UNSET || next == C.INDEX_UNSET || next == index) return false
        val current = songAt(index) ?: return false
        return CrossfadeRules.applies(seconds, current, songAt(next), repeatOne = main.repeatMode == Player.REPEAT_MODE_ONE)
    }

    private fun startLevelling() {
        val item = main.currentMediaItem ?: return
        val h = helper ?: newHelper().also { helper = it }
        levellingIndex = main.currentMediaItemIndex
        setHelperShare(h, 0f)
        h.playbackParameters = PlaybackParameters.DEFAULT
        // A little ahead of [main], to allow for its start; levelling takes care of the rest.
        h.setMediaItem(item, main.currentPosition + LEAD_MS)
        h.prepare()
        h.playWhenReady = true
        phase = Phase.LEVELLING
    }

    private fun beginFade(remaining: Long) {
        val h = helper ?: return stop()
        // The same audio, level in both players: swap it to the helper, unheard.
        setHelperShare(h, 1f)
        setMainShare(0f)
        fadeLengthMs = remaining.coerceAtLeast(MIN_FADE_MS)
        fadeStartedAt = SystemClock.elapsedRealtime()
        nextStartedAt = 0L
        phase = Phase.FADING
        movingOn = true
        try {
            main.seekToNextMediaItem()
        } finally {
            movingOn = false
        }
    }

    private fun finish() {
        helper?.let {
            it.playWhenReady = false
            it.stop()
            it.clearMediaItems()
        }
        phase = Phase.IDLE
        setMainShare(1f)
    }

    /** End anything under way: the helper falls silent, [main] plays at full volume. */
    fun stop() {
        if (phase != Phase.IDLE) finish()
        setMainShare(1f)
    }

    fun release() {
        stop()
        watcher?.cancel()
        watcher = null
        helper?.release()
        helper = null
    }

    private companion object {
        const val TICK_MS = 250L
        const val FAST_TICK_MS = 30L
        /** The helper starts levelling this long before the overlap. */
        const val PREPARE_AHEAD_MS = 4_000L
        const val LEAD_MS = 150L
        const val MIN_FADE_MS = 1_000L
    }
}
