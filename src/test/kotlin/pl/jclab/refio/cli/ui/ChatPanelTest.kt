package pl.jclab.refio.cli.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test

class ChatPanelTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `should display user message`() {
        val messages = listOf(
            UIChatMessage(
                id = "1",
                timestamp = System.currentTimeMillis(),
                role = "user",
                content = "Hello from the user"
            )
        )
        composeRule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ChatPanel(messages = messages, isStreaming = false, onSend = {})
            }
        }
        composeRule.onNodeWithText("Hello from the user").assertExists()
    }

    @Test
    fun `should display assistant message`() {
        val messages = listOf(
            UIChatMessage(
                id = "2",
                timestamp = System.currentTimeMillis(),
                role = "assistant",
                content = "Hello from assistant"
            )
        )
        composeRule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ChatPanel(messages = messages, isStreaming = false, onSend = {})
            }
        }
        composeRule.onNodeWithText("Hello from assistant").assertExists()
    }

    @Test
    fun `should display multiple messages`() {
        val messages = listOf(
            UIChatMessage(id = "1", timestamp = 1000L, role = "user", content = "First message"),
            UIChatMessage(id = "2", timestamp = 2000L, role = "assistant", content = "Second message"),
            UIChatMessage(id = "3", timestamp = 3000L, role = "user", content = "Third message")
        )
        composeRule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ChatPanel(messages = messages, isStreaming = false, onSend = {})
            }
        }
        composeRule.onNodeWithText("First message").assertExists()
        composeRule.onNodeWithText("Second message").assertExists()
        composeRule.onNodeWithText("Third message").assertExists()
    }

    @Test
    fun `should show streaming indicator when streaming`() {
        composeRule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ChatPanel(messages = emptyList(), isStreaming = true, onSend = {})
            }
        }
        composeRule.onNodeWithText("Thinking...").assertExists()
    }

    @Test
    fun `should not show streaming indicator when not streaming`() {
        composeRule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ChatPanel(messages = emptyList(), isStreaming = false, onSend = {})
            }
        }
        composeRule.onNodeWithText("Thinking...").assertDoesNotExist()
    }

    @Test
    fun `should display agent event messages`() {
        val messages = listOf(
            UIChatMessage(
                id = "1",
                timestamp = System.currentTimeMillis(),
                role = "agent_event",
                content = "Agent 'analyst' started: Analyzing code",
                agentName = "analyst",
                messageType = MessageType.AGENT_STARTED
            )
        )
        composeRule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ChatPanel(messages = messages, isStreaming = false, onSend = {})
            }
        }
        composeRule.onNodeWithText("Agent 'analyst' started: Analyzing code").assertExists()
    }
}
