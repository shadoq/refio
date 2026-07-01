package pl.jclab.refio.ui.components.autocomplete

import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.api.models.ContextType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [ContextAutocompleteItem] - the @mention dropdown's filter and ordering logic. Wrong
 * matching hides valid options; wrong sort keys jumble the dropdown (MCP providers must sort last).
 * These are pure over a ContextReference, so the rules are pinned directly.
 */
class ContextAutocompleteItemTest {

    private fun item(
        type: ContextType,
        displayName: String,
        path: String = "",
        metadata: Map<String, Any> = emptyMap()
    ) = ContextAutocompleteItem(
        ContextReference(type = type, path = path, displayName = displayName, metadata = metadata)
    )

    @Test
    fun `matchesPrefix matches on display name, path, or type - case-insensitive and at-stripped`() {
        val file = item(ContextType.FILE, displayName = "Main.kt", path = "src/Main.kt")
        assertTrue(file.matchesPrefix("@main"), "display-name substring, @ stripped")
        assertTrue(file.matchesPrefix("src"), "path substring")
        assertTrue(file.matchesPrefix("fil"), "type-name prefix (FILE)")
        assertFalse(file.matchesPrefix("zzz"))
    }

    @Test
    fun `getSortKey orders selection before file before an MCP provider`() {
        val items = listOf(
            item(ContextType.FILE, displayName = "Main.kt", path = "src/Main.kt"),
            item(ContextType.SELECTION, displayName = "sel"),
            item(ContextType.PROVIDER, displayName = "gh", metadata = mapOf("providerId" to "mcp-github"))
        )
        val ordered = items.sortedBy { it.getSortKey() }.map { it.getDisplayName() }
        assertEquals(listOf("sel", "Main.kt", "gh"), ordered)
    }

    @Test
    fun `getDescription prefers a custom metadata description`() {
        val custom = item(ContextType.FILE, displayName = "A.kt", path = "src/A.kt", metadata = mapOf("description" to "my note"))
        assertEquals("my note", custom.getDescription())
    }

    @Test
    fun `getDescription falls back to a per-type label`() {
        assertEquals("File: src/A.kt", item(ContextType.FILE, displayName = "A.kt", path = "src/A.kt").getDescription())
    }
}
