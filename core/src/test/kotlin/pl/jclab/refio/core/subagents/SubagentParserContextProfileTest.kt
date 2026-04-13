package pl.jclab.refio.core.subagents

import pl.jclab.refio.core.subagents.models.SubagentScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for SubagentParser context_profile parsing (Unit 6).
 */
class SubagentParserContextProfileTest {

    private val parser = SubagentParser()

    @Test
    fun `parses context_profile from YAML frontmatter`() {
        val content = """
            ---
            name: test-agent
            description: Test agent
            tools: read_file
            context_profile:
              include_file_tree: false
              include_conversation: true
              include_working_memory: true
              include_dependencies: true
              max_context_tokens: 8000
              include_parent_summary: true
            ---

            You are a test agent.
        """.trimIndent()

        val definition = parser.parse(content, null, SubagentScope.BUILTIN)

        assertFalse(definition.contextProfile.includeFileTree)
        assertTrue(definition.contextProfile.includeConversation)
        assertTrue(definition.contextProfile.includeWorkingMemory)
        assertTrue(definition.contextProfile.includeDependencies)
        assertEquals(8000, definition.contextProfile.maxContextTokens)
        assertTrue(definition.contextProfile.includeParentSummary)
    }

    @Test
    fun `uses default context_profile when not specified`() {
        val content = """
            ---
            name: simple-agent
            description: Simple agent
            ---

            You are a simple agent.
        """.trimIndent()

        val definition = parser.parse(content, null, SubagentScope.BUILTIN)

        assertTrue(definition.contextProfile.includeFileTree)
        assertTrue(definition.contextProfile.includeConversation)
        assertTrue(definition.contextProfile.includeWorkingMemory)
        assertTrue(definition.contextProfile.includeDependencies)
        assertEquals(null, definition.contextProfile.maxContextTokens)
        assertFalse(definition.contextProfile.includeParentSummary)
    }

    @Test
    fun `parses partial context_profile with defaults for missing fields`() {
        val content = """
            ---
            name: partial-agent
            description: Partial profile agent
            context_profile:
              include_parent_summary: true
            ---

            You are a partial agent.
        """.trimIndent()

        val definition = parser.parse(content, null, SubagentScope.PROJECT)

        // Specified fields
        assertTrue(definition.contextProfile.includeParentSummary)

        // Default fields
        assertTrue(definition.contextProfile.includeFileTree)
        assertTrue(definition.contextProfile.includeConversation)
        assertTrue(definition.contextProfile.includeWorkingMemory)
        assertTrue(definition.contextProfile.includeDependencies)
        assertEquals(null, definition.contextProfile.maxContextTokens)
    }

    @Test
    fun `handles invalid context_profile value gracefully`() {
        val content = """
            ---
            name: bad-profile-agent
            description: Bad profile agent
            context_profile: not_a_map
            ---

            You are a bad profile agent.
        """.trimIndent()

        val definition = parser.parse(content, null, SubagentScope.BUILTIN)

        // Should use all defaults
        assertTrue(definition.contextProfile.includeFileTree)
        assertTrue(definition.contextProfile.includeConversation)
    }
}
