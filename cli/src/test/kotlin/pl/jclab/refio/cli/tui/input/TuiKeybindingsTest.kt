package pl.jclab.refio.cli.tui.input

import org.junit.jupiter.api.Test
import pl.jclab.refio.cli.tui.state.TuiScreen
import pl.jclab.refio.cli.tui.state.TuiTab
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TuiKeybindingsTest {

    @Test
    fun `F1 should open Help screen`() {
        val action = TuiKeybindings.resolveEscapeSequence("\u001bOP")
        assertNotNull(action)
        assertTrue(action is TuiAction.SwitchScreen)
        assertEquals(TuiScreen.HELP, (action as TuiAction.SwitchScreen).screen)
    }

    @Test
    fun `F2 should switch to Steps tab`() {
        val action = TuiKeybindings.resolveEscapeSequence("\u001bOQ")
        assertNotNull(action)
        assertTrue(action is TuiAction.SwitchTab)
        assertEquals(TuiTab.STEPS, (action as TuiAction.SwitchTab).tab)
    }

    @Test
    fun `F5 should switch to Logs tab`() {
        val action = TuiKeybindings.resolveEscapeSequence("\u001b[15~")
        assertNotNull(action)
        assertTrue(action is TuiAction.SwitchTab)
        assertEquals(TuiTab.LOGS, (action as TuiAction.SwitchTab).tab)
    }

    @Test
    fun `Arrow Up should scroll up`() {
        val action = TuiKeybindings.resolveEscapeSequence("\u001b[A")
        assertNotNull(action)
        assertTrue(action is TuiAction.ScrollUp)
    }

    @Test
    fun `Arrow Down should scroll down`() {
        val action = TuiKeybindings.resolveEscapeSequence("\u001b[B")
        assertNotNull(action)
        assertTrue(action is TuiAction.ScrollDown)
    }

    @Test
    fun `Ctrl+C should cancel operation`() {
        val action = TuiKeybindings.resolveControlChar(3)
        assertNotNull(action)
        assertTrue(action is TuiAction.CancelOperation)
    }

    @Test
    fun `Ctrl+Q should quit`() {
        val action = TuiKeybindings.resolveControlChar(17)
        assertNotNull(action)
        assertTrue(action is TuiAction.Quit)
    }

    @Test
    fun `Ctrl+S should switch to settings`() {
        val action = TuiKeybindings.resolveControlChar(19)
        assertNotNull(action)
        assertTrue(action is TuiAction.SwitchScreen)
        assertEquals(TuiScreen.SETTINGS, (action as TuiAction.SwitchScreen).screen)
    }

    @Test
    fun `code 8 (BS) should produce Backspace action`() {
        // On Windows, Backspace sends code 8 (BS). Must NOT open History.
        val action = TuiKeybindings.resolveControlChar(8)
        assertNotNull(action)
        assertTrue(action is TuiAction.Backspace)
    }

    @Test
    fun `code 127 (DEL) should produce Backspace action`() {
        // On macOS/Linux, Backspace sends code 127 (DEL).
        val action = TuiKeybindings.resolveControlChar(127)
        assertNotNull(action)
        assertTrue(action is TuiAction.Backspace)
    }

    @Test
    fun `Alt+H should switch to History`() {
        // History moved from Ctrl+H to Alt+H to avoid Backspace conflict
        val action = TuiKeybindings.resolveEscapeSequence("\u001bh")
        assertNotNull(action)
        assertTrue(action is TuiAction.SwitchScreen)
        assertEquals(TuiScreen.HISTORY, (action as TuiAction.SwitchScreen).screen)
    }

    @Test
    fun `Enter should send message`() {
        val action = TuiKeybindings.resolveControlChar(13)
        assertNotNull(action)
        assertTrue(action is TuiAction.SendMessage)
    }

    @Test
    fun `Escape should go back to main`() {
        val action = TuiKeybindings.resolveControlChar(27)
        assertNotNull(action)
        assertTrue(action is TuiAction.BackToMain)
    }

    @Test
    fun `unknown escape sequence should return null`() {
        val action = TuiKeybindings.resolveEscapeSequence("\u001b[99~")
        assertNull(action)
    }
}
