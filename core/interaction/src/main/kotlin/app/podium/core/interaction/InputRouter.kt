package app.podium.core.interaction

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

/** Something that handles semantic input: a screen or an overlay. */
fun interface InputTarget {
    /** @return true if consumed; otherwise the router falls through to targets below and then globals. */
    fun onInput(input: PodiumInput): Boolean
}

/**
 * Routes [PodiumInput]s to the top-most registered target (overlays above screens), falling back to
 * global handlers: Menu = back, long-press Menu = Home, transport buttons = playback (ADR-011).
 */
@Stable
class InputRouter {
    private class Registration(val target: () -> InputTarget, val context: WheelContext)

    private val stack = mutableListOf<Registration>()

    /** Called when no target consumes the input. */
    var global: (PodiumInput) -> Boolean = { false }

    /** The wheel context of the top-most target (drives acceleration and small visual hints). */
    var activeContext: WheelContext by mutableStateOf(WheelContext.LIST_FOCUS)
        private set

    fun dispatch(input: PodiumInput): Boolean {
        for (i in stack.indices.reversed()) {
            if (stack[i].target().onInput(input)) return true
        }
        return global(input)
    }

    internal fun register(target: () -> InputTarget, context: WheelContext): Any {
        val registration = Registration(target, context)
        stack += registration
        activeContext = context
        return registration
    }

    internal fun unregister(handle: Any) {
        stack.remove(handle)
        activeContext = stack.lastOrNull()?.context ?: WheelContext.LIST_FOCUS
    }
}

val LocalInputRouter = compositionLocalOf<InputRouter> { error("No InputRouter provided") }

/** Registers [target] while this composable is in the composition. Later registrations are on top. */
@Composable
fun InputTargetEffect(context: WheelContext, target: InputTarget) {
    val router = LocalInputRouter.current
    val current by rememberUpdatedState(target)
    DisposableEffect(router, context) {
        val handle = router.register({ current }, context)
        onDispose { router.unregister(handle) }
    }
}
