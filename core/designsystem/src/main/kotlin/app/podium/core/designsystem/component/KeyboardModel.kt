package app.podium.core.designsystem.component

/*
 * The Podium keyboard's model (D-45, interaction-model.md §2.1): which keys, in which rows, and what
 * each does to the text. Pure, so typing is tested without a screen.
 */

/** Which keyboard text fields use: Podium's own (the Wheel becomes it) or the phone's. */
enum class KeyboardStyle { PODIUM, PHONE }

/** What a field is for, which picks the keyboard's first page. */
enum class KeyLayout { TEXT, HEX }

/** The action key's word. */
enum class KeyboardAction(val label: String) { SEARCH("Search"), DONE("Done") }

enum class KeyPage { LETTERS, SYMBOLS, HEX }

enum class ShiftState { OFF, ONCE, LOCKED }

sealed interface Key {
    /** Types [text] (a letter is typed in upper case while shift is on). */
    data class Char(val text: String) : Key
    data object Shift : Key
    data object Backspace : Key
    data object Space : Key
    data object Symbols : Key
    data object Letters : Key
    data object Action : Key
    data object Hide : Key
}

/** A key and its share of its row's width. */
data class KeySpec(val key: Key, val weight: Float = 1f)

/** An empty share of a row (the half-key indent of the second letter row). */
val Gap = KeySpec(Key.Char(""), 0.5f)

object KeyboardLayouts {

    private fun chars(s: String) = s.map { KeySpec(Key.Char(it.toString())) }

    fun rows(page: KeyPage): List<List<KeySpec>> = when (page) {
        KeyPage.LETTERS -> listOf(
            chars("qwertyuiop"),
            listOf(Gap) + chars("asdfghjkl") + Gap,
            listOf(KeySpec(Key.Shift, 1.5f)) + chars("zxcvbnm") + KeySpec(Key.Backspace, 1.5f),
            listOf(KeySpec(Key.Symbols, 1.5f), KeySpec(Key.Space, 4.5f), KeySpec(Key.Char("."), 1f), KeySpec(Key.Action, 2f), KeySpec(Key.Hide, 1f)),
        )
        KeyPage.SYMBOLS -> listOf(
            chars("1234567890"),
            listOf(Gap) + chars("-/:;()$&@") + Gap,
            listOf(KeySpec(Key.Char("#"), 1.5f)) + chars(".,?!'\"+") + KeySpec(Key.Backspace, 1.5f),
            listOf(KeySpec(Key.Letters, 1.5f), KeySpec(Key.Space, 4.5f), KeySpec(Key.Char("."), 1f), KeySpec(Key.Action, 2f), KeySpec(Key.Hide, 1f)),
        )
        KeyPage.HEX -> listOf(
            chars("123456"),
            chars("7890AB"),
            chars("CDEF") + KeySpec(Key.Backspace, 2f),
            listOf(KeySpec(Key.Action, 3f), KeySpec(Key.Hide, 3f)),
        )
    }

    fun firstPage(layout: KeyLayout): KeyPage = if (layout == KeyLayout.HEX) KeyPage.HEX else KeyPage.LETTERS

    /** What a key shows (a letter in the case it would type); null for a key drawn as a symbol. */
    fun label(key: Key, shift: ShiftState, action: KeyboardAction): String? = when (key) {
        is Key.Char -> if (shift != ShiftState.OFF) key.text.uppercase() else key.text
        Key.Space -> "space"
        Key.Symbols -> "123"
        Key.Letters -> "abc"
        Key.Action -> action.label
        Key.Shift, Key.Backspace, Key.Hide -> null
    }

    /** What a screen reader says for a key. */
    fun description(key: Key, shift: ShiftState, action: KeyboardAction): String = when (key) {
        is Key.Char -> if (shift != ShiftState.OFF) key.text.uppercase() else key.text
        Key.Shift -> when (shift) {
            ShiftState.OFF -> "Shift"
            ShiftState.ONCE -> "Shift, on"
            ShiftState.LOCKED -> "Caps lock, on"
        }
        Key.Backspace -> "Delete"
        Key.Space -> "Space"
        Key.Symbols -> "Numbers and symbols"
        Key.Letters -> "Letters"
        Key.Action -> action.label
        Key.Hide -> "Close keyboard"
    }
}

/** The keyboard's own state while typing. */
data class KeyboardState(val page: KeyPage, val shift: ShiftState = ShiftState.OFF)

object KeyboardEditor {

    /** What a key did: the new text and keyboard state, and whether it was the action or hide key. */
    data class Result(val text: String, val state: KeyboardState, val action: Boolean = false, val hide: Boolean = false)

    /** Two shift taps within this long lock capitals. */
    const val DOUBLE_TAP_MS = 350L

    fun press(
        key: Key,
        text: String,
        state: KeyboardState,
        maxLength: Int? = null,
        accept: (kotlin.Char) -> Boolean = { true },
        nowMs: Long = 0L,
        lastShiftMs: Long = Long.MIN_VALUE,
    ): Result = when (key) {
        is Key.Char -> {
            val typed = if (state.shift != ShiftState.OFF) key.text.uppercase() else key.text
            val fits = maxLength == null || text.length + typed.length <= maxLength
            val next = if (typed.isNotEmpty() && fits && typed.all(accept)) text + typed else text
            Result(next, state.copy(shift = if (state.shift == ShiftState.ONCE) ShiftState.OFF else state.shift))
        }
        Key.Space -> {
            val fits = maxLength == null || text.length < maxLength
            Result(if (fits && accept(' ')) "$text " else text, state)
        }
        Key.Backspace -> Result(dropLast(text), state)
        Key.Shift -> {
            val shift = when {
                state.shift == ShiftState.OFF -> ShiftState.ONCE
                state.shift == ShiftState.ONCE && lastShiftMs != Long.MIN_VALUE && nowMs - lastShiftMs in 0..DOUBLE_TAP_MS -> ShiftState.LOCKED
                else -> ShiftState.OFF
            }
            Result(text, state.copy(shift = shift))
        }
        Key.Symbols -> Result(text, state.copy(page = KeyPage.SYMBOLS))
        Key.Letters -> Result(text, state.copy(page = KeyPage.LETTERS))
        Key.Action -> Result(text, state, action = true)
        Key.Hide -> Result(text, state, hide = true)
    }

    /** The text without its last character (a whole emoji or surrogate pair, never half of one). */
    fun dropLast(text: String): String {
        if (text.isEmpty()) return text
        val end = text.length
        val start = text.offsetByCodePoints(end, -1)
        return text.substring(0, start)
    }
}
