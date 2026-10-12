package app.podium.player.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the sleep timer is waiting for (audio-architecture.md §9, D-75). */
sealed interface SleepTimerState {
    data object Off : SleepTimerState

    /** The music stops at [endsAtMs], on the timer's clock; [minutes] is what was chosen. */
    data class Counting(val endsAtMs: Long, val minutes: Int) : SleepTimerState

    /** The music stops when the song playing now ends. */
    data object EndOfSong : SleepTimerState

    /** The music stops when the last song of the album playing now ends. */
    data object EndOfAlbum : SleepTimerState
}

/**
 * The sleep timer (audio-architecture.md §9, D-75): stops the music after a while, or at the end of
 * the song or the album. Shared by the UI, which sets it, and the playback engine, which carries it
 * out — so it keeps running with the screen off, for as long as the music plays.
 *
 * - Counting down, the last [fadeMs] fade the player's own volume ([volume]) to nothing; then
 *   [expired] fires and the music pauses. The phone's volume is never touched.
 * - After that, [volume] stays at nothing until the music plays again ([resumed]), so the pause
 *   always lands in silence.
 * - The end of a song or an album is the engine's to see; it calls [endReached] when it has paused there.
 */
class SleepTimer(
    private val scope: CoroutineScope,
    /** A clock that only moves forward (elapsed time since boot). */
    private val now: () -> Long,
    private val fadeMs: Long = 10_000,
    private val stepMs: Long = 100,
) {
    private val _state = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    private val _volume = MutableStateFlow(1f)

    /** The player's volume for the timer, 0–1: 1 except while fading out and after the timer ran out. */
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _expired = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Each time the timer runs out: the music pauses. */
    val expired: SharedFlow<Unit> = _expired.asSharedFlow()

    private var countdown: Job? = null

    /** Stop the music in [minutes]. */
    fun start(minutes: Int) {
        require(minutes > 0)
        val endsAt = now() + minutes * 60_000L
        replace(SleepTimerState.Counting(endsAt, minutes))
        countdown = scope.launch {
            delay((endsAt - fadeMs - now()).coerceAtLeast(0))
            // The fade: the player's volume falls with the time that's left.
            while (true) {
                val left = endsAt - now()
                if (left <= 0) break
                _volume.value = (left.toFloat() / fadeMs).coerceIn(0f, 1f)
                delay(minOf(stepMs, left))
            }
            _volume.value = 0f
            _state.value = SleepTimerState.Off
            countdown = null
            _expired.tryEmit(Unit)
        }
    }

    fun stopAtEndOfSong() = replace(SleepTimerState.EndOfSong)

    fun stopAtEndOfAlbum() = replace(SleepTimerState.EndOfAlbum)

    /** Turn the timer off; a fade under way is undone. */
    fun cancel() = replace(SleepTimerState.Off)

    /** The engine paused at the end of the song or album the timer was waiting for. */
    fun endReached() {
        if (_state.value == SleepTimerState.EndOfSong || _state.value == SleepTimerState.EndOfAlbum) {
            _state.value = SleepTimerState.Off
            _expired.tryEmit(Unit)
        }
    }

    /** The music plays again: full volume, whatever the timer did before. */
    fun resumed() {
        if (countdown == null || _state.value !is SleepTimerState.Counting) _volume.value = 1f
    }

    /** Time left on a countdown, or null when the timer isn't counting. */
    fun remainingMs(): Long? = (_state.value as? SleepTimerState.Counting)?.let { (it.endsAtMs - now()).coerceAtLeast(0) }

    private fun replace(state: SleepTimerState) {
        countdown?.cancel()
        countdown = null
        _volume.value = 1f
        _state.value = state
    }

    companion object {
        /** The countdowns offered, in minutes (audio-architecture.md §9). */
        val CHOICES = listOf(15, 30, 45, 60, 90)
    }
}

/** How the sleep timer is described, the same wherever it shows (copy rules: sentence case, no dots). */
object SleepTimerText {

    /** A countdown choice: "15 minutes", "1 hour", "1 hour 30 minutes". */
    fun choice(minutes: Int): String = when {
        minutes < 60 -> "$minutes minutes"
        minutes % 60 == 0 -> if (minutes == 60) "1 hour" else "${minutes / 60} hours"
        else -> "${minutes / 60} hour ${minutes % 60} minutes"
    }

    /** The timer at a glance (a Settings value): "Off", "23 min left", "After this song". */
    fun summary(state: SleepTimerState, remainingMs: Long?): String = when (state) {
        SleepTimerState.Off -> "Off"
        is SleepTimerState.Counting -> left(remainingMs ?: 0)
        SleepTimerState.EndOfSong -> "After this song"
        SleepTimerState.EndOfAlbum -> "After this album"
    }

    /** What Now Playing says while the timer is set; null when it's off. */
    fun note(state: SleepTimerState, remainingMs: Long?): String? = when (state) {
        SleepTimerState.Off -> null
        is SleepTimerState.Counting -> "Music stops in ${minutesLeft(remainingMs ?: 0)}"
        SleepTimerState.EndOfSong -> "Music stops after this song"
        SleepTimerState.EndOfAlbum -> "Music stops after this album"
    }

    private fun left(ms: Long): String = if (ms < 60_000) "Under a minute left" else "${minutesLeft(ms)} left"

    /** Rounded up, so "1 min" lasts until the last minute is over. */
    private fun minutesLeft(ms: Long): String {
        if (ms < 60_000) return "under a minute"
        val minutes = ((ms + 59_999) / 60_000).toInt()
        return if (minutes == 1) "1 min" else "$minutes min"
    }
}
