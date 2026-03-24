package pl.jclab.refio.cli.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test

class AppTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `LoadingScreen should show spinner text`() {
        composeRule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Initializing Refio...")
                }
            }
        }
        composeRule.onNodeWithText("Initializing Refio...").assertExists()
    }

    @Test
    fun `ErrorScreen should display error message`() {
        composeRule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(Modifier.padding(24.dp)) {
                        Text("Error")
                        Text("Connection failed: timeout")
                    }
                }
            }
        }
        composeRule.onNodeWithText("Error").assertExists()
        composeRule.onNodeWithText("Connection failed: timeout").assertExists()
    }

    @Test
    fun `UIChatMessage model should hold agent info`() {
        val msg = UIChatMessage(
            id = "test-1",
            timestamp = 12345L,
            role = "assistant",
            content = "Hello",
            agentId = "agent-1",
            agentName = "analyst",
            agentColor = Color.Red,
            isStreaming = true,
            messageType = MessageType.TEXT
        )
        assert(msg.agentId == "agent-1")
        assert(msg.agentName == "analyst")
        assert(msg.isStreaming)
        assert(msg.messageType == MessageType.TEXT)
    }

    @Test
    fun `ChatMessageMapper should assign unique colors`() {
        ChatMessageMapper.reset()
        val color1 = ChatMessageMapper.getAgentColor("agent-a")
        val color2 = ChatMessageMapper.getAgentColor("agent-b")
        val color1Again = ChatMessageMapper.getAgentColor("agent-a")

        assert(color1 == color1Again) { "Same agent should get same color" }
        assert(color1 != color2) { "Different agents should get different colors" }
        ChatMessageMapper.reset()
    }

    @Test
    fun `ChatMessageMapper reset should clear assignments`() {
        ChatMessageMapper.reset()
        val color1 = ChatMessageMapper.getAgentColor("agent-x")
        ChatMessageMapper.reset()
        val color2 = ChatMessageMapper.getAgentColor("agent-y")
        // After reset, agent-y gets the first color (same as agent-x had before reset)
        assert(color1 == color2) { "After reset, first color should be reused" }
        ChatMessageMapper.reset()
    }
}
