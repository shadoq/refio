package pl.jclab.refio.core.debug

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
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
}
