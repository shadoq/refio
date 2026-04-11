package pl.jclab.refio.core.services.context

import io.mockk.mockk
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.services.ConfigService
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the RAG skip filter, in particular the new system-phrase short-circuit.
 *
 * Background: when the agent loop fails (empty content / format retry exhausted) the harness
 * resends the conversation with a synthetic "Continue from where you left off" user message.
 * That phrase carries no task-specific signal, but RagSearchService spent 7-23 seconds per
 * iteration generating embeddings for it. Skip it explicitly.
 */
class RagContextLoaderTest {

    private fun loader(): RagContextLoader =
        RagContextLoader(configService = mockk<ConfigService>(relaxed = true))

    @Test
    fun `null and blank queries are skipped`() {
        val l = loader()
        assertTrue(l.shouldSkipRag(null))
        assertTrue(l.shouldSkipRag(""))
        assertTrue(l.shouldSkipRag("   "))
    }

    @Test
    fun `system harness continue phrase is skipped`() {
        val l = loader()
        assertTrue(l.shouldSkipRag("Continue from where you left off"))
        assertTrue(l.shouldSkipRag("Continue from where you left off."))
        assertTrue(l.shouldSkipRag("continue from where you left off..."))
        // Surrounding whitespace should not defeat the match.
        assertTrue(l.shouldSkipRag("   Continue from where you left off  "))
    }

    @Test
    fun `polish continue variants are skipped`() {
        val l = loader()
        assertTrue(l.shouldSkipRag("Kontynuuj zadanie"))
        assertTrue(l.shouldSkipRag("Kontynuuj od miejsca w którym skończyłeś"))
    }

    @Test
    fun `meta and structure questions are still skipped`() {
        val l = loader()
        assertTrue(l.shouldSkipRag("opisz projekt"))
        assertTrue(l.shouldSkipRag("what is this project about"))
        assertTrue(l.shouldSkipRag("describe the project structure"))
    }

    @Test
    fun `code-mentioning queries are not skipped even when short`() {
        val l = loader()
        assertFalse(l.shouldSkipRag("show me file foo"))
        assertFalse(l.shouldSkipRag("find function bar"))
    }

    @Test
    fun `substantive task descriptions are not skipped`() {
        val l = loader()
        assertFalse(
            l.shouldSkipRag(
                "Refactor AgentTurnLoop empty-content branch to recover JSON from thinking field"
            )
        )
    }
}
