package pl.jclab.refio.ui

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rail shows one button per visible screen. The business rule under test: the "Advanced View"
 * setting decides which screens a user can reach, and turning it off must leave a usable panel
 * (Chat and Execution always stay reachable).
 */
class RefioScreenTest {

    @Test
    fun `simple view exposes only the two everyday screens`() {
        val visible = RefioScreen.visibleFor(advancedView = false)

        assertEquals(listOf(RefioScreen.CHAT, RefioScreen.EXECUTION), visible)
    }

    @Test
    fun `advanced view exposes every screen in rail order`() {
        val visible = RefioScreen.visibleFor(advancedView = true)

        assertEquals(RefioScreen.entries.toList(), visible)
        assertEquals(RefioScreen.CHAT, visible.first())
    }

    @Test
    fun `every screen has a distinct title so tooltips stay unambiguous`() {
        val titles = RefioScreen.entries.map { it.title }

        assertEquals(titles.size, titles.distinct().size)
        assertTrue(titles.none { it.isBlank() })
    }
}
