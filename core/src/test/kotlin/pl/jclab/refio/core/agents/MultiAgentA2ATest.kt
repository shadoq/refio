package pl.jclab.refio.core.agents

import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.agents.events.AgentEvent
import pl.jclab.refio.core.agents.events.AgentEventBus
import pl.jclab.refio.core.agents.events.AgentInboxRegistry
import pl.jclab.refio.core.tools.implementations.AnswerMessageTool
import pl.jclab.refio.core.tools.implementations.SendMessageTool
import java.util.UUID

/**
 * End-to-end A2A integration test per docs/0054-multiagent.md §4 Step 7.
 *
 * Drives `MultiAgentRunner` with a fake executor that exercises only the event plumbing
 * — no LLM, no DB. Verifies the full round-trip: asker emits DataRequest → answerer's
 * inbox sees it → answerer replies via answer_message → DataResponse on the bus →
 * asker's turn would resume with the response content.
 */
class MultiAgentA2ATest {

    @Test
    fun `peer A2A round-trip emits DataRequest and matching DataResponse`() = runBlocking {
        val bus = AgentEventBus()
        val registry = AgentInboxRegistry()
        val runner = MultiAgentRunner(bus, registry)

        val sessionId = "session-${UUID.randomUUID()}"
        val sendTool = SendMessageTool(bus, registry)
        val answerTool = AnswerMessageTool(bus, registry)

        // Capture both events for end-state assertions before the session completes.
        val requestDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            bus.events.first { it is AgentEvent.DataRequest && it.sessionId == sessionId } as AgentEvent.DataRequest
        }
        val responseDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            bus.events.first { it is AgentEvent.DataResponse && it.sessionId == sessionId } as AgentEvent.DataResponse
        }

        val specs = listOf(
            AgentSpec(name = "asker", task = "ask answerer for the answer"),
            AgentSpec(name = "answerer", task = "reply when asked")
        )

        val results = runner.run(sessionId, specs) { spec, agentId ->
            when (spec.name) {
                "asker" -> {
                    // Both agents are launched in parallel without dependsOn. Wait until the
                    // peer's inbox has registered before sending — otherwise the spec's
                    // fail-fast peer validation rejects the request. Mirrors the guidance in
                    // docs/0054-multiagent.md §5 "ask after depends_on or after you've received
                    // a lifecycle event from the peer".
                    withTimeout(2_000) {
                        while (!registry.isRegistered(sessionId, "answerer")) yield()
                    }
                    // 1) Asker sends a question to "answerer" via the real SendMessageTool path.
                    val sendResult = sendTool.execute(mapOf(
                        "_agent_id" to agentId,
                        "_session_id" to sessionId,
                        "message" to "What is the answer?",
                        "type" to "question",
                        "to" to "answerer"
                    ))
                    assertTrue(sendResult.success, "send_message should succeed (got: ${sendResult.error ?: sendResult.output})")
                    assertEquals("AWAITING_RESPONSE", sendResult.metadata!!["type"])
                    val requestId = sendResult.metadata!!["requestId"] as String

                    // 2) Wait for the DataResponse that answerer will emit (mirrors what
                    //    AgentTurnLoop's AWAITING_RESPONSE handler does at lines 1113-1118).
                    val resp = withTimeout(2_000) {
                        bus.events
                            .first { it is AgentEvent.DataResponse && it.requestId == requestId } as AgentEvent.DataResponse
                    }

                    AgentResult(
                        agentName = spec.name,
                        success = true,
                        response = "asker got: ${resp.response}",
                        tokensUsed = 0,
                        costUsd = 0.0,
                        durationMs = 0
                    )
                }
                "answerer" -> {
                    // Poll the inbox until the request arrives, then reply via answer_message.
                    var seen: AgentEvent.DataRequest? = null
                    repeat(200) {
                        val pending = registry.find(sessionId, "answerer")?.snapshotPending().orEmpty()
                        if (pending.isNotEmpty()) {
                            seen = pending.first()
                            return@repeat
                        }
                        yield()
                    }
                    val req = seen ?: error("answerer never received a request")
                    val ans = answerTool.execute(mapOf(
                        "_agent_name" to "answerer",
                        "_session_id" to sessionId,
                        "requestId" to req.id,
                        "response" to "the answer is 42"
                    ))
                    assertTrue(ans.success, "answer_message should succeed (got: ${ans.error ?: ans.output})")

                    AgentResult(
                        agentName = spec.name,
                        success = true,
                        response = "replied to ${req.sourceAgentId}",
                        tokensUsed = 0,
                        costUsd = 0.0,
                        durationMs = 0
                    )
                }
                else -> error("unexpected agent ${spec.name}")
            }
        }

        // Final assertions on event bus contents. Bounded so a missed emission fails fast
        // instead of hanging the test (as happened before adding the inbox-registration wait).
        val req = withTimeout(2_000) { requestDeferred.await() }
        assertEquals("answerer", req.targetAgentId)
        assertEquals("What is the answer?", req.query)

        val resp = withTimeout(2_000) { responseDeferred.await() }
        assertEquals(req.id, resp.requestId)
        assertEquals("answerer", resp.sourceAgentId)
        assertEquals("the answer is 42", resp.response)

        // Both agents complete cleanly.
        assertTrue(results["asker"]?.success == true, "asker failed: ${results["asker"]?.error}")
        assertTrue(results["asker"]?.response?.contains("the answer is 42") == true)
        assertTrue(results["answerer"]?.success == true, "answerer failed: ${results["answerer"]?.error}")

        // Inbox unregistered after completion — no leak.
        assertTrue(registry.listAgents(sessionId).isEmpty(), "inboxes should be cleared after session")
    }

    @Test
    fun `send_message to unknown peer fails fast without waiting`() = runBlocking {
        val bus = AgentEventBus()
        val registry = AgentInboxRegistry()
        val runner = MultiAgentRunner(bus, registry)

        val sessionId = "session-${UUID.randomUUID()}"
        val sendTool = SendMessageTool(bus, registry)

        val specs = listOf(AgentSpec(name = "asker", task = "send to nonexistent"))

        val results = runner.run(sessionId, specs) { _, agentId ->
            // No "ghost" agent in this session → send_message must reject immediately.
            val r = sendTool.execute(mapOf(
                "_agent_id" to agentId,
                "_session_id" to sessionId,
                "message" to "hi",
                "type" to "question",
                "to" to "ghost"
            ))
            assertEquals(false, r.success)
            assertTrue((r.error ?: "").contains("ghost"))
            AgentResult("asker", true, "rejected as expected", 0, 0.0, 0)
        }
        assertTrue(results["asker"]?.success == true)
    }
}
