package pl.jclab.refio.core.tools.implementations

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.agents.events.AgentEvent
import pl.jclab.refio.core.agents.events.AgentEventBus
import pl.jclab.refio.core.agents.events.AgentEventHandler
import pl.jclab.refio.core.services.AgentPlanService
import pl.jclab.refio.core.services.PlanStepStatus
import pl.jclab.refio.core.services.context.WorkingMemoryService
import pl.jclab.refio.core.tools.base.ToolCategory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for multi-agent orchestration patterns.
 */
class MultiAgentIntegrationTest {

    private lateinit var planService: AgentPlanService
    private lateinit var memoryService: WorkingMemoryService
    private lateinit var eventBus: AgentEventBus

    @BeforeEach
    fun setup() {
        planService = AgentPlanService()
        memoryService = WorkingMemoryService()
        eventBus = AgentEventBus()
    }

    @Test
    fun `parallel tool execution simulation with shared memory`() = runBlocking {
        val taskId = "task-parallel-1"
        val memoryTool = MemoryTool(memoryService)

        // Agent A writes findings
        memoryTool.execute(mapOf(
            "_task_id" to taskId,
            "_agent_name" to "searcher",
            "action" to "write",
            "key" to "findings",
            "value" to "Found 5 emails from user@example.com",
            "importance" to 9
        ))

        // Agent B writes findings
        memoryTool.execute(mapOf(
            "_task_id" to taskId,
            "_agent_name" to "calendar",
            "action" to "write",
            "key" to "findings",
            "value" to "3 meetings scheduled today",
            "importance" to 7
        ))

        // Orchestrator reads all memory
        val result = memoryTool.execute(mapOf(
            "_task_id" to taskId,
            "action" to "read"
        ))
        assertTrue(result.success)
        assertTrue(result.output!!.contains("5 emails"))
        assertTrue(result.output!!.contains("3 meetings"))
    }

    @Test
    fun `sequential pipeline with plan tracking`() = runBlocking {
        val taskId = "task-pipeline-1"
        val tasksTool = TasksTool(planService)

        // Create plan
        tasksTool.execute(mapOf(
            "_task_id" to taskId,
            "action" to "plan",
            "steps" to listOf(
                mapOf("title" to "Search emails"),
                mapOf("title" to "Analyze content"),
                mapOf("title" to "Generate report")
            )
        ))

        // Step 1: Search
        tasksTool.execute(mapOf(
            "_task_id" to taskId, "action" to "update", "step_index" to 0, "status" to "in_progress"
        ))
        tasksTool.execute(mapOf(
            "_task_id" to taskId, "action" to "update", "step_index" to 0, "status" to "completed", "note" to "Found 5 emails"
        ))

        // Step 2: Analyze
        tasksTool.execute(mapOf(
            "_task_id" to taskId, "action" to "update", "step_index" to 1, "status" to "completed", "note" to "Pattern identified"
        ))

        // Check plan section
        val section = planService.buildPlanSection(taskId)
        assertTrue(section.contains("[x] 1. Search emails"))
        assertTrue(section.contains("[x] 2. Analyze content"))
        assertTrue(section.contains("[ ] 3. Generate report"))
        assertTrue(section.contains("Note: Found 5 emails"))
    }

    @Test
    fun `AWAITING_RESPONSE via send_message`() = runBlocking {
        val sendTool = SendMessageTool(eventBus)

        val result = sendTool.execute(mapOf(
            "_agent_id" to "child-agent",
            "_task_id" to "task-1",
            "_parent_run_id" to "parent-run",
            "message" to "How long should the promotion last?",
            "type" to "question"
        ))

        assertTrue(result.success)
        assertEquals("AWAITING_RESPONSE", result.metadata!!["type"])
        val requestId = result.metadata!!["requestId"] as String
        assertNotNull(requestId)

        // Verify event was emitted
        val emittedEvent = eventBus.eventsOfType<AgentEvent.DataRequest>()
            .filter { it.id == requestId }
            .first()
        assertEquals("How long should the promotion last?", emittedEvent.query)
        assertEquals("parent-run", emittedEvent.targetAgentId)
    }

    @Test
    fun `AgentEventHandler requestData and respond flow`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        val childHandler = AgentEventHandler(
            agentId = "child-1",
            sessionId = "session-1",
            correlationId = "corr-1",
            eventBus = eventBus,
            scope = scope
        )

        // Simulate parent responding to child's request
        val responseJob = launch {
            delay(100)
            val request = eventBus.eventsOfType<AgentEvent.DataRequest>().first()
            eventBus.emit(AgentEvent.DataResponse(
                id = "resp-1",
                sessionId = "session-1",
                sourceAgentId = "parent-1",
                timestamp = System.currentTimeMillis(),
                correlationId = "corr-1",
                targetAgentId = "child-1",
                requestId = request.id,
                response = "7 days"
            ))
        }

        val response = childHandler.requestData(
            targetAgentId = "parent-1",
            query = "How long?",
            timeout = kotlin.time.Duration.parse("5s")
        )

        responseJob.join()
        assertNotNull(response)
        assertEquals("7 days", response.response)

        childHandler.shutdown()
        scope.cancel()
    }

    @Test
    fun `session-scoped memory visible across tasks`() = runBlocking {
        val sessionId = "session-shared"
        val memoryTool = MemoryTool(memoryService)

        // Child task writes with session
        memoryTool.execute(mapOf(
            "_task_id" to "child-task-1",
            "_session_id" to sessionId,
            "_agent_name" to "searcher",
            "action" to "write",
            "key" to "findings",
            "value" to "Found password in email"
        ))

        // Different child task writes with same session
        memoryTool.execute(mapOf(
            "_task_id" to "child-task-2",
            "_session_id" to sessionId,
            "_agent_name" to "analyzer",
            "action" to "write",
            "key" to "analysis",
            "value" to "SEC code not yet received"
        ))

        // Session memory shows both
        val sessionMemory = memoryService.buildSessionMemorySection(sessionId, 4096)
        assertTrue(sessionMemory.contains("Found password"))
        assertTrue(sessionMemory.contains("SEC code"))
    }

    @Test
    fun `all SYSTEM tools have correct category`() {
        val tools = listOf(
            TasksTool(planService),
            MemoryTool(memoryService),
            SendMessageTool(eventBus)
        )

        tools.forEach { tool ->
            assertEquals(ToolCategory.SYSTEM, tool.category, "Tool ${tool.name} should have SYSTEM category")
        }
    }
}
