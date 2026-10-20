@file:OptIn(UnstableApi::class)

package app.podium.player.service

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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
 * **Carrying a song from a better copy (D-92).** The same second player lets a song that started
 * from the catalogue move to a custom source's copy of the same recording mid-song, unheard but for
 * a short crossfade ([carry]): the helper loads that copy a few seconds ahead, starts as [main]
 * reaches that point, is brought level, and the sound crossfades over to it. [main] then plays on,
 * silent and kept level with the helper, so the queue, position, session and lyrics stay where
 * they are. At the next pause or seek, or when the song ends, the carry ends and the engine decides
 * what [main] does ([onCarryEnded]). Crossfade at the end of a carried song works as usual, with the
 * helper fading out the copy it carries.
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
    /** A carried song is now heard from the helper (D-92): the queue item's media id. */
    private val onCarrying: (String) -> Unit = {},
    /** A carry ended, or never got going (D-92). */
    private val onCarryEnded: (CarryEnd) -> Unit = {},
) {
    var seconds: Int = 0
        set(value) {
            field = value
            // Crossfade turned off leaves a carry under way alone; it needs the helper.
            if (value <= 0) {
                if (!carrying) release()
            } else {
                watch()
            }
        }

    /** Why a carry ended. */
    enum class CarryReason { PAUSED, SOUGHT, ENDED, MOVED_ON, GAVE_UP, FAILED, CANCELLED }

    /**
     * A carry that ended: the queue item's [uid], whether its copy was being [heard], and where the
     * helper was in it ([positionMs]).
     */
    data class CarryEnd(val uid: String, val reason: CarryReason, val heard: Boolean, val positionMs: Long)

    private var helper: ExoPlayer? = null
    private var watcher: Job? = null
    private var phase = Phase.IDLE

    /** Set while [main] moves to the next song itself, so the move isn't taken for the listener's. */
    private var movingOn = false

    private enum class Phase { IDLE, LEVELLING, LEVEL, FADING, CARRY_LOADING, CARRY_LEVELLING, CARRY_FADING, CARRYING }

    private val carrying: Boolean
        get() = phase == Phase.CARRY_LOADING || phase == Phase.CARRY_LEVELLING || phase == Phase.CARRY_FADING || phase == Phase.CARRYING

    init {
        main.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady) {
                    if (carrying) endCarry(CarryReason.PAUSED) else stop()
                } else {
                    watch()
                }
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                when {
                    movingOn -> Unit
                    // The listener skipped or sought: whatever was under way ends.
                    reason == Player.DISCONTINUITY_REASON_SEEK -> if (carrying) endCarry(CarryReason.SOUGHT) else stop()
                    // The carried song came to its end without a crossfade: [main] has moved on.
                    reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION && carrying -> endCarry(CarryReason.ENDED)
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (carrying) endCarry(CarryReason.FAILED) else stop()
            }
        })
    }

    /** The helper player, while one exists (the engine keeps its equaliser in step). */
    val helperPlayer: ExoPlayer? get() = helper

    /** Look, while music plays, for the moment to start. */
    private fun watch() {
        if ((seconds <= 0 && !carrying) || watcher?.isActive == true) return
        watcher = scope.launch {
            while (isActive && (seconds > 0 || carrying)) {
                tick()
                delay(if (phase == Phase.IDLE || phase == Phase.LEVEL || phase == Phase.CARRYING) TICK_MS else FAST_TICK_MS)
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
                if (seconds <= 0) return
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
            Phase.CARRY_LOADING, Phase.CARRY_LEVELLING, Phase.CARRY_FADING, Phase.CARRYING -> carryTick()
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
        if (carrying) return endCarry(CarryReason.CANCELLED)
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

    // --- Carrying a song from a better copy (D-92) ---------------------------------------------------

    private var carryUid: String? = null
    private var carryIndex = C.INDEX_UNSET
    private var carryTargetMs = 0L
    private var carryTries = 0
    private var carryPhaseStartedAt = 0L

    /**
     * Start carrying the song [main] is playing from [item]: another copy of the same recording
     * (D-92). False when it can't start now — nothing else may be under way, the song must be
     * playing, and enough of it must be left; the engine then waits for a pause or seek.
     */
    fun carry(item: MediaItem): Boolean {
        if (phase != Phase.IDLE || !main.playWhenReady) return false
        val remaining = remainingMs() ?: return false
        if (remaining < CARRY_MIN_REMAINING_MS) return false
        val uid = main.currentMediaItem?.mediaId ?: return false
        val h = helper ?: newHelper().also { helper = it }
        carryUid = uid
        carryIndex = main.currentMediaItemIndex
        carryTries = 0
        carryTargetMs = main.currentPosition + CARRY_AHEAD_MS
        setHelperShare(h, 0f)
        h.playbackParameters = PlaybackParameters.DEFAULT
        // Loaded ahead and held there: it starts only once [main] gets to that point.
        h.playWhenReady = false
        h.setMediaItem(item, carryTargetMs)
        h.prepare()
        phase = Phase.CARRY_LOADING
        carryPhaseStartedAt = SystemClock.elapsedRealtime()
        watch()
        return true
    }

    private fun carryTick() {
        val h = helper ?: return endCarry(CarryReason.GAVE_UP)
        if (main.currentMediaItemIndex != carryIndex) return endCarry(CarryReason.MOVED_ON)
        if (h.playerError != null) return endCarry(CarryReason.GAVE_UP)
        val now = SystemClock.elapsedRealtime()
        when (phase) {
            Phase.CARRY_LOADING -> {
                val position = main.currentPosition
                when {
                    position > carryTargetMs + CARRY_MISSED_MS -> retarget(h)
                    h.playbackState == Player.STATE_READY && position >= carryTargetMs - CARRY_START_EARLY_MS -> {
                        h.playWhenReady = true
                        phase = Phase.CARRY_LEVELLING
                        carryPhaseStartedAt = now
                    }
                }
            }
            Phase.CARRY_LEVELLING -> {
                if (h.isPlaying) {
                    val drift = h.currentPosition - main.currentPosition
                    if (kotlin.math.abs(drift) <= CrossfadeRules.IN_STEP_MS) {
                        h.playbackParameters = PlaybackParameters.DEFAULT
                        phase = Phase.CARRY_FADING
                        fadeStartedAt = now
                        return
                    }
                    // Still silent: the helper may play a little slower or faster to get level.
                    h.playbackParameters = PlaybackParameters(CrossfadeRules.catchUpSpeed(drift))
                }
                if (now - carryPhaseStartedAt > CARRY_LEVEL_TIMEOUT_MS) endCarry(CarryReason.GAVE_UP)
            }
            Phase.CARRY_FADING -> {
                // Two copies of one recording: a short equal-power crossfade covers any difference
                // in where their audio starts.
                val p = ((now - fadeStartedAt).toFloat() / CARRY_FADE_MS).coerceIn(0f, 1f)
                setMainShare(CrossfadeRules.fadeOut(p))
                setHelperShare(h, CrossfadeRules.fadeIn(p))
                if (p >= 1f) {
                    phase = Phase.CARRYING
                    carryUid?.let(onCarrying)
                }
            }
            Phase.CARRYING -> {
                // The copy ended first (they can differ by a second or two): [main] plays the rest.
                if (h.playbackState == Player.STATE_ENDED) setMainShare(1f)
                // [main] follows the helper, silently, so the position everyone sees is what's heard.
                val drift = main.currentPosition - h.currentPosition
                val speed = if (kotlin.math.abs(drift) > CrossfadeRules.IN_STEP_MS * 2) CrossfadeRules.catchUpSpeed(drift) else 1f
                if (main.playbackParameters.speed != speed) main.playbackParameters = PlaybackParameters(speed)
                // Crossfade into the next song as usual, the helper fading out the copy it carries.
                val remaining = remainingMs()
                if (seconds > 0 && remaining != null && remaining <= seconds * 1_000L && applies()) {
                    val ended = CarryEnd(carryUid.orEmpty(), CarryReason.ENDED, heard = true, positionMs = h.currentPosition)
                    carryUid = null
                    main.playbackParameters = PlaybackParameters.DEFAULT
                    beginFade(remaining)
                    onCarryEnded(ended)
                }
            }
            else -> Unit
        }
    }

    /** [main] went past the point the copy was loaded at before it was ready: try further ahead. */
    private fun retarget(h: ExoPlayer) {
        carryTries++
        val remaining = remainingMs()
        if (carryTries > CARRY_MAX_TRIES || remaining == null || remaining < CARRY_MIN_REMAINING_MS) return endCarry(CarryReason.GAVE_UP)
        carryTargetMs = main.currentPosition + CARRY_AHEAD_MS * (1L shl carryTries)
        h.seekTo(carryTargetMs)
    }

    private fun endCarry(reason: CarryReason) {
        val uid = carryUid
        val heard = phase == Phase.CARRYING || phase == Phase.CARRY_FADING
        val position = helper?.currentPosition ?: 0L
        carryUid = null
        finish()
        if (main.playbackParameters.speed != 1f) main.playbackParameters = PlaybackParameters.DEFAULT
        // With crossfade off, the second player was only for this.
        if (seconds <= 0) {
            helper?.release()
            helper = null
        }
        uid?.let { onCarryEnded(CarryEnd(it, reason, heard, position)) }
    }

    private companion object {
        const val TICK_MS = 250L
        const val FAST_TICK_MS = 30L
        /** The helper starts levelling this long before the overlap. */
        const val PREPARE_AHEAD_MS = 4_000L
        const val LEAD_MS = 150L
        const val MIN_FADE_MS = 1_000L

        /** How far ahead the copy is loaded at first (D-92); further each time it isn't ready in time. */
        const val CARRY_AHEAD_MS = 5_000L
        const val CARRY_MAX_TRIES = 3

        /** Not worth it for a song's last seconds. */
        const val CARRY_MIN_REMAINING_MS = 15_000L
        const val CARRY_START_EARLY_MS = 40L
        const val CARRY_MISSED_MS = 400L
        const val CARRY_LEVEL_TIMEOUT_MS = 8_000L
        const val CARRY_FADE_MS = 400L
    }
}
