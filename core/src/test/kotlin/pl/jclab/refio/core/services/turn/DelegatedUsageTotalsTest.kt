package pl.jclab.refio.core.services.turn

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.api.StreamCallback
import pl.jclab.refio.core.api.TurnRequest
import pl.jclab.refio.core.db.ExecutionMode
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.SubtaskKind
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.ToolCallData
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.LLMUsage
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.ToolCall
import pl.jclab.refio.core.services.ToolExecutor
import pl.jclab.refio.core.services.ToolResultSummarizer
import pl.jclab.refio.core.services.TurnLoopConfig
import pl.jclab.refio.core.services.TurnResult
import pl.jclab.refio.core.subagents.SubagentRouter
import pl.jclab.refio.core.subagents.models.SubagentDefinition
import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.core.tools.implementations.DelegateToStrongModelTool
import pl.jclab.refio.core.tools.implementations.InvokeSubagentTool
import pl.jclab.refio.core.tools.implementations.LlmCallTool
import pl.jclab.refio.testutil.TestDatabase
import java.time.Duration
import kotlin.test.assertEquals

/**
 * The task history shows each task's tokens and cost summed over its messages, so every model call
 * has to land on exactly one message. A subagent, or a strong model given tools, runs a turn of its
 * own and writes its replies with their usage into the same task, so the parent's tool message must
 * not repeat that usage. A one-shot call made inside a tool has no message of its own, so its usage
 * belongs on the tool message.
 */
class DelegatedUsageTotalsTest {

    private companion object {
        const val TASK = "task-usage"
    }

    private lateinit var db: TestDatabase.SharedInMemoryDb
    private val tasks = TaskRepository()
    private val messages = ChatMessageRepository()
    private val subtasks = SubtaskRepository()

    private val llmClient = mockk<LLMClient>()
    private val configService = mockk<ConfigService>(relaxed = true)

    @BeforeEach
    fun setup() {
        db = TestDatabase.createSharedInMemory()
        transaction {
            tasks.create(name = "Usage totals", mode = TaskMode.AGENT, projectId = "p", projectPath = "/tmp/p", id = TASK)
        }
        every { configService.getStrongModel(any(), any()) } returns ("strong-model" to "openrouter")
        coEvery { configService.getModel(ModelOperation.WEAK, any(), any()) } returns ("weak-model" to "openrouter")
        coEvery {
            llmClient.complete(
                provider = any(), model = any(), messages = any(), systemPrompt = any(), maxTokens = any(),
                temperature = any(), responseFormat = any(), taskId = any(), source = any(), thinking = any(), stream = any(),
            )
        } returns LLMResponse(
            content = "one-shot answer",
            usage = LLMUsage(inputTokens = 10, outputTokens = 20, totalTokens = 30),
            model = "m", provider = "openrouter", cost = 0.05, finishReason = "stop",
        )
    }

    @AfterEach
    fun tearDown() {
        db.keepAlive.close()
    }

    /** Stands in for the child turn: writes the child's reply with its usage, as the turn loop does, and returns the run totals. */
    private val childTurn: suspend (TurnRequest, TurnEventListener?, StreamCallback?) -> TurnResult = { request, _, _ ->
        val overrides = request.profileOverrides
        messages.create(
            TASK, MessageRole.ASSISTANT, "child done",
            tokensIn = 100, tokensOut = 50, cost = 0.005,
            agentInstanceId = overrides?.agentInstanceId, agentName = overrides?.subagentName, agentDepth = 1
        )
        TurnResult(
            success = true, response = "child report", iterations = 1,
            tokensIn = 100, tokensOut = 50, cost = 0.005, toolsUsed = listOf("read_file")
        )
    }

    /** Runs one call of [tool] through the tool executor and writes its tool message the way the turn loop does. */
    private suspend fun runAndRecord(tool: Tool, kind: SubtaskKind, arguments: String) {
        val registry = mockk<ToolRegistry>()
        every { registry.getTool(tool.name) } returns tool
        every { registry.getAllTools() } returns listOf(tool)
        every { registry.toSubtaskKind(tool.name) } returns kind
        val toolExecutor = mockk<ToolExecutor>()
        coEvery { toolExecutor.executeTool(any(), any()) } coAnswers { tool.execute(firstArg<ToolCall>().params) }
        val config = mockk<TurnLoopConfig>(relaxed = true)
        every { config.parallelReadTools } returns true
        every { config.maxParallelReadTools } returns 6
        every { config.enableSnapshots } returns false
        every { config.toolTimeout } returns Duration.ofSeconds(60)
        every { config.networkToolTimeout } returns Duration.ofSeconds(60)

        val executor = TurnToolExecutor(
            toolExecutor = toolExecutor, toolRegistry = registry,
            subtaskRepository = subtasks, toolResultSummarizer = mockk<ToolResultSummarizer>()
        )
        val results = executor.executeToolCalls(
            taskId = TASK, toolCalls = listOf(ToolCallData(id = "call-1", name = tool.name, arguments = arguments)),
            mode = TaskMode.AGENT, executionMode = ExecutionMode.AUTO,
            listener = null, iteration = 1, config = config, runId = "r1", depth = 0
        )
        assertEquals(1, results.size)
        for ((toolCall, resultData) in results) {
            messages.createToolResult(
                taskId = TASK, toolCallId = toolCall.id, subtaskId = resultData.subtaskId, result = resultData.content,
                isSummarized = resultData.isSummarized, rawOutput = resultData.rawOutput, metadata = resultData.metadata,
                tokensIn = resultData.subTokensIn, tokensOut = resultData.subTokensOut, cost = resultData.subCost,
            )
        }
    }

    private fun assertTaskTotals(tokensIn: Int, tokensOut: Int, cost: Double) {
        val stats = tasks.getTaskWithStats(TASK)!!
        assertEquals(tokensIn to tokensOut, stats.tokensIn to stats.tokensOut, "tokens in/out shown for the task")
        assertEquals(cost, stats.costUsd, 1e-9, "cost shown for the task")
    }

    @Test
    fun `a subagent's usage is counted once in the task totals`() = runTest {
        val router = mockk<SubagentRouter>()
        every { router.getSubagent("explorer") } returns
            SubagentDefinition(name = "explorer", description = "explores", systemPrompt = "explore", model = "openrouter/m")

        runAndRecord(
            InvokeSubagentTool({ router }, childTurn, { configService }),
            SubtaskKind.INVOKE_SUBAGENT, """{"subagent_name":"explorer","goal":"find callers"}"""
        )

        assertTaskTotals(tokensIn = 100, tokensOut = 50, cost = 0.005)
    }

    @Test
    fun `a strong model given tools is counted once in the task totals`() = runTest {
        runAndRecord(
            DelegateToStrongModelTool(llmClient = llmClient, configServiceProvider = { configService }, runTurnCallback = childTurn),
            SubtaskKind.DELEGATE_TO_STRONG_MODEL, """{"task":"review the design","allow_tools":true}"""
        )

        assertTaskTotals(tokensIn = 100, tokensOut = 50, cost = 0.005)
    }

    @Test
    fun `a one-shot model call inside a tool is counted in the task totals`() = runTest {
        runAndRecord(
            LlmCallTool(llmClient = llmClient, configService = configService, sandbox = mockk<PathSandbox>(relaxed = true)),
            SubtaskKind.LLM_CALL, """{"prompt":"classify this","data":"some text"}"""
        )

        assertTaskTotals(tokensIn = 10, tokensOut = 20, cost = 0.05)
    }

    @Test
    fun `a one-shot strong model call is counted in the task totals`() = runTest {
        runAndRecord(
            DelegateToStrongModelTool(llmClient = llmClient, configServiceProvider = { configService }, runTurnCallback = childTurn),
            SubtaskKind.DELEGATE_TO_STRONG_MODEL, """{"task":"review the design"}"""
        )

        assertTaskTotals(tokensIn = 10, tokensOut = 20, cost = 0.05)
    }
}
