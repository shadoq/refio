package pl.jclab.refio.core.services.turn

import com.google.gson.JsonParser
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.debug.TurnStopReason
import pl.jclab.refio.core.services.TurnResult
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A reply cut off by the output cap looks like any other failed answer in the chat history. The
 * stored assistant message carries the stop reason so a UI can tell "the model ran out of output"
 * from "the model gave up" and offer to continue. Every other exit must store what it always did.
 */
class TurnFinalizerStopReasonTest {

    private val repository = mockk<ChatMessageRepository>(relaxed = true)
    private val finalizer = TurnFinalizer(repository)

    private val failedTurn = TurnResult(
        success = false,
        response = "The agent's response was truncated due to output length limits.",
        iterations = 3,
        tokensIn = 100,
        tokensOut = 4096,
        cost = 0.0,
    )

    private fun storedMetadata(metadata: String?, stopReason: TurnStopReason?): String? {
        finalizer.completeTurn(
            taskId = "task-1",
            result = failedTurn,
            listener = null,
            runId = "run-1",
            parentRunId = null,
            depth = 0,
            persistAssistantMessage = true,
            metadata = metadata,
            stopReason = stopReason,
        )
        val captured = mutableListOf<String?>()
        verify {
            repository.create(
                taskId = "task-1",
                role = MessageRole.ASSISTANT,
                content = any(),
                metadata = captureNullable(captured),
                agentInstanceId = any(),
                agentName = any(),
                agentDepth = any(),
            )
        }
        return captured.single()
    }

    @Test
    fun `a reply cut off by the output cap is stored with the truncation stop reason`() {
        val metadata = storedMetadata(metadata = null, stopReason = TurnStopReason.OUTPUT_TRUNCATED)

        val json = JsonParser.parseString(assertNotNull(metadata)).asJsonObject
        assertEquals("OUTPUT_TRUNCATED", json.get(TurnFinalizer.STOP_REASON_KEY).asString)
    }

    @Test
    fun `a truncated subagent reply keeps its subagent name next to the stop reason`() {
        // The chat groups subagent messages by this key; losing it would move the reply under the parent.
        val metadata = storedMetadata(
            metadata = """{"subagent_name":"reviewer"}""",
            stopReason = TurnStopReason.OUTPUT_TRUNCATED,
        )

        val json = JsonParser.parseString(assertNotNull(metadata)).asJsonObject
        assertEquals("reviewer", json.get("subagent_name").asString)
        assertEquals("OUTPUT_TRUNCATED", json.get(TurnFinalizer.STOP_REASON_KEY).asString)
    }

    @Test
    fun `a turn that stopped for any other reason stores its metadata unchanged`() {
        val subagentMetadata = """{"subagent_name":"reviewer"}"""

        assertEquals(subagentMetadata, storedMetadata(subagentMetadata, TurnStopReason.MAX_ITERATIONS))
    }

    @Test
    fun `a completed top-level turn still stores no metadata at all`() {
        assertNull(storedMetadata(metadata = null, stopReason = TurnStopReason.COMPLETED))
    }

    @Test
    fun `metadata that is not a JSON object is kept rather than replaced by the stop reason`() {
        val metadata = storedMetadata(metadata = "legacy-marker", stopReason = TurnStopReason.OUTPUT_TRUNCATED)

        assertEquals("legacy-marker", metadata)
        assertFalse(metadata.orEmpty().contains(TurnFinalizer.STOP_REASON_KEY))
    }
}
