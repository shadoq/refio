package pl.jclab.refio.core.session

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import pl.jclab.refio.api.models.ToolCallStatus
import pl.jclab.refio.api.models.ToolDisplayType
import pl.jclab.refio.core.db.ToolCallData
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Lifetime of the live "tool call in progress" bubble ([CoreMessageToolCallListener]).
 *
 * These guard the failure that silently froze the whole chat UI: the bubble is the only copy of a
 * running tool call that carries streamed content, and every delta finds it by id. If it is ever
 * absent from the message list while the call runs, the deltas map over a list that no longer holds
 * that id, the resulting list is equal to the previous one, and the StateFlow - which emits on
 * inequality - stops emitting for the entire generation. The visible symptoms were a dead char
 * counter and a chat that only refreshed when the user resized the panel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoreMessageToolCallListenerTest {

    private val taskId = "task-1"
    private val toolCall = ToolCallData(id = "call-1", name = "advance_code_editing", arguments = "{}")

    private fun TestScope.newListener(state: SessionStateManager) = CoreMessageToolCallListener(
        scope = this,
        stateManager = state,
        onReloadSubtasks = {},
        onReloadMessages = {},
        resolveToolDisplayType = { ToolDisplayType.CODE_EDIT },
        parseToolParameters = { emptyMap() },
    )

    @Test
    fun `a tool bubble is live from creation, not from its first delta`() = runTest {
        // Reconciliation on a mid-turn reload keeps an in-memory message only while it is live. A
        // bubble that is still marked non-streaming in the window between the call starting and its
        // first delta gets dropped there, which detaches all later deltas from the list.
        val state = SessionStateManager()
        val listener = newListener(state)

        listener.onToolExecutionStarted(taskId, toolCall)
        advanceUntilIdle()

        val bubble = state.messages.value.single()
        assertTrue(bubble.isStreaming, "A running tool call must be live immediately")
        assertTrue(bubble.isToolStreaming, "The char counter renders only for a tool-streaming bubble")
        assertEquals(ToolCallStatus.EXECUTING, bubble.toolCallInfo?.status)
    }

    @Test
    fun `deltas still reach the list after a reload reconciles in the middle of the call`() = runTest {
        // The exact chain that broke: start the call, let a reload reconcile against a DB that does
        // not hold it yet, then stream. The content must land, proving the bubble survived.
        val state = SessionStateManager()
        val listener = newListener(state)
        listener.onToolExecutionStarted(taskId, toolCall)
        advanceUntilIdle()

        state.updateMessages { inMemory ->
            MessageDispatcher.reconcileMessages(inMemory, emptyList(), taskId)
        }
        advanceUntilIdle()

        listener.onToolStreamChunk(taskId, toolCall.id, delta = "abc", accumulated = "abc")
        advanceUntilIdle()

        assertEquals(
            "abc",
            state.messages.value.single().content,
            "Streamed content must reach the bubble that survived the reload",
        )
    }

    @Test
    fun `finalizing clears the live flags so the persisted row can take over`() = runTest {
        // The bubble must stop being live when the call ends, otherwise reconciliation keeps
        // preserving it next to its persisted copy and the same call renders twice.
        val state = SessionStateManager()
        val listener = newListener(state)
        listener.onToolExecutionStarted(taskId, toolCall)
        advanceUntilIdle()

        listener.onToolExecutionCompleted(taskId, toolCall, result = "done", success = true)
        advanceUntilIdle()

        val bubble = state.messages.value.single()
        assertFalse(bubble.isStreaming, "A finished call must not stay live")
        assertFalse(bubble.isToolStreaming)
        assertEquals(ToolCallStatus.COMPLETED, bubble.toolCallInfo?.status)
    }

    @Test
    fun `a delta that does not grow the text does not rebuild the message list`() = runTest {
        // Adapters emit many deltas whose accumulated text is unchanged. Pushing those costs a
        // coroutine, the messages mutex and a full list rebuild to produce an identical result.
        val state = SessionStateManager()
        val listener = newListener(state)
        listener.onToolExecutionStarted(taskId, toolCall)
        listener.onToolStreamChunk(taskId, toolCall.id, delta = "abc", accumulated = "abc")
        advanceUntilIdle()
        val afterFirstDelta = state.messages.value

        listener.onToolStreamChunk(taskId, toolCall.id, delta = "", accumulated = "abc")
        advanceUntilIdle()

        assertSame(
            afterFirstDelta,
            state.messages.value,
            "An empty delta must not produce a new list",
        )
    }

    @Test
    fun `text that grows after a skipped no-op delta still lands`() = runTest {
        // Skipping unchanged deltas must never swallow the final frame - the case that would make
        // the optimization repeat the very bug this class guards against.
        val state = SessionStateManager()
        val listener = newListener(state)
        listener.onToolExecutionStarted(taskId, toolCall)
        listener.onToolStreamChunk(taskId, toolCall.id, delta = "abc", accumulated = "abc")
        listener.onToolStreamChunk(taskId, toolCall.id, delta = "", accumulated = "abc")
        listener.onToolStreamChunk(taskId, toolCall.id, delta = "de", accumulated = "abcde")
        advanceUntilIdle()

        assertEquals("abcde", state.messages.value.single().content)
    }
}
