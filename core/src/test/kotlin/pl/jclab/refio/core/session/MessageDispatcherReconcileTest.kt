package pl.jclab.refio.core.session

import org.junit.jupiter.api.Test
import pl.jclab.refio.api.models.Message
import pl.jclab.refio.api.models.ToolCallDisplayInfo
import pl.jclab.refio.api.models.ToolCallStatus
import pl.jclab.refio.api.models.ToolDisplayType
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

    private fun toolMsg(
        id: String,
        toolCallId: String,
        createdAt: Long,
        isStreaming: Boolean,
        status: ToolCallStatus,
        taskId: String = "task-1",
    ) = Message(
        id = id,
        taskId = taskId,
        role = "assistant",
        content = "",
        createdAt = createdAt,
        isStreaming = isStreaming,
        toolCallInfo = ToolCallDisplayInfo(
            toolName = "advance_code_editing",
            toolCallId = toolCallId,
            displayType = ToolDisplayType.CODE_EDIT,
            parameters = emptyMap(),
            status = status,
        ),
    )

    @Test
    fun `a tool bubble that has not streamed yet survives a reload before its first delta`() {
        // This is what killed the live char counter. Between the call starting and its first delta the
        // bubble is EXECUTING but not yet flagged streaming. Dropping it in that window detaches every
        // later delta: they map over an id that is no longer in the list, so the list comes back equal
        // and the StateFlow - which emits on inequality - goes silent for the whole generation.
        val inMemory = listOf(
            toolMsg("temp-call-9", toolCallId = "call-9", createdAt = 30, isStreaming = false, status = ToolCallStatus.EXECUTING),
        )

        val result = MessageDispatcher.reconcileMessages(inMemory, emptyList(), "task-1")

        assertEquals(listOf("temp-call-9"), result.map { it.id },
            "A running tool call must survive until it finishes, even before its first delta")
    }

    @Test
    fun `a live tool bubble wins over its persisted twin so the running counter is not replaced by an empty row`() {
        // The assistant row carrying a tool call is persisted BEFORE the tool finishes, so the persisted
        // display copy ("<messageId>:tc0", built with empty content) can show up while the live bubble
        // ("temp-<toolCallId>") is still streaming. Rendering both is the duplicate the user sees; and
        // resolving it in favour of the DB row would swap the live char counter for an empty bubble.
        // While streaming, the transient is the only copy with content, so it must win.
        val inMemory = listOf(
            toolMsg("temp-call-9", toolCallId = "call-9", createdAt = 30, isStreaming = true, status = ToolCallStatus.EXECUTING),
        )
        val db = listOf(
            toolMsg("msg-7:tc0", toolCallId = "call-9", createdAt = 31, isStreaming = false, status = ToolCallStatus.EXECUTING),
        )

        val result = MessageDispatcher.reconcileMessages(inMemory, db, "task-1")

        assertEquals(listOf("temp-call-9"), result.map { it.id },
            "Exactly one bubble per tool call, and while it streams it must be the live one")
    }

    @Test
    fun `once the tool bubble stops streaming the persisted twin takes over`() {
        // Mirror of the case above: after finalize the transient is no longer streaming, so it is
        // dropped and the persisted row (which now carries the result) is the single surviving bubble.
        val inMemory = listOf(
            toolMsg("temp-call-9", toolCallId = "call-9", createdAt = 30, isStreaming = false, status = ToolCallStatus.COMPLETED),
        )
        val db = listOf(
            toolMsg("msg-7:tc0", toolCallId = "call-9", createdAt = 31, isStreaming = false, status = ToolCallStatus.COMPLETED),
        )

        val result = MessageDispatcher.reconcileMessages(inMemory, db, "task-1")

        assertEquals(listOf("msg-7:tc0"), result.map { it.id },
            "A finished tool call must render once, from the persisted row")
    }

    @Test
    fun `a live subagent stream never hides earlier persisted turns of the same agent`() {
        // Guard against keying the hold-back on agentName: an agent can be invoked repeatedly, so its
        // name maps to many persisted rows. Only toolCallId (unique per call) may hold a DB row back.
        val inMemory = listOf(
            msg("uuid-live", "assistant", "partial…", createdAt = 40, isStreaming = true,
                agentDepth = 1, agentName = "doc-engineer"),
        )
        val db = listOf(
            msg("earlier-1", "assistant", "first answer", createdAt = 10, agentDepth = 1, agentName = "doc-engineer"),
            msg("earlier-2", "assistant", "second answer", createdAt = 20, agentDepth = 1, agentName = "doc-engineer"),
        )

        val result = MessageDispatcher.reconcileMessages(inMemory, db, "task-1")

        assertEquals(listOf("earlier-1", "earlier-2", "uuid-live"), result.map { it.id },
            "Earlier persisted turns of the same agent must stay visible alongside the live stream")
    }

    @Test
    fun `a streaming tool bubble survives while the DB has no twin yet`() {
        val inMemory = listOf(
            toolMsg("temp-call-9", toolCallId = "call-9", createdAt = 30, isStreaming = true, status = ToolCallStatus.EXECUTING),
        )
        val result = MessageDispatcher.reconcileMessages(inMemory, emptyList(), "task-1")

        assertEquals(listOf("temp-call-9"), result.map { it.id },
            "A live tool stream with no persisted twin must stay visible")
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

    private fun editMsg(
        id: String,
        toolCallId: String,
        content: String,
        createdAt: Long,
        isStreaming: Boolean,
        status: ToolCallStatus,
    ) = Message(
        id = id,
        taskId = "task-1",
        role = "assistant",
        content = content,
        createdAt = createdAt,
        isStreaming = isStreaming,
        toolCallInfo = ToolCallDisplayInfo(
            toolName = "advance_code_editing",
            toolCallId = toolCallId,
            displayType = ToolDisplayType.LLM_EDIT,
            parameters = mapOf("edit_description" to "make a game"),
            status = status,
        ),
    )

    @Test
    fun `a finished code-editing transient with content is kept over its empty persisted twin`() {
        // advance_code_editing streams the FULL generated file into the transient's content, but the
        // persisted display twin ("<messageId>:tc0") is stored EMPTY. Dropping the finished transient for
        // that empty twin makes the completed "Generated content" preview show nothing - only the
        // edit_description parameter remains, reading as if the tool surfaced its instructions instead of
        // the code. The richer transient must be kept so the final bubble shows the generated code.
        val inMemory = listOf(
            editMsg("temp-call-9", toolCallId = "call-9", content = "<!DOCTYPE html>… full file",
                createdAt = 30, isStreaming = false, status = ToolCallStatus.COMPLETED),
        )
        val db = listOf(
            editMsg("msg-7:tc0", toolCallId = "call-9", content = "",
                createdAt = 31, isStreaming = false, status = ToolCallStatus.COMPLETED),
        )

        val result = MessageDispatcher.reconcileMessages(inMemory, db, "task-1")

        assertEquals(listOf("temp-call-9"), result.map { it.id },
            "The finished code-editing transient (holding the generated code) wins over its empty twin")
    }

    @Test
    fun `a finished code-editing transient is dropped when its persisted twin already carries content`() {
        // The keep-the-transient rule is scoped to the EMPTY-twin case. If the persisted row already has
        // content (nothing to lose), the normal rule applies: a finished transient is dropped so the tool
        // call renders once, from the DB row.
        val inMemory = listOf(
            editMsg("temp-call-9", toolCallId = "call-9", content = "<!DOCTYPE html>… full file",
                createdAt = 30, isStreaming = false, status = ToolCallStatus.COMPLETED),
        )
        val db = listOf(
            editMsg("msg-7:tc0", toolCallId = "call-9", content = "<!DOCTYPE html>… full file",
                createdAt = 31, isStreaming = false, status = ToolCallStatus.COMPLETED),
        )

        val result = MessageDispatcher.reconcileMessages(inMemory, db, "task-1")

        assertEquals(listOf("msg-7:tc0"), result.map { it.id },
            "With a non-empty persisted twin the finished transient is dropped (single bubble, from DB)")
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
