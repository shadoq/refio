package pl.jclab.refio.core.services.turn

import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import pl.jclab.refio.core.services.ToolPermissionsService
import pl.jclab.refio.core.tools.base.ToolRegistry

class ToolCallParserTest {

    private val parser = ToolCallParser(
        toolRegistry = mockk<ToolRegistry>(relaxed = true),
        toolPermissionsService = mockk<ToolPermissionsService>(relaxed = true)
    )

    @Test
    fun `should detect empty object as meaningless json`() {
        assertTrue(parser.isMeaninglessJson("{}"))
        assertTrue(parser.isMeaninglessJson("{ }"))
    }

    @Test
    fun `should treat known payload keys as meaningful json`() {
        assertFalse(parser.isMeaninglessJson("""{"response":"done"}"""))
        assertFalse(parser.isMeaninglessJson("""{"actions":[]}"""))
        assertFalse(parser.isMeaninglessJson("""{"thinking":"..."}"""))
    }

    @Test
    fun `should treat unknown payload object as meaningless json`() {
        assertTrue(parser.isMeaninglessJson("""{"foo":"bar"}"""))
        assertFalse(parser.isMeaninglessJson(""))
    }
}
