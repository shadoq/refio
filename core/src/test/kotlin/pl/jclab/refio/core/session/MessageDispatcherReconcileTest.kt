package pl.jclab.refio.core.session

import org.junit.jupiter.api.Test
import pl.jclab.refio.api.models.Message
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reconciliation between the in-memory message list and the DB snapshot on every reload
 * (`MessageDispatcher.reconcileMessages`).
 *
 * The bug this guards against: during a turn several writers push transient bubbles into the
 * in-memory list (the human's prompt, the streaming assistant, per-subagent streams) BEFORE they
 * exist in the DB. A mid-turn reload used to replace the whole list with `DB + in-memory system
 * only`, so those not-yet-persisted bubbles blinked out — the user's own prompt vanished and the
 * transcript churned. Reconciliation must keep a transient ONLY until its DB counterpart exists,
 * and never produce a duplicate once it does.
 */
class MessageDispatcherReconcileTest {

    private fun msg(
        id: String,
        role: String,
        content: String,
        createdAt: Long,
        agentDepth: Int? = null,
        agentName: String? = null,
        isStreaming: Boolean = false,
        taskId: String = "task-1",
    ) = Message(
        id = id,
        taskId = taskId,
        role = role,
        content = content,
        createdAt = createdAt,
        agentDepth = agentDepth,
        agentName = agentName,
        isStreaming = isStreaming,
    )

    @Test
    fun `subagent run keeps the human top-level prompt even though the DB only has the agent-scoped echo`() {
        // Human typed a prompt that invoked a subagent. It lives in-memory at depth 0; the turn loop
        // persists it tagged to the subagent (depth 1), under a different id. Without preservation the
        // reload would drop the in-memory bubble and the user's own prompt would disappear.
        val inMemory = listOf(
            msg("A", "user", "investigate the docs", createdAt = 10, agentDepth = 0),
        )
        val db = listOf(
            msg("B", "user", "investigate the docs", createdAt = 12, agentDepth = 1, agentName = "doc-engineer"),
            msg("C", "assistant", "Looking…", createdAt = 13, agentDepth = 1, agentName = "doc-engineer"),
        )

        val result = MessageDispatcher.reconcileMessages(inMemory, db, "task-1")

        assertTrue(result.any { it.id == "A" }, "Top-level human prompt must survive the reload")
        assertEquals(listOf("A", "B", "C"), result.map { it.id }, "Preserved prompt keeps chronological position")
    }

    @Test
    fun `a plain turn drops the in-memory prompt in favor of its persisted top-level copy (no duplicate)`() {
        val inMemory = listOf(
            msg("A", "user", "fix the bug", createdAt = 10, agentDepth = 0),
        )
        val db = listOf(
            // Same content, top-level (depth 0/null), different id — the persisted form of the same prompt.
            msg("B", "user", "fix the bug", createdAt = 11, agentDepth = null),
        )

        val result = MessageDispatcher.reconcileMessages(inMemory, db, "task-1")

        assertEquals(listOf("B"), result.map { it.id }, "Only the DB copy should remain — no duplicated user bubble")
    }

    @Test
    fun `a streaming assistant bubble is preserved while it streams`() {
        val inMemory = listOf(
            msg("S", "assistant", "partial out", createdAt = 20, isStreaming = true),
        )
        val result = MessageDispatcher.reconcileMessages(inMemory, emptyList(), "task-1")

        assertEquals(listOf("S"), result.map { it.id }, "Live stream must not blink out on a mid-turn reload")
    }

    @Test
    fun `a finished non-streaming assistant transient is dropped so the DB copy can take over`() {
        // Once the stream ends the message is marked non-streaming; the DB-persisted copy (different id)
        // replaces it. Preserving it here would duplicate the bubble.
        val inMemory = listOf(
            msg("S", "assistant", "final out", createdAt = 20, isStreaming = false),
        )
        val result = MessageDispatcher.reconcileMessages(inMemory, emptyList(), "task-1")

        assertTrue(result.none { it.id == "S" }, "A completed transient assistant must not survive (would duplicate)")
    }

    @Test
    fun `in-memory system notices are still preserved`() {
        val inMemory = listOf(
            msg("Y", "system", "Agent guidance…", createdAt = 30),
        )
        val result = MessageDispatcher.reconcileMessages(inMemory, emptyList(), "task-1")

        assertEquals(listOf("Y"), result.map { it.id })
    }

    @Test
    fun `messages from other sessions are never merged in`() {
        val inMemory = listOf(
            msg("Z", "system", "stale", createdAt = 5, taskId = "other-task"),
        )
        val db = listOf(msg("B", "user", "hi", createdAt = 6))

        val result = MessageDispatcher.reconcileMessages(inMemory, db, "task-1")

        assertEquals(listOf("B"), result.map { it.id }, "Cross-session leftovers must not leak into the active list")
    }

    @Test
    fun `with nothing to preserve the DB list passes through unchanged`() {
        val db = listOf(
            msg("B", "user", "hi", createdAt = 6),
            msg("C", "assistant", "hello", createdAt = 7),
        )
        val result = MessageDispatcher.reconcileMessages(emptyList(), db, "task-1")

        assertEquals(db, result, "No preserved transients → identical DB list (no needless re-sort/copy)")
    }
}
