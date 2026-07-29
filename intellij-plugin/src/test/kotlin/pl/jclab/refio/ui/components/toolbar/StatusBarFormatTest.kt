package pl.jclab.refio.ui.components.toolbar

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The status bar has room for three metrics in a dock that can be 300 px wide. The rule under
 * test: a metric must stay short regardless of magnitude, so a busy session cannot push the
 * cost off the visible strip.
 */
class StatusBarFormatTest {

    private val dec = java.text.DecimalFormatSymbols.getInstance().decimalSeparator

    @Test
    fun `counts below a thousand are printed exactly`() {
        assertEquals("0", StatusBarFormat.count(0))
        assertEquals("999", StatusBarFormat.count(999))
    }

    @Test
    fun `larger counts collapse to at most five characters`() {
        assertEquals("1${dec}0K", StatusBarFormat.count(1_000))
        assertEquals("187${dec}1K", StatusBarFormat.count(187_100))
        assertEquals("2${dec}5M", StatusBarFormat.count(2_500_000))
        assertEquals("1${dec}2B", StatusBarFormat.count(1_200_000_000))
    }

    @Test
    fun `cost keeps two decimals so small sessions do not read as free`() {
        assertEquals("0${dec}04", StatusBarFormat.cost(0.0412))
        assertEquals("12${dec}30", StatusBarFormat.cost(12.3))
    }
}
