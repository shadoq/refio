package pl.jclab.refio.core.agents.events

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AgentEventHandlerTest {

    private val eventBus = AgentEventBus()

    private fun CoroutineScope.createHandler(
        agentId: String = "agent-1",
        sessionId: String = "session-1"
    ) = AgentEventHandler(
        agentId = agentId,
        sessionId = sessionId,
        correlationId = "corr-1",
        eventBus = eventBus,
        scope = this
    )

    @Nested
    inner class DataRequestResponse {

        @Test
        fun `requestData should emit DataRequest and return DataResponse`() = runTest {
            val handler = createHandler()

            // Simulate another agent responding
            val responderJob = launch {
                eventBus.events.collect { event ->
                    if (event is AgentEvent.DataRequest && event.sourceAgentId == "agent-1") {
                        delay(10) // small delay to simulate processing
                        eventBus.emit(AgentEvent.DataResponse(
                            id = UUID.randomUUID().toString(),
                            sessionId = "session-1",
                            sourceAgentId = "agent-2",
                            timestamp = System.currentTimeMillis(),
                            correlationId = "corr-1",
                            targetAgentId = "agent-1",
                            requestId = event.id,
                            response = "Here is the data"
                        ))
                    }
                }
            }

            val response = handler.requestData(
                targetAgentId = "agent-2",
                query = "What fields does User have?",
                timeout = 5.seconds
            )

            assertNotNull(response)
            assertEquals("Here is the data", response.response)
            assertEquals("agent-2", response.sourceAgentId)

            responderJob.cancel()
            handler.shutdown()
        }

        @Test
        fun `requestData should return null on timeout`() = runTest {
            val handler = createHandler()

            val response = handler.requestData(
                targetAgentId = "agent-2",
                query = "Will timeout",
                timeout = 100.milliseconds
            )

            assertNull(response)
            handler.shutdown()
        }

        @Test
        fun `requestData should include context in emitted event`() = runTest {
            val emittedEvents = mutableListOf<AgentEvent>()
            val collectJob = launch {
                eventBus.events.collect { emittedEvents.add(it) }
            }

            val handler = createHandler()

            handler.requestData(
                targetAgentId = "agent-2",
                query = "test",
                context = mapOf("key" to "value"),
                timeout = 50.milliseconds
            )

            collectJob.cancel()

            val dataRequest = emittedEvents.filterIsInstance<AgentEvent.DataRequest>().firstOrNull()
            assertNotNull(dataRequest)
            assertEquals("test", dataRequest.query)
            assertEquals(mapOf("key" to "value"), dataRequest.context)
            assertEquals("agent-1", dataRequest.sourceAgentId)
            assertEquals("agent-2", dataRequest.targetAgentId)

            handler.shutdown()
        }

        @Test
        fun `should ignore DataResponse for different agent`() = runTest {
            val handler = createHandler(agentId = "agent-1")

            launch {
                delay(10)
                eventBus.emit(AgentEvent.DataResponse(
                    id = UUID.randomUUID().toString(),
                    sessionId = "session-1",
                    sourceAgentId = "agent-2",
                    timestamp = System.currentTimeMillis(),
                    correlationId = "corr-1",
                    targetAgentId = "agent-3", // Wrong target
                    requestId = "some-request-id",
                    response = "Not for agent-1"
                ))
            }

            val response = handler.requestData(
                targetAgentId = "agent-2",
                query = "ignored",
                timeout = 200.milliseconds
            )

            assertNull(response, "Should not receive response meant for different agent")
            handler.shutdown()
        }
    }

    @Nested
    inner class ApprovalFlow {

        @Test
        fun `requestApproval should return true when approved`() = runTest {
            val handler = createHandler()

            val responderJob = launch {
                eventBus.events.collect { event ->
                    if (event is AgentEvent.ApprovalRequired) {
                        delay(10)
                        eventBus.emit(AgentEvent.ApprovalDecision(
                            id = UUID.randomUUID().toString(),
                            sessionId = "session-1",
                            sourceAgentId = "user",
                            timestamp = System.currentTimeMillis(),
                            correlationId = "corr-1",
                            approvalId = event.id,
                            approved = true,
                            reason = null
                        ))
                    }
                }
            }

            val approved = handler.requestApproval(
                action = "Write file",
                actionType = "FILE_WRITE",
                risk = "MEDIUM",
                details = mapOf("path" to "src/User.kt")
            )

            assertTrue(approved)
            responderJob.cancel()
            handler.shutdown()
        }

        @Test
        fun `requestApproval should return false when rejected`() = runTest {
            val handler = createHandler()

            val responderJob = launch {
                eventBus.events.collect { event ->
                    if (event is AgentEvent.ApprovalRequired) {
                        delay(10)
                        eventBus.emit(AgentEvent.ApprovalDecision(
                            id = UUID.randomUUID().toString(),
                            sessionId = "session-1",
                            sourceAgentId = "user",
                            timestamp = System.currentTimeMillis(),
                            correlationId = "corr-1",
                            approvalId = event.id,
                            approved = false,
                            reason = "Too risky"
                        ))
                    }
                }
            }

            val approved = handler.requestApproval(
                action = "Delete file",
                actionType = "FILE_DELETE",
                risk = "HIGH",
                details = mapOf("path" to "important.kt")
            )

            assertFalse(approved)
            responderJob.cancel()
            handler.shutdown()
        }

        @Test
        fun `requestApproval should auto-approve on timeout`() = runTest {
            val handler = createHandler()

            val approved = handler.requestApproval(
                action = "Minor operation",
                actionType = "OTHER",
                risk = "LOW",
                details = emptyMap(),
                autoApproveAfterMs = 100
            )

            assertTrue(approved, "Should auto-approve on timeout")
            handler.shutdown()
        }

        @Test
        fun `requestApproval should emit ApprovalRequired event`() = runTest {
            val emittedEvents = mutableListOf<AgentEvent>()
            val collectJob = launch {
                eventBus.events.collect { emittedEvents.add(it) }
            }

            val handler = createHandler()

            handler.requestApproval(
                action = "test action",
                actionType = "FILE_WRITE",
                risk = "MEDIUM",
                details = mapOf("path" to "test.kt"),
                autoApproveAfterMs = 50
            )

            collectJob.cancel()

            val approval = emittedEvents.filterIsInstance<AgentEvent.ApprovalRequired>().firstOrNull()
            assertNotNull(approval)
            assertEquals("test action", approval.action)
            assertEquals("FILE_WRITE", approval.actionType)
            assertEquals("MEDIUM", approval.risk)
            assertEquals(mapOf("path" to "test.kt"), approval.details)

            handler.shutdown()
        }
    }

    @Nested
    inner class ShutdownBehavior {

        @Test
        fun `shutdown should not crash`() = runTest {
            val handler = createHandler()
            handler.shutdown()

            // After shutdown, emitting events should not cause issues
            eventBus.emit(AgentEvent.ProgressUpdate(
                id = UUID.randomUUID().toString(),
                sessionId = "session-1",
                sourceAgentId = "agent-1",
                timestamp = System.currentTimeMillis(),
                correlationId = "corr-1",
                phase = "test",
                message = "after shutdown",
                progress = null
            ))
        }

        @Test
        fun `concurrent requestData calls should timeout independently`() = runTest {
            val handler = createHandler()

            val job1 = async {
                handler.requestData("agent-2", "query1", timeout = 100.milliseconds)
            }
            val job2 = async {
                handler.requestData("agent-3", "query2", timeout = 100.milliseconds)
            }

            val r1 = job1.await()
            val r2 = job2.await()

            assertNull(r1)
            assertNull(r2)
            handler.shutdown()
        }
    }

    @Nested
    inner class OrphanedDeferreds {

        @Test
        fun `late response after timeout should not cause exception`() = runTest {
            val handler = createHandler()

            // Request with short timeout — no response will come
            val response = handler.requestData(
                targetAgentId = "agent-2",
                query = "Will timeout",
                timeout = 100.milliseconds
            )
            assertNull(response, "Should return null on timeout")

            // Emit a late DataResponse with a requestId that was already cleaned up.
            // The handler should not crash.
            eventBus.emit(AgentEvent.DataResponse(
                id = UUID.randomUUID().toString(),
                sessionId = "session-1",
                sourceAgentId = "agent-2",
                timestamp = System.currentTimeMillis(),
                correlationId = "corr-1",
                targetAgentId = "agent-1",
                requestId = "already-cleaned-up-id",
                response = "Late response"
            ))

            // Handler still works for new requests
            val responderJob = launch {
                eventBus.events.collect { event ->
                    if (event is AgentEvent.DataRequest && event.sourceAgentId == "agent-1") {
                        delay(10)
                        eventBus.emit(AgentEvent.DataResponse(
                            id = UUID.randomUUID().toString(),
                            sessionId = "session-1",
                            sourceAgentId = "agent-2",
                            timestamp = System.currentTimeMillis(),
                            correlationId = "corr-1",
                            targetAgentId = "agent-1",
                            requestId = event.id,
                            response = "Fresh response"
                        ))
                    }
                }
            }

            val freshResponse = handler.requestData(
                targetAgentId = "agent-2",
                query = "New request after timeout",
                timeout = 5.seconds
            )
            assertNotNull(freshResponse, "Handler should still work after previous timeout")
            assertEquals("Fresh response", freshResponse.response)

            responderJob.cancel()
            handler.shutdown()
        }
    }

    @Nested
    inner class ShutdownWithPendingRequests {

        @Test
        fun `shutdown cancels pending data request`() = runTest {
            val handler = createHandler()

            // Launch a request that will never get a response
            val requestJob = async {
                handler.requestData(
                    targetAgentId = "agent-2",
                    query = "Will be cancelled",
                    timeout = 60.seconds
                )
            }

            // Give the request time to register
            delay(50)

            // Shutdown should cancel pending deferred
            handler.shutdown()

            // The pending request should complete (cancelled deferred -> CancellationException)
            val result = try {
                requestJob.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                null // Expected: deferred was cancelled
            }

            // Either null (cancelled) or null (timeout) — both are acceptable
            assertNull(result, "Pending request should be cancelled on shutdown")
        }

        @Test
        fun `shutdown cancels pending approval`() = runTest {
            val handler = createHandler()

            val approvalJob = async {
                handler.requestApproval(
                    action = "Write file",
                    actionType = "FILE_WRITE",
                    risk = "HIGH",
                    details = emptyMap()
                    // No autoApproveAfterMs — waits indefinitely
                )
            }

            delay(50)
            handler.shutdown()

            val result = try {
                approvalJob.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                null
            }

            assertNull(result, "Pending approval should be cancelled on shutdown")
        }
    }

    @Nested
    inner class ConcurrentShutdownRace {

        @Test
        fun `concurrent requestData and shutdown should not throw ConcurrentModificationException`() = runTest {
            val handler = createHandler()

            // Launch 10 concurrent requestData coroutines
            val jobs = (0 until 10).map { i ->
                async {
                    try {
                        handler.requestData(
                            targetAgentId = "agent-$i",
                            query = "query-$i",
                            timeout = 5.seconds
                        )
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        null // Expected when shutdown cancels deferred
                    }
                }
            }

            // After some requests are registered, shut down
            delay(50)
            handler.shutdown()

            // All jobs should complete without ConcurrentModificationException
            val results = jobs.map { it.await() }
            assertTrue(results.all { it == null }, "All requests should be null (cancelled or timed out)")

            // No hanging coroutines — all jobs completed
        }
    }
}
