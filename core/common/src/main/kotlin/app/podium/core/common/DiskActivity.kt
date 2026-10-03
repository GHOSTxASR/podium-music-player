package app.podium.core.common

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The device's activity light (D-33): anything that reads or writes — opening a folder on the
 * paper, a library sync, loading artwork, a network request — pulses it, and the disk LED on the
 * body flickers like an old drive. Fire-and-forget and lossy by design: a burst of work is one
 * flicker, never a backlog.
 */
object DiskActivity {
    private val _pulses = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    val pulses: SharedFlow<Unit> = _pulses.asSharedFlow()

    fun pulse() {
        _pulses.tryEmit(Unit)
    }
}
