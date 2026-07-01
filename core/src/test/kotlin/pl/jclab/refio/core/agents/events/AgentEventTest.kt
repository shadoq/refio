package pl.jclab.refio.core.agents.events

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for AgentEvent sealed interface — all 12 event subtypes
 * and Artifact data class.
 */
class AgentEventTest {

    private val baseFields = mapOf(
        "id" to "evt-1",
        "sessionId" to "sess-1",
        "sourceAgentId" to "agent-1",
        "timestamp" to 1000L,
        "correlationId" to "corr-1"
    )

    @Nested
    inner class LifecycleEvents {

        @Test
        fun `AgentStarted should hold all fields`() {
            val event = AgentEvent.AgentStarted(
                id = "evt-1", sessionId = "s1", sourceAgentId = "a1",
                timestamp = 1000L, correlationId = "c1",
                agentName = "Analyst", profile = "business-analyst",
                task = "Analyze code", model = "claude", dependsOn = listOf("base")
            )
            assertIs<AgentEvent>(event)
            assertEquals("Analyst", event.agentName)
            assertEquals("business-analyst", event.profile)
            assertEquals(listOf("base"), event.dependsOn)
            assertEquals("s1", event.sessionId)
        }

        @Test
        fun `AgentCompleted should hold summary and metrics`() {
            val event = AgentEvent.AgentCompleted(
                id = "evt-1", sessionId = "s1", sourceAgentId = "a1",
                timestamp = 1000L, correlationId = "c1",
                summary = "Done", artifacts = listOf(Artifact("FILE_CREATED", "test.kt")),
                tokensUsed = 500, costUsd = 0.05, durationMs = 3000
            )
            assertEquals("Done", event.summary)
            assertEquals(1, event.artifacts.size)
            assertEquals(500L, event.tokensUsed)
            assertEquals(0.05, event.costUsd)
        }

        @Test
        fun `AgentFailed should hold error and recoverable flag`() {
            val event = AgentEvent.AgentFailed(
                id = "evt-1", sessionId = "s1", sourceAgentId = "a1",
                timestamp = 1000L, correlationId = "c1",
                error = "Compilation failed", recoverable = true
            )
            assertEquals("Compilation failed", event.error)
            assertTrue(event.recoverable)
        }
    }

    @Nested
    inner class DataExchangeEvents {

        @Test
        fun `DataRequest should support broadcast (null target)`() {
            val event = AgentEvent.DataRequest(
                id = "evt-1", sessionId = "s1", sourceAgentId = "a1",
                timestamp = 1000L, correlationId = "c1",
                targetAgentId = null, query = "Anyone know this?",
                context = mapOf("topic" to "auth")
            )
            assertEquals(null, event.targetAgentId)
            assertEquals("Anyone know this?", event.query)
            assertEquals("auth", event.context["topic"])
        }

        @Test
        fun `DataResponse should reference request`() {
            val event = AgentEvent.DataResponse(
                id = "evt-2", sessionId = "s1", sourceAgentId = "a2",
                timestamp = 1000L, correlationId = "c1",
                targetAgentId = "a1", requestId = "evt-1",
                response = "Here is the answer",
                artifacts = listOf(Artifact("ANALYSIS", "result.md", content = "..."))
            )
            assertEquals("evt-1", event.requestId)
            assertEquals("a1", event.targetAgentId)
            assertEquals(1, event.artifacts.size)
        }
    }

    @Nested
    inner class ApprovalEvents {

        @Test
        fun `ApprovalRequired should hold action details`() {
            val event = AgentEvent.ApprovalRequired(
                id = "evt-1", sessionId = "s1", sourceAgentId = "a1",
                timestamp = 1000L, correlationId = "c1",
                action = "Write src/User.kt", actionType = "FILE_WRITE",
                risk = "MEDIUM", details = mapOf("path" to "src/User.kt", "size" to "1024")
            )
            assertEquals("FILE_WRITE", event.actionType)
            assertEquals("MEDIUM", event.risk)
            assertEquals(2, event.details.size)
        }

        @Test
        fun `ApprovalDecision should hold approve and reason`() {
            val event = AgentEvent.ApprovalDecision(
                id = "evt-2", sessionId = "s1", sourceAgentId = "user",
                timestamp = 1000L, correlationId = "c1",
                approvalId = "evt-1", approved = false, reason = "Too risky"
            )
            assertEquals("evt-1", event.approvalId)
            assertEquals(false, event.approved)
            assertEquals("Too risky", event.reason)
        }
    }

    @Nested
    inner class ProgressEvents {

        @Test
        fun `ProgressUpdate should hold phase and progress`() {
            val event = AgentEvent.ProgressUpdate(
                id = "evt-1", sessionId = "s1", sourceAgentId = "a1",
                timestamp = 1000L, correlationId = "c1",
                phase = "coding", message = "Implementing UserController",
                progress = 0.5f
            )
            assertEquals("coding", event.phase)
            assertEquals(0.5f, event.progress)
        }

        @Test
        fun `StreamChunk should hold delta and accumulated`() {
            val event = AgentEvent.StreamChunk(
                id = "evt-1", sessionId = "s1", sourceAgentId = "a1",
                timestamp = 1000L, correlationId = "c1",
                delta = " world", accumulated = "Hello world", isComplete = false
            )
            assertEquals(" world", event.delta)
            assertEquals("Hello world", event.accumulated)
            assertEquals(false, event.isComplete)
        }
    }

    @Nested
    inner class ArtifactModel {

        @Test
        fun `Artifact should support all fields`() {
            val artifact = Artifact(
                type = "FILE_MODIFIED",
                name = "User.kt",
                content = "class User {}",
                path = "/src/User.kt",
                metadata = mapOf("language" to "kotlin")
            )
            assertEquals("FILE_MODIFIED", artifact.type)
            assertEquals("class User {}", artifact.content)
            assertEquals("kotlin", artifact.metadata["language"])
        }

        @Test
        fun `Artifact should support minimal creation`() {
            val artifact = Artifact(type = "ANALYSIS", name = "report.md")
            assertEquals("ANALYSIS", artifact.type)
            assertEquals(null, artifact.content)
            assertEquals(null, artifact.path)
            assertTrue(artifact.metadata.isEmpty())
        }
    }

    @Nested
    inner class CommonInterfaceContract {

        @Test
        fun `all event types should implement AgentEvent interface`() {
            val events: List<AgentEvent> = listOf(
                AgentEvent.AgentStarted("1", "s", "a", 0, "c", "n", null, "t", null, emptyList()),
                AgentEvent.AgentCompleted("2", "s", "a", 0, "c", "done", emptyList(), 0, 0.0, 0),
                AgentEvent.AgentFailed("3", "s", "a", 0, "c", "err", false),
                AgentEvent.DataRequest("4", "s", "a", 0, "c", null, "q"),
                AgentEvent.DataResponse("5", "s", "a", 0, "c", "t", "r", "resp"),
                AgentEvent.ApprovalRequired("9", "s", "a", 0, "c", "act", "type", "risk", emptyMap()),
                AgentEvent.ApprovalDecision("10", "s", "a", 0, "c", "aid", true, null),
                AgentEvent.ProgressUpdate("11", "s", "a", 0, "c", "phase", "msg", null),
                AgentEvent.StreamChunk("12", "s", "a", 0, "c", "d", "acc", false)
            )

            assertEquals(9, events.size, "Should have all 9 event types")
            events.forEach { event ->
                assertNotNull(event.id)
                assertEquals("s", event.sessionId)
                assertEquals("a", event.sourceAgentId)
            }
        }
    }
}
