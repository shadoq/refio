package pl.jclab.refio.core.services.longtask

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The TUI and the IDE read `/until` and `/long` through this one parser, so both accept the same input. */
class LongTaskCommandTest {

    @Test
    fun `parse until, long off, long rounds`() {
        assertEquals(LongTaskCommand.Until("npm test -- --silent"), LongTaskCommand.parse("/until npm test -- --silent"))
        assertEquals(LongTaskCommand.UntilOff, LongTaskCommand.parse("/until off"))
        assertEquals(LongTaskCommand.Status, LongTaskCommand.parse("/until"))
        assertEquals(LongTaskCommand.Off, LongTaskCommand.parse("/long off"))
        assertEquals(LongTaskCommand.On, LongTaskCommand.parse("/long on"))
        assertEquals(LongTaskCommand.Status, LongTaskCommand.parse("/long"))
        assertEquals(LongTaskCommand.Rounds(20), LongTaskCommand.parse("/long rounds 20"))
        assertEquals(LongTaskCommand.Hours(1.5), LongTaskCommand.parse("/long hours 1.5"))
    }

    @Test
    fun `other input is not a long task command`() {
        assertNull(LongTaskCommand.parse("/untiled"))
        assertNull(LongTaskCommand.parse("/goal all tests pass"))
        assertNull(LongTaskCommand.parse("run /until npm test"))
    }

    @Test
    fun `bad numbers explain the usage`() {
        assertIs<LongTaskCommand.Invalid>(LongTaskCommand.parse("/long rounds many"))
        assertIs<LongTaskCommand.Invalid>(LongTaskCommand.parse("/long rounds 0"))
        assertIs<LongTaskCommand.Invalid>(LongTaskCommand.parse("/long hours -2"))
        assertIs<LongTaskCommand.Invalid>(LongTaskCommand.parse("/long forever"))
    }
}
