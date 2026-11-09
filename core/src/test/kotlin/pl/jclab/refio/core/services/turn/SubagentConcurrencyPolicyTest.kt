package pl.jclab.refio.core.services.turn

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.db.ExecutionMode
import pl.jclab.refio.core.db.SubtaskKind
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.ToolCallData
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.services.OllamaRequestGate
import pl.jclab.refio.core.services.ToolExecutor
import pl.jclab.refio.core.services.ToolResultSummarizer
import pl.jclab.refio.core.services.TurnLoopConfig
import pl.jclab.refio.core.subagents.SubagentRouter
import pl.jclab.refio.core.subagents.models.SubagentDefinition
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.core.tools.base.ToolResult
import pl.jclab.refio.core.tools.implementations.InvokeSubagentTool
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Several subagents asked for in one reply. Two that write files would edit the same project at the
 * same time, and on a local Ollama that serves one request at a time parallel runs only wait for
 * each other while holding their contexts in memory. Those cases run one after another; read-only
 * subagents on a provider that serves requests side by side still run together.
 */
class SubagentConcurrencyPolicyTest {

    @Test
    fun `writing subagents run one after another`() {
        assertFalse(SubagentConcurrencyPolicy.canRunInParallel(true, TaskMode.AGENT, "anthropic", ollamaMaxConcurrent = 1))
    }

    @Test
    fun `read-only subagents on ollama run in order by default`() {
        assertFalse(SubagentConcurrencyPolicy.canRunInParallel(false, TaskMode.AGENT, "ollama", ollamaMaxConcurrent = 1))
    }

    @Test
    fun `read-only subagents on ollama with four slots run together`() {
        assertTrue(SubagentConcurrencyPolicy.canRunInParallel(false, TaskMode.AGENT, "ollama", ollamaMaxConcurrent = 4))
    }

    @Test
    fun `read-only subagents on a cloud provider run together`() {
        assertTrue(SubagentConcurrencyPolicy.canRunInParallel(false, TaskMode.AGENT, "anthropic", ollamaMaxConcurrent = 1))
    }

    @Test
    fun `a writing definition cannot write in plan mode, so it is not held back for that`() {
        assertTrue(SubagentConcurrencyPolicy.canRunInParallel(true, TaskMode.PLAN, "anthropic", ollamaMaxConcurrent = 1))
    }

    // ---- the same rule applied to a real batch of calls ----

    private val savedOllamaSlots = OllamaRequestGate.maxConcurrentPerEndpoint

    @AfterEach
    fun restoreOllamaSlots() {
        OllamaRequestGate.maxConcurrentPerEndpoint = savedOllamaSlots
    }

    private fun tool(toolName: String, toolMode: ToolMode) = mockk<Tool>(relaxed = true).also {
        every { it.name } returns toolName
        every { it.mode } returns toolMode
    }

    /** Runs one reply with two invoke_subagent calls and returns how many of them ran at the same time. */
    private suspend fun peakConcurrentSubagents(definition: SubagentDefinition, mode: TaskMode = TaskMode.AGENT): Int {
        val router = mockk<SubagentRouter>()
        every { router.getSubagent(definition.name) } returns definition
        val invoker = InvokeSubagentTool({ router }, { _, _, _ -> error("not called: the executor is mocked") }, { mockk(relaxed = true) })
        val registry = mockk<ToolRegistry>()
        every { registry.getTool("invoke_subagent") } returns invoker
        every { registry.getTool("read_file") } returns tool("read_file", ToolMode.READ_ONLY)
        every { registry.getTool("advance_code_editing") } returns tool("advance_code_editing", ToolMode.WRITE)
        every { registry.getAllTools() } returns listOf(invoker, tool("read_file", ToolMode.READ_ONLY), tool("advance_code_editing", ToolMode.WRITE))
        every { registry.toSubtaskKind(any()) } returns SubtaskKind.INVOKE_SUBAGENT

        val running = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val toolExecutor = mockk<ToolExecutor>()
        coEvery { toolExecutor.executeTool(any(), any()) } coAnswers {
            peak.accumulateAndGet(running.incrementAndGet()) { a, b -> maxOf(a, b) }
            delay(100)
            running.decrementAndGet()
            ToolResult(success = true, output = "report")
        }
        val config = mockk<TurnLoopConfig>(relaxed = true)
        every { config.parallelReadTools } returns true
        every { config.maxParallelReadTools } returns 6
        every { config.enableSnapshots } returns false
        every { config.toolTimeout } returns Duration.ofSeconds(60)
        every { config.networkToolTimeout } returns Duration.ofSeconds(60)

        val executor = TurnToolExecutor(
            toolExecutor = toolExecutor, toolRegistry = registry,
            subtaskRepository = mockk<SubtaskRepository>(relaxed = true), toolResultSummarizer = mockk<ToolResultSummarizer>()
        )
        val call = { id: String -> ToolCallData(id = id, name = "invoke_subagent", arguments = """{"subagent_name":"${definition.name}","goal":"do $id"}""") }
        val results = executor.executeToolCalls(
            taskId = "t1", toolCalls = listOf(call("c1"), call("c2")), mode = mode, executionMode = ExecutionMode.AUTO,
            listener = null, iteration = 1, config = config, runId = "r1", depth = 0
        )
        assertEquals(2, results.size)
        return peak.get()
    }

    private fun definition(model: String, tools: List<String>) =
        SubagentDefinition(name = "worker", description = "worker", systemPrompt = "work", allowedTools = tools, model = model)

    @Test
    fun `two writing subagents in one reply run one after another`() = runTest {
        assertEquals(1, peakConcurrentSubagents(definition("anthropic/claude-sonnet-5-5", listOf("read_file", "advance_code_editing"))))
    }

    @Test
    fun `two read-only subagents on a cloud provider run together`() = runTest {
        assertEquals(2, peakConcurrentSubagents(definition("anthropic/claude-sonnet-5-5", listOf("read_file"))))
    }

    @Test
    fun `two read-only subagents on a one-slot ollama run one after another`() = runTest {
        OllamaRequestGate.maxConcurrentPerEndpoint = 1
        assertEquals(1, peakConcurrentSubagents(definition("ollama/qwen3.5:9b", listOf("read_file"))))
    }
}
