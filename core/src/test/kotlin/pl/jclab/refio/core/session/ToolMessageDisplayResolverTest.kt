package pl.jclab.refio.core.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ToolMessageDisplayResolverTest {

    @Test
    fun `should keep summarized tool content as primary display text`() {
        val resolved = ToolMessageDisplayResolver.resolve(
            role = "tool",
            content = "Summary output",
            isSummarized = true,
            rawOutput = "Very long raw output"
        )

        assertEquals("Summary output", resolved.content)
        assertEquals("Very long raw output", resolved.toolStreamContent)
    }

    @Test
    fun `should not attach raw output for non summarized tool message`() {
        val resolved = ToolMessageDisplayResolver.resolve(
            role = "tool",
            content = "Full output",
            isSummarized = false,
            rawOutput = "Full output"
        )

        assertEquals("Full output", resolved.content)
        assertNull(resolved.toolStreamContent)
    }
}
