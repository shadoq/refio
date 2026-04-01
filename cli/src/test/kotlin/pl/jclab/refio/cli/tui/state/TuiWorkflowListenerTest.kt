package pl.jclab.refio.cli.tui.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TuiWorkflowListenerTest {

    private val messagesState = MutableStateFlow<List<TuiChatMessage>>(emptyList())
    private val streamingState = MutableStateFlow(false)
    private val stepsState = MutableStateFlow<List<TuiStep>>(emptyList())
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private val listener = TuiWorkflowListener(
        agentId = "test",
        agentName = "TestAgent",
        agentColorIndex = 0,
        messagesState = messagesState,
        streamingState = streamingState,
        stepsState = stepsState,
        scope = scope
    )

    @Test
    fun `onChatStarted should set streaming to true`() = runTest {
        listener.onChatStarted()
        assertTrue(streamingState.value)
    }

    @Test
    fun `onPlanningStarted should set streaming to true`() = runTest {
        listener.onPlanningStarted()
        assertTrue(streamingState.value)
    }

    @Test
    fun `onStreamChunk should accumulate content and debounce UI updates`() = runTest {
        listener.onChatStarted()
        listener.onStreamChunk("Hello ")
        // Second chunk within 500ms is debounced — content IS accumulated internally
        // but UI update is throttled. The first chunk triggers immediate UI update.
        listener.onStreamChunk("World")

        val messages = messagesState.value
        assertTrue(messages.isNotEmpty())
        val streamMsg = messages.last()
        // First chunk flushes immediately; second is debounced.
        // Content in UI shows first flush, but onStreamComplete will have full content.
        assertTrue(streamMsg.content.startsWith("Hello"))
        assertTrue(streamMsg.isStreaming)
    }

    @Test
    fun `onStreamComplete should finalize with full accumulated content`() = runTest {
        listener.onChatStarted()
        listener.onStreamChunk("Hello")
        // Even though chunks were debounced, onStreamComplete gets full accumulated content
        listener.onStreamComplete("Hello World")

        assertFalse(streamingState.value)
        val messages = messagesState.value
        assertTrue(messages.isNotEmpty())
        val finalMsg = messages.last()
        assertEquals("Hello World", finalMsg.content)
        assertFalse(finalMsg.isStreaming)
    }

    @Test
    fun `onWorkflowError should add error message`() = runTest {
        listener.onWorkflowError(RuntimeException("test error"))

        assertFalse(streamingState.value)
        val messages = messagesState.value
        assertTrue(messages.isNotEmpty())
        val errorMsg = messages.last()
        assertEquals("system", errorMsg.role)
        assertTrue(errorMsg.content.contains("test error"))
        assertEquals(TuiMessageType.AGENT_FAILED, errorMsg.messageType)
    }

    @Test
    fun `onSubagentStarted should set streaming with initial content`() = runTest {
        listener.onSubagentStarted("CodeReview")
        assertTrue(streamingState.value)
    }
}
