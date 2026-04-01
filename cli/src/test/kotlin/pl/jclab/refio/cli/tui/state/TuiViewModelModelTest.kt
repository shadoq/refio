package pl.jclab.refio.cli.tui.state

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Tests model selection state in TuiState (pure state tests).
 * Router-dependent model resolution is tested via integration tests.
 */
class TuiViewModelModelTest {

    @Test
    fun `TuiState model should default to null`() {
        val state = TuiState()
        assertNull(state.model)
    }

    @Test
    fun `TuiState modelSelectorVisible should default to false`() {
        val state = TuiState()
        assertFalse(state.modelSelectorVisible)
    }

    @Test
    fun `TuiState modelSelectorCandidates should default to empty`() {
        val state = TuiState()
        assertTrue(state.modelSelectorCandidates.isEmpty())
    }

    @Test
    fun `TuiState modelSelectorIndex should default to 0`() {
        val state = TuiState()
        assertEquals(0, state.modelSelectorIndex)
    }

    @Test
    fun `model selector state should be settable via copy`() {
        val state = TuiState()
        val candidates = listOf("ollama/qwen2.5:7b", "anthropic/claude-sonnet")
        val updated = state.copy(
            modelSelectorVisible = true,
            modelSelectorCandidates = candidates,
            modelSelectorIndex = 1
        )
        assertTrue(updated.modelSelectorVisible)
        assertEquals(2, updated.modelSelectorCandidates.size)
        assertEquals(1, updated.modelSelectorIndex)
    }

    @Test
    fun `model selector index wrapping logic`() {
        val candidates = listOf("a", "b", "c")
        val max = candidates.size

        // Next from last wraps to first
        val idx = 2
        val next = (idx + 1) % max
        assertEquals(0, next)

        // Prev from first wraps to last
        val idx2 = 0
        val prev = (idx2 - 1 + max) % max
        assertEquals(2, prev)
    }

    @Test
    fun `TuiState context metrics should have defaults`() {
        val state = TuiState()
        assertEquals(0, state.contextUsedTokens)
        assertEquals(128000, state.contextMaxTokens)
        assertEquals(0L, state.sessionTokensIn)
        assertEquals(0L, state.sessionTokensOut)
    }

    @Test
    fun `TuiState agentFilter should default to null`() {
        val state = TuiState()
        assertNull(state.agentFilter)
    }

    @Test
    fun `TuiState thinkingEnabled should default to false`() {
        val state = TuiState()
        assertFalse(state.thinkingEnabled)
    }

    @Test
    fun `TuiState noEgressEnabled should default to false`() {
        val state = TuiState()
        assertFalse(state.noEgressEnabled)
    }

    @Test
    fun `TuiState ragIndexingProgress should default to not indexing`() {
        val state = TuiState()
        assertEquals(-1.0, state.ragIndexingProgress)
        assertEquals("", state.ragIndexingStatus)
    }
}
