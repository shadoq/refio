package pl.jclab.refio.cli.tui.views

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import pl.jclab.refio.cli.tui.state.TuiAgentState
import pl.jclab.refio.cli.tui.state.TuiState

class TuiAgentFlowViewTest {

    @Test
    fun `should render empty state when no agents`() {
        val state = TuiState()
        val buf = TuiAgentFlowView.renderToBuffer(state, 80, 20)
        val output = buf.getLines().joinToString("\n")
        assertTrue(output.contains("No active agents"), "Should show no agents message")
    }

    @Test
    fun `should render single agent`() {
        val state = TuiState(agents = listOf(
            TuiAgentState(id = "a1", name = "architect", status = "RUNNING", colorIndex = 0)
        ))
        val buf = TuiAgentFlowView.renderToBuffer(state, 80, 20)
        val output = buf.getLines().joinToString("\n")
        assertTrue(output.contains("architect"), "Should show agent name")
        assertTrue(output.contains("RUNNING"), "Should show agent status")
    }

    @Test
    fun `should render multiple agents with dependencies`() {
        val state = TuiState(agents = listOf(
            TuiAgentState(id = "a1", name = "architect", status = "COMPLETED", colorIndex = 0, costUsd = 0.02, tokensUsed = 500),
            TuiAgentState(id = "a2", name = "coder", status = "RUNNING", colorIndex = 1, dependsOn = listOf("a1")),
            TuiAgentState(id = "a3", name = "reviewer", status = "PENDING", colorIndex = 2, dependsOn = listOf("a2"))
        ))
        val buf = TuiAgentFlowView.renderToBuffer(state, 80, 30)
        val output = buf.getLines().joinToString("\n")
        assertTrue(output.contains("architect"), "Should show architect")
        assertTrue(output.contains("coder"), "Should show coder")
        assertTrue(output.contains("reviewer"), "Should show reviewer")
        assertTrue(output.contains("depends on"), "Should show dependency info")
    }

    @Test
    fun `should show metrics for completed agents`() {
        val state = TuiState(agents = listOf(
            TuiAgentState(id = "a1", name = "worker", status = "COMPLETED", colorIndex = 0,
                costUsd = 0.0123, tokensUsed = 1500)
        ))
        val buf = TuiAgentFlowView.renderToBuffer(state, 80, 20)
        val output = buf.getLines().joinToString("\n")
        // Cost uses locale-dependent decimal separator (. or ,)
        assertTrue(output.contains("0.0123") || output.contains("0,0123"), "Should show cost")
        assertTrue(output.contains("1.5K") || output.contains("1,5K") || output.contains("1500"), "Should show tokens")
    }

    @Test
    fun `should show agent count in header`() {
        val state = TuiState(agents = listOf(
            TuiAgentState(id = "a1", name = "a", status = "RUNNING", colorIndex = 0),
            TuiAgentState(id = "a2", name = "b", status = "PENDING", colorIndex = 1)
        ))
        val buf = TuiAgentFlowView.renderToBuffer(state, 80, 20)
        val output = buf.getLines().joinToString("\n")
        assertTrue(output.contains("2 agents"), "Should show agent count")
    }
}
