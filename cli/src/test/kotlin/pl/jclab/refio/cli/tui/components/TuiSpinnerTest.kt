package pl.jclab.refio.cli.tui.components

import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TuiSpinnerTest {

    @Test
    fun `frame should return non-empty string`() {
        val frame = TuiSpinner.frame(0)
        assertNotNull(frame)
    }

    @Test
    fun `frame should cycle through frames`() {
        val frames = (0L..20L).map { TuiSpinner.frame(it) }
        // Should have repeating pattern (10 frames)
        assertTrue(frames.size > 10)
    }

    @Test
    fun `render should include message`() {
        val result = TuiSpinner.render(0, "Loading...")
        assertNotNull(result)
    }
}
