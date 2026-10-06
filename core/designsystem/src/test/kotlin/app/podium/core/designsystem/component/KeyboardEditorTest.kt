package app.podium.core.designsystem.component

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Typing on the Podium keyboard (D-45). */
class KeyboardEditorTest {

    private val letters = KeyboardState(KeyPage.LETTERS)

    @Test
    fun `letters type, shift capitalises once, twice quickly locks`() {
        var r = KeyboardEditor.press(Key.Char("p"), "", letters)
        assertEquals("p", r.text)
        r = KeyboardEditor.press(Key.Shift, r.text, r.state, nowMs = 1_000)
        assertEquals(ShiftState.ONCE, r.state.shift)
        r = KeyboardEditor.press(Key.Char("o"), r.text, r.state)
        assertEquals("pO", r.text)
        assertEquals(ShiftState.OFF, r.state.shift)
        // Double tap: caps lock until shift again.
        r = KeyboardEditor.press(Key.Shift, r.text, r.state, nowMs = 2_000)
        r = KeyboardEditor.press(Key.Shift, r.text, r.state, nowMs = 2_200, lastShiftMs = 2_000)
        assertEquals(ShiftState.LOCKED, r.state.shift)
        r = KeyboardEditor.press(Key.Char("d"), r.text, r.state)
        r = KeyboardEditor.press(Key.Char("i"), r.text, r.state)
        assertEquals("pODI", r.text)
        r = KeyboardEditor.press(Key.Shift, r.text, r.state, nowMs = 5_000, lastShiftMs = 2_200)
        assertEquals(ShiftState.OFF, r.state.shift)
        // Shift on with no earlier tap to pair with turns it off, never locks.
        assertEquals(ShiftState.OFF, KeyboardEditor.press(Key.Shift, "", letters.copy(shift = ShiftState.ONCE), nowMs = 9_000).state.shift)
    }

    @Test
    fun `space, delete and whole characters`() {
        var r = KeyboardEditor.press(Key.Space, "abc", letters)
        assertEquals("abc ", r.text)
        r = KeyboardEditor.press(Key.Backspace, r.text, r.state)
        assertEquals("abc", r.text)
        // An emoji is one character to delete, never half of one.
        assertEquals("a", KeyboardEditor.dropLast("a🎵"))
        assertEquals("", KeyboardEditor.dropLast(""))
    }

    @Test
    fun `pages switch, the action and close keys report themselves`() {
        val symbols = KeyboardEditor.press(Key.Symbols, "x", letters)
        assertEquals(KeyPage.SYMBOLS, symbols.state.page)
        assertEquals(KeyPage.LETTERS, KeyboardEditor.press(Key.Letters, "x", symbols.state).state.page)
        assertTrue(KeyboardEditor.press(Key.Action, "x", letters).action)
        assertTrue(KeyboardEditor.press(Key.Hide, "x", letters).hide)
    }

    @Test
    fun `a hex field takes six hex digits and nothing else`() {
        val hex = KeyboardState(KeyboardLayouts.firstPage(KeyLayout.HEX))
        val accept: (Char) -> Boolean = { it.isDigit() || it.uppercaseChar() in 'A'..'F' }
        var text = ""
        for (k in listOf("2", "F", "6", "F", "5", "E", "1")) text = KeyboardEditor.press(Key.Char(k), text, hex, maxLength = 6, accept = accept).text
        assertEquals("2F6F5E", text)
        assertEquals("2F", KeyboardEditor.press(Key.Char("Z"), "2F", hex, maxLength = 6, accept = accept).text)
        assertEquals("2F", KeyboardEditor.press(Key.Space, "2F", hex, maxLength = 6, accept = accept).text)
    }

    @Test
    fun `every row of every page fills the same width`() {
        for (page in KeyPage.entries) {
            val widths = KeyboardLayouts.rows(page).map { row -> row.sumOf { it.weight.toDouble() } }
            assertEquals(1, widths.toSet().size, "$page rows: $widths")
        }
    }
}
