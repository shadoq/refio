package pl.jclab.refio.core.context

import org.junit.jupiter.api.Test
import pl.jclab.refio.api.models.ContextType
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ContextRefSpecTest {

    @Test
    fun `an MCP server reference carries the server as provider and the rest as its query`() {
        val ref = ContextRefSpec.parse("@stubnotes:project notes")

        assertEquals(ContextType.PROVIDER, ref.type)
        assertEquals("stubnotes", ref.metadata["providerId"])
        assertEquals("project notes", ref.path)
    }

    @Test
    fun `a provider with no query still resolves, since most built-ins take none`() {
        val ref = ContextRefSpec.parse("@clipboard")

        assertEquals(ContextType.PROVIDER, ref.type)
        assertEquals("clipboard", ref.metadata["providerId"])
        assertEquals("", ref.path)
    }

    @Test
    fun `the leading at sign is optional, because a shell would need it quoted`() {
        assertEquals(
            ContextRefSpec.parse("@clipboard").metadata["providerId"],
            ContextRefSpec.parse("clipboard").metadata["providerId"]
        )
    }

    @Test
    fun `a file reference keeps its path`() {
        val ref = ContextRefSpec.parse("@file:src/Main.kt")

        assertEquals(ContextType.FILE, ref.type)
        assertEquals("src/Main.kt", ref.path)
    }

    @Test
    fun `a folder reference keeps its path`() {
        assertEquals(ContextType.FOLDER, ContextRefSpec.parse("@folder:src").type)
    }

    @Test
    fun `rules works with and without a path`() {
        assertEquals(ContextType.RULES, ContextRefSpec.parse("@rules").type)
        assertEquals("custom.md", ContextRefSpec.parse("@rules:custom.md").path)
    }

    @Test
    fun `a windows path keeps its drive letter instead of splitting on the colon`() {
        // "file" is consumed as the keyword and everything after the FIRST colon is the path,
        // so "C:/tmp/x.kt" must survive intact.
        assertEquals("C:/tmp/x.kt", ContextRefSpec.parse("@file:C:/tmp/x.kt").path)
    }

    @Test
    fun `a keyword with no argument that needs one is rejected loudly`() {
        assertFailsWith<IllegalArgumentException> { ContextRefSpec.parse("@file") }
        assertFailsWith<IllegalArgumentException> { ContextRefSpec.parse("@folder:") }
    }

    @Test
    fun `an empty reference is rejected`() {
        assertFailsWith<IllegalArgumentException> { ContextRefSpec.parse("  @ ") }
    }
}
