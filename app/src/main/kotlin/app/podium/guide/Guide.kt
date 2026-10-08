package app.podium.guide

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the tour was finished or skipped (D-57); kept on the phone. */
class GuideStore(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("podium_guide", Context.MODE_PRIVATE))

    private val _seen = MutableStateFlow(prefs.getBoolean(KEY_SEEN, false))
    val seen: StateFlow<Boolean> = _seen.asStateFlow()

    fun markSeen() {
        _seen.value = true
        prefs.edit().putBoolean(KEY_SEEN, true).apply()
    }

    /** Debug builds: as on a first launch. */
    fun forget() {
        _seen.value = false
        prefs.edit().remove(KEY_SEEN).apply()
    }

    private companion object {
        const val KEY_SEEN = "seen"
    }
}

/**
 * The tour's checkpoints (D-57): a welcome, then each thing shown on the model — the camera closes
 * in on it, a finger shows how, and the listener can try it on the model. [demo] says what the
 * animation shows, for accessibility services.
 */
enum class GuideStep(val title: String, val line: String, val demo: String) {
    WELCOME(
        "Welcome to Podium",
        "A music player you hold like the real thing. Here's how it works, in six short steps.",
        "A model of your Podium.",
    ),
    TURN(
        "Turn the Wheel",
        "Slide a finger around the Wheel to move through a menu. Try it on the model.",
        "A finger slides around the Wheel and the lit row moves down the menu.",
    ),
    CENTER(
        "Press the center",
        "The center button chooses what's lit. Give it a press.",
        "A finger presses the center button and the next menu slides in.",
    ),
    MENU(
        "Press Menu to go back",
        "Menu takes you back a step. Hold it to go all the way Home.",
        "A finger presses Menu at the top of the Wheel and the menu slides back.",
    ),
    PLAY(
        "Play and pause",
        "The bottom of the Wheel plays or pauses. The arrows skip, and hold them to scan.",
        "A finger presses play at the bottom of the Wheel and the music starts.",
    ),
    PINCH(
        "Step outside",
        "Pinch two fingers together on your Podium to see it as an object, out in its space.",
        "Two fingers pinch together and the Podium moves back into a starry space.",
    ),
    STICKERS(
        "Make it yours",
        "Outside, turn your pictures into stickers and stick them anywhere. Tap one to try. Help and Turn off live there too.",
        "Beside the Podium, a page of stickers; one is tapped and flies onto the Podium.",
    ),
    ;

    val isCheckpoint: Boolean get() = this != WELCOME

    /** "Step 2 of 6" for the checkpoints; nothing for the welcome. */
    val counter: String? get() = if (isCheckpoint) "Step $ordinal of $CHECKPOINTS" else null

    companion object {
        val CHECKPOINTS = entries.size - 1
    }
}

/**
 * The tour (D-57): which checkpoint is showing and which the listener has done on the model. A
 * done checkpoint moves on by itself (the UI waits a moment first); Next, Back and Skip are always
 * there. Finishing or skipping is remembered; Help replays it.
 */
@Stable
class GuideState(private val store: GuideStore) {
    var step: GuideStep? by mutableStateOf(null)
        private set

    var passed: Set<GuideStep> by mutableStateOf(emptySet())
        private set

    /** First launch: the welcome, unless the tour was finished or skipped before. */
    fun offer() {
        if (!store.seen.value && step == null) step = GuideStep.WELCOME
    }

    /** From the welcome, or Help's "Replay the hands-on guide". */
    fun begin() {
        passed = emptySet()
        step = GuideStep.TURN
    }

    fun next() {
        val s = step ?: return
        val following = GuideStep.entries.getOrNull(s.ordinal + 1)
        if (following == null) finish() else step = following
    }

    fun back() {
        val s = step ?: return
        if (s.ordinal > 1) step = GuideStep.entries[s.ordinal - 1]
    }

    /** The listener did [s] on the model. */
    fun pass(s: GuideStep) {
        if (step == s && s.isCheckpoint) passed = passed + s
    }

    fun skip() = finish()

    fun finish() {
        step = null
        store.markSeen()
    }
}
