package pl.jclab.refio.core.debug

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.api.MultiAgentInstanceResponse
import pl.jclab.refio.core.api.MultiAgentSessionResponse
import pl.jclab.refio.core.db.ApiLog
import pl.jclab.refio.core.db.ApprovalStatus
import pl.jclab.refio.core.db.ChatMessage
import pl.jclab.refio.core.db.ExecutionMode
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.Subtask
import pl.jclab.refio.core.db.SubtaskKind
import pl.jclab.refio.core.db.Task
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.TaskStatus
import pl.jclab.refio.core.db.repositories.ApiLogRepository
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SessionDebugExporterTest {

    private val taskRepository = mockk<TaskRepository>()
    private val subtaskRepository = mockk<SubtaskRepository>()
    private val apiLogRepository = mockk<ApiLogRepository>()
    private val chatMessageRepository = mockk<ChatMessageRepository>()
    private val exporter = SessionDebugExporter(
        taskRepository, subtaskRepository, apiLogRepository, chatMessageRepository
    )

    private fun task(tokensIn: Int = 100, tokensOut: Int = 20, costUsd: Double = 0.5) = Task(
        id = "t1", name = "bench", mode = TaskMode.AGENT, status = TaskStatus.values().first(),
        readOnly = false, pinned = false, executionMode = ExecutionMode.AUTO,
        requiresPlanApproval = false, planApproved = false, uiState = null, coreApiVersion = null,
        projectId = "p", projectPath = "/p",
        tokensIn = tokensIn, tokensOut = tokensOut, costUsd = costUsd,
        createdAt = 1_000, updatedAt = 5_000,
    )

    private fun subtask(orderIndex: Int = 0, errorMessage: String? = null) = Subtask(
        id = "s$orderIndex", taskId = "t1", orderIndex = orderIndex,
        kind = SubtaskKind.values().first(), status = TaskStatus.values().first(),
        description = "do thing", paramsJson = null, stepPlanJson = null, summary = null,
        requiresApproval = false, approvalStatus = ApprovalStatus.values().first(), approvedAt = null,
        result = null, errorMessage = errorMessage, errorStacktrace = null,
        llmModel = "qwen3.5:9b", llmProvider = "ollama",
        inputTokens = 10, outputTokens = 5, costUsd = 0.1, latencyMs = 200,
        snapshotIdBeforeWrite = null, createdAt = 1_000, updatedAt = 2_000,
        startedAt = 1_000, completedAt = 2_000,
    )

    private fun apiLog(errorMessage: String? = null) = ApiLog(
        id = "a1", taskId = "t1", subtaskId = null, provider = "ollama", model = "qwen3.5:9b",
        endpoint = "http://x", requestSource = "agent", requestPayload = "{}", responsePayload = "{}",
        httpStatus = 200, inputTokens = 50, outputTokens = 10, costUsd = 0.2, latencyMs = 300,
        errorMessage = errorMessage, errorType = errorMessage?.let { "Error" }, createdAt = 1_500,
    )

    private fun msg(role: MessageRole, content: String) = ChatMessage(
        id = "m-${content.hashCode()}", taskId = "t1", role = role, content = content,
        metadata = null, toolCalls = null, toolCallId = null,
        tokensIn = null, tokensOut = null, cost = null, createdAt = 2_000,
    )

    private fun stub(
        task: Task? = task(),
        subtasks: List<Subtask> = listOf(subtask()),
        apiLogs: List<ApiLog> = listOf(apiLog()),
        messages: List<ChatMessage> = listOf(msg(MessageRole.ASSISTANT, "done")),
    ) {
        every { taskRepository.findById(any()) } returns task
        every { subtaskRepository.findByTaskId(any()) } returns subtasks
        every { apiLogRepository.findByTaskId(any()) } returns apiLogs
        every { chatMessageRepository.findByTaskId(any()) } returns messages
    }

    @Test
    fun `contextOverflow surfaces in run json metrics when a task overflowed the window`() {
        ContextOverflowTracker.reset()
        try {
            stub()
            // Before any overflow: false (silence must never read as a truncated run).
            val clean = exporter.export("t1", SessionDebugOptions.forLevel(DebugLevel.STANDARD))
            assertEquals(false, clean.metrics.contextOverflow)

            // After the adapter/turn-loop records an overflow for this task: true.
            ContextOverflowTracker.markOverflow("t1")
            val overflowed = exporter.export("t1", SessionDebugOptions.forLevel(DebugLevel.STANDARD))
            assertTrue(overflowed.metrics.contextOverflow, "overflow must propagate to run.json")
            assertTrue(
                exporter.toJson(overflowed).contains("\"contextOverflow\": true"),
                "run.json must carry contextOverflow=true for the e2e harness (docs/0061)"
            )
        } finally {
            ContextOverflowTracker.reset()
        }
    }

    @Test
    fun `toJson carries schemaVersion and session metrics`() {
        stub(task = task(tokensIn = 123))
        val json = exporter.toJson(exporter.export("t1", SessionDebugOptions.forLevel(DebugLevel.STANDARD)))
        assertTrue(json.contains("\"schemaVersion\": 1"), "should carry schema version")
        assertTrue(json.contains("\"tokensIn\": 123"), "should carry session token metrics")
    }

    @Test
    fun `minimal level omits subtasks conversation and apiLogs but keeps counts`() {
        stub()
        val snap = exporter.export("t1", SessionDebugOptions.forLevel(DebugLevel.MINIMAL))
        assertTrue(snap.subtasks.isEmpty())
        assertTrue(snap.conversation.isEmpty())
        assertTrue(snap.apiLogs.isEmpty())
        // Counts are still derived from the raw rows even when the detail lists are omitted.
        assertEquals(1, snap.metrics.apiCallCount)
        assertEquals(1, snap.metrics.toolCallCount)
    }

    @Test
    fun `standard level includes subtasks conversation and apiLogs`() {
        stub()
        val snap = exporter.export("t1", SessionDebugOptions.forLevel(DebugLevel.STANDARD))
        assertEquals(1, snap.subtasks.size)
        assertEquals(1, snap.conversation.size)
        assertEquals(1, snap.apiLogs.size)
    }

    @Test
    fun `missing task yields an error not a crash`() {
        stub(task = null, subtasks = emptyList(), apiLogs = emptyList(), messages = emptyList())
        val snap = exporter.export("ghost", SessionDebugOptions.forLevel(DebugLevel.STANDARD))
        assertEquals("ghost", snap.session.id)
        assertEquals("UNKNOWN", snap.session.status)
        assertTrue(snap.errors.any { it.contains("Task not found") })
    }

    @Test
    fun `tool call arguments are exported so an e2e assertion can match which subagent ran`() {
        val withCall = ChatMessage(
            id = "m-call", taskId = "t1", role = MessageRole.ASSISTANT, content = "delegating",
            metadata = null,
            toolCalls = listOf(
                pl.jclab.refio.core.db.ToolCallData(
                    id = "tc1", name = "invoke_subagent",
                    arguments = """{"subagent_name":"code-reviewer","goal":"find the bug"}""",
                )
            ),
            toolCallId = null, tokensIn = null, tokensOut = null, cost = null, createdAt = 2_000,
        )
        stub(messages = listOf(withCall))
        val snap = exporter.export("t1", SessionDebugOptions.forLevel(DebugLevel.STANDARD))
        val detail = snap.conversation.single().toolCallDetails.single()
        assertEquals("invoke_subagent", detail.name)
        // The raw arguments JSON must survive so a needle like subagent_name=code-reviewer can match.
        assertTrue(detail.arguments.contains("code-reviewer"), "arguments JSON must carry the subagent name")
        assertTrue(
            exporter.toJson(snap).contains("code-reviewer"),
            "run.json must expose tool-call arguments for the e2e tool_invoked assertion"
        )
    }

    @Test
    fun `finalOutput is the last assistant message`() {
        stub(messages = listOf(msg(MessageRole.USER, "make it"), msg(MessageRole.ASSISTANT, "created snake.html")))
        val snap = exporter.export("t1", SessionDebugOptions.forLevel(DebugLevel.MINIMAL))
        assertEquals("created snake.html", snap.finalOutput)
    }

    @Test
    fun `a failed agent always leaves a reason in errors, even when it carries none`() {
        // A multi-agent run that ends in failure must be diagnosable from run.json. The error list
        // was built only from agents that carried an error string, so an agent that came back
        // FAILED without one produced an empty list - a failure with no recorded cause anywhere.
        val response = MultiAgentSessionResponse(
            sessionId = "s1", name = "pipeline", status = "COMPLETED",
            agents = listOf(
                agent("analyst", tokensIn = 5000, tokensOut = 300, costUsd = 0.0, started = 1_000),
                MultiAgentInstanceResponse(
                    agentName = "coder", status = "FAILED", success = false, response = "",
                    tokensUsed = 42_000, tokensIn = 41_000, tokensOut = 1_000, costUsd = 0.0,
                    durationMs = 300, startedAt = 2_000, completedAt = 2_300, error = null,
                ),
            ),
            totalTokens = 47_300, totalTokensIn = 46_000, totalTokensOut = 1_300, totalCostUsd = 0.0,
            durationMs = 4_000, createdAt = 1_000, completedAt = 5_000,
        )

        val snap = exporter.exportMultiAgent(response, model = "m", options = SessionDebugOptions.forLevel(DebugLevel.STANDARD))

        val coderErrors = snap.errors.filter { it.contains("coder") }
        assertEquals(1, coderErrors.size, "the failed agent must appear exactly once in errors: ${snap.errors}")
        assertTrue(coderErrors.first().contains("FAILED"), "the entry must name the agent status: ${coderErrors.first()}")
        assertTrue(snap.errors.none { it.contains("analyst") }, "a successful agent must not be reported as an error")
    }

    private fun agent(name: String, tokensIn: Int, tokensOut: Int, costUsd: Double, started: Long) =
        MultiAgentInstanceResponse(
            agentName = name, status = "COMPLETED", success = true, response = "done",
            tokensUsed = (tokensIn + tokensOut).toLong(), tokensIn = tokensIn, tokensOut = tokensOut,
            costUsd = costUsd, durationMs = 100, startedAt = started, completedAt = started + 100,
        )

    @Test
    fun `multi-agent snapshot reports the real aggregate token split, not zero`() {
        // Regression: the multi-agent run.json used to hardcode metrics to 0, so the e2e stats layer
        // (which reads .metrics.tokensOut) undercounted every multi-agent scenario to nothing. The
        // rolled-up figure must be the sum of the per-agent OUTPUT tokens - not a combined in+out
        // number that would inflate tokensOut by the (much larger) prompt tokens.
        val response = MultiAgentSessionResponse(
            sessionId = "s1", name = "pipeline", status = "COMPLETED",
            agents = listOf(
                agent("analyst", tokensIn = 5000, tokensOut = 300, costUsd = 0.01, started = 2_000),
                agent("coder", tokensIn = 6000, tokensOut = 500, costUsd = 0.02, started = 1_000),
            ),
            totalTokens = 11_800, totalTokensIn = 11_000, totalTokensOut = 800, totalCostUsd = 0.03,
            durationMs = 4_000, createdAt = 1_000, completedAt = 5_000,
        )
        val snap = exporter.exportMultiAgent(response, model = "ollama/qwen3.5:122b", options = SessionDebugOptions.forLevel(DebugLevel.STANDARD))

        assertEquals(800, snap.metrics.tokensOut, "tokensOut must be the summed OUTPUT tokens")
        assertEquals(11_000, snap.metrics.tokensIn)
        assertEquals(0.03, snap.metrics.costUsd)
        assertEquals(2, snap.metrics.apiCallCount, "one API call slot per agent")
        assertEquals("MULTI_AGENT", snap.session.mode)
        assertEquals(800, snap.session.tokensOut)
        // Agents ordered by real start time: coder (started 1000) before analyst (started 2000).
        assertEquals(listOf("coder", "analyst"), snap.multiAgent?.agents?.map { it.agentName })
        assertEquals(500, snap.multiAgent?.agents?.first()?.tokensOut, "per-agent split is preserved")
    }
}
