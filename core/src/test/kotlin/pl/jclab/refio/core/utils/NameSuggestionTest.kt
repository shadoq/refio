package pl.jclab.refio.core.utils

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The business rule: a near-miss name (a typo the model can recover from) gets a concrete
 * suggestion, while a wildly-invented name gets none - because a wrong "did you mean" is worse
 * than silence and would send a weak model chasing the wrong tool.
 */
class NameSuggestionTest {

    private val subagents = listOf(
        "security-engineer", "code-reviewer", "architect-reviewer", "ui-designer"
    )

    @Test
    fun `suggests the real subagent for the observed architecture-reviewer hallucination`() {
        assertEquals("architect-reviewer", NameSuggestion.closest("architecture-reviewer", subagents))
    }

    @Test
    fun `suggests the intended tool for a transposition typo`() {
        val tools = listOf("read_file", "grep_search", "file_search")
        assertEquals("grep_search", NameSuggestion.closest("grep_serach", tools))
    }

    @Test
    fun `is case-insensitive`() {
        assertEquals("code-reviewer", NameSuggestion.closest("Code-Reviewer", subagents))
    }

    @Test
    fun `returns null for a wildly-invented name so no misleading suggestion is shown`() {
        assertNull(NameSuggestion.closest("do-my-taxes", subagents))
    }

    @Test
    fun `returns null for an empty input or empty candidate set`() {
        assertNull(NameSuggestion.closest("", subagents))
        assertNull(NameSuggestion.closest("code-reviewer", emptyList()))
    }
}
