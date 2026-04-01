package pl.jclab.refio.cli.tui.components

import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TuiProgressBarTest {

    @Test
    fun `should render empty progress bar for zero max`() {
        val result = TuiProgressBar.render(0, 0, 20)
        assertNotNull(result)
        assertTrue(result.startsWith("["))
        assertTrue(result.endsWith("]"))
    }

    @Test
    fun `should render half-filled progress bar`() {
        val result = TuiProgressBar.render(50, 100, 20)
        assertNotNull(result)
        assertTrue(result.contains("50%"))
    }

    @Test
    fun `should render full progress bar`() {
        val result = TuiProgressBar.render(100, 100, 10)
        assertNotNull(result)
        assertTrue(result.contains("100%"))
    }

    @Test
    fun `should handle overflow gracefully`() {
        val result = TuiProgressBar.render(200, 100, 10)
        assertNotNull(result)
        assertTrue(result.contains("100%"))
    }
}
