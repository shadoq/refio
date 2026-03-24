package pl.jclab.refio.cli.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test

class StatusPanelTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `should show no active agents when empty`() {
        composeRule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                StatusPanel(
                    agents = emptyList(),
                    metrics = MetricsInfo()
                )
            }
        }
        composeRule.onNodeWithText("No active agents").assertExists()
    }

    @Test
    fun `should display agent names`() {
        val agents = listOf(
            AgentState(id = "a1", name = "analyst", status = "RUNNING", color = Color.Green),
            AgentState(id = "a2", name = "coder", status = "PENDING", color = Color.Blue)
        )
        composeRule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                StatusPanel(agents = agents, metrics = MetricsInfo())
            }
        }
        composeRule.onNodeWithText("analyst").assertExists()
        composeRule.onNodeWithText("coder").assertExists()
    }

    @Test
    fun `should display metrics`() {
        val metrics = MetricsInfo(
            tokensIn = 1500,
            tokensOut = 500,
            costUsd = 0.0123,
            totalAgents = 3,
            completedAgents = 2
        )
        composeRule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                StatusPanel(agents = emptyList(), metrics = metrics)
            }
        }
        composeRule.onNodeWithText("1500").assertExists()
        composeRule.onNodeWithText("500").assertExists()
        composeRule.onNodeWithText("2/3").assertExists()
    }

    @Test
    fun `should display pending approvals`() {
        val approvals = listOf(
            PendingApproval(
                id = "ap1",
                agentId = "a1",
                agentName = "coder",
                action = "write_file",
                risk = "medium",
                details = mapOf("path" to "/src/main.kt")
            )
        )
        composeRule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                StatusPanel(
                    agents = emptyList(),
                    metrics = MetricsInfo(),
                    pendingApprovals = approvals
                )
            }
        }
        composeRule.onNodeWithText("coder: write_file").assertExists()
        composeRule.onNodeWithText("Risk: medium").assertExists()
        composeRule.onNodeWithText("Approve").assertExists()
        composeRule.onNodeWithText("Reject").assertExists()
    }

    @Test
    fun `should call onApprove when Approve clicked`() {
        var approvedId: String? = null
        val approvals = listOf(
            PendingApproval(
                id = "ap1", agentId = "a1", agentName = "test",
                action = "action", risk = "low", details = emptyMap()
            )
        )
        composeRule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                StatusPanel(
                    agents = emptyList(),
                    metrics = MetricsInfo(),
                    pendingApprovals = approvals,
                    onApprove = { approvedId = it }
                )
            }
        }
        composeRule.onNodeWithText("Approve").performClick()
        assert(approvedId == "ap1") { "Expected ap1 but got $approvedId" }
    }

    @Test
    fun `should display agent with current phase`() {
        val agents = listOf(
            AgentState(
                id = "a1", name = "analyzer", status = "RUNNING",
                color = Color.Green, currentPhase = "Scanning imports"
            )
        )
        composeRule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                StatusPanel(agents = agents, metrics = MetricsInfo())
            }
        }
        composeRule.onNodeWithText("analyzer").assertExists()
        composeRule.onNodeWithText("Scanning imports").assertExists()
    }

    @Test
    fun `should display agent cost when non-zero`() {
        val agents = listOf(
            AgentState(
                id = "a1", name = "coder", status = "COMPLETED",
                color = Color.Blue, costUsd = 0.042
            )
        )
        composeRule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                StatusPanel(agents = agents, metrics = MetricsInfo())
            }
        }
        composeRule.onNodeWithText("coder").assertExists()
        // Cost is formatted as $X.XXX — match the formatted string
        val expected = "$${String.format("%.3f", 0.042)}"
        composeRule.onNodeWithText(expected).assertExists()
    }

    @Test
    fun `should display duration when non-zero`() {
        val metrics = MetricsInfo(totalDurationMs = 5000)
        composeRule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                StatusPanel(agents = emptyList(), metrics = metrics)
            }
        }
        composeRule.onNodeWithText("5s").assertExists()
    }
}
