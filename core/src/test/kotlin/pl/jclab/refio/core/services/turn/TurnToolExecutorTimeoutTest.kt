package pl.jclab.refio.core.services.turn

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.db.ExecutionMode
import pl.jclab.refio.core.db.SubtaskKind
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.ToolCallData
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.services.SnapshotService
import pl.jclab.refio.core.services.ToolExecutor
import pl.jclab.refio.core.services.ToolResultSummarizer
import pl.jclab.refio.core.services.ToolResultSummary
import pl.jclab.refio.core.services.TurnLoopConfig
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.core.tools.base.ToolResult
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the wall-clock budget of a single tool call.
 *
 * WHY this matters: a tool that never returns parks the whole turn forever - the user sees a
 * spinner and the only way out is killing the IDE. The configured budget (TurnLoopConfig.toolTimeout)
 * used to be read by nothing on the live path, and a CPU-bound tool (a quadratic regex over one very
 * long line) does not react to cooperative cancellation either. So the budget must both exist AND be
 * able to walk away from a tool that ignores cancellation.
 */
class TurnToolExecutorTimeoutTest {

    private val toolExecutor = mockk<ToolExecutor>()
    private val toolRegistry = mockk<ToolRegistry>()
    private val subtaskRepository = mockk<SubtaskRepository>(relaxed = true)
    private val toolResultSummarizer = mockk<ToolResultSummarizer>()
    private val snapshotService = mockk<SnapshotService>(relaxed = true)

    private lateinit var executor: TurnToolExecutor
    private lateinit var config: TurnLoopConfig

    @BeforeEach
    fun setup() {
        executor = TurnToolExecutor(
            toolExecutor = toolExecutor,
            toolRegistry = toolRegistry,
            subtaskRepository = subtaskRepository,
            toolResultSummarizer = toolResultSummarizer,
            snapshotService = snapshotService
        )
        config = mockk(relaxed = true)
        every { config.enableSnapshots } returns false
        every { config.toolTimeout } returns Duration.ofMillis(50)
        every { config.networkToolTimeout } returns Duration.ofSeconds(5)
        every { toolRegistry.toSubtaskKind(any()) } returns SubtaskKind.PLAN_STEP
        every { toolRegistry.getTool(any()) } returns readOnlyTool()
        coEvery { toolResultSummarizer.summarizeToolResult(any(), any(), any(), any()) } returns
            ToolResultSummary("summary", wasSummarized = false, 0, 0, 0.0)
    }

    private fun readOnlyTool() = mockk<Tool>(relaxed = true).also {
        every { it.mode } returns ToolMode.READ_ONLY
    }

    private suspend fun execute(toolName: String) = executor.executeSingleTool(
        taskId = "t1",
        toolCall = ToolCallData(id = "c1", name = toolName, arguments = """{"pattern":"[a-z]*!"}"""),
        subtaskId = "s1",
        listener = null, iteration = 1, _config = config,
        mode = TaskMode.AGENT, executionMode = ExecutionMode.AUTO,
        runId = "r1", depth = 0, profileOverrides = null, _subtaskIds = emptyMap()
    )

    @Test
    fun `a tool that outruns the configured budget fails the call instead of parking the turn`() = runTest {
        // grep_search over a 2 MB single-line file is quadratic in `find()`; without a budget the
        // turn waits for hours. The tool must come back as a normal failed result so the agent can
        // react (narrow the pattern) and the loop can continue.
        coEvery { toolExecutor.executeTool(any(), any()) } coAnswers {
            Thread.sleep(3_000)
            ToolResult(success = true, output = "finished far too late")
        }

        val result = execute("grep_search")

        assertFalse(result.success, "a tool that blew its time budget must be reported as failed")
        assertTrue(
            result.content.contains("timed out", ignoreCase = true),
            "the agent must be told WHY the call failed, got: ${result.content}"
        )
    }

    @Test
    fun `the abandoned tool thread is interrupted so the work actually stops`() = runTest {
        // Returning an error while a runaway thread keeps burning a core is only half a timeout:
        // every later tool call in the session competes with it. Cancellation must reach the thread.
        val interrupted = CountDownLatch(1)
        coEvery { toolExecutor.executeTool(any(), any()) } coAnswers {
            try {
                Thread.sleep(30_000)
            } catch (e: InterruptedException) {
                interrupted.countDown()
            }
            ToolResult(success = true, output = "finished far too late")
        }

        execute("grep_search")

        assertTrue(
            interrupted.await(10, TimeUnit.SECONDS),
            "the timed-out tool thread must be interrupted, not left running"
        )
    }

    @Test
    fun `a search that waits on a local embedding model gets the network budget, not the in-process one`() = runTest {
        // A cold local embedding model needs tens of seconds to load before rag_search can answer.
        // Judging it by the in-process budget (30 s in PLAN) turns a working local-first setup into
        // a stream of false failures.
        coEvery { toolExecutor.executeTool(any(), any()) } coAnswers {
            Thread.sleep(300)
            ToolResult(success = true, output = "3 fragments")
        }

        val result = execute("rag_search")

        assertTrue(result.success, "rag_search must be judged by the network budget: ${result.content}")
    }

    @Test
    fun `an MCP tool gets the network budget too - it runs against an external server`() = runTest {
        coEvery { toolExecutor.executeTool(any(), any()) } coAnswers {
            Thread.sleep(300)
            ToolResult(success = true, output = "server answered")
        }

        val result = execute("mcp_github_list_issues")

        assertTrue(result.success, "an MCP call must be judged by the network budget: ${result.content}")
    }

    @Test
    fun `a network tool is still bounded - it is slower, not unbounded`() = runTest {
        every { config.networkToolTimeout } returns Duration.ofMillis(100)
        coEvery { toolExecutor.executeTool(any(), any()) } coAnswers {
            Thread.sleep(3_000)
            ToolResult(success = true, output = "finished far too late")
        }

        val result = execute("http_request")

        assertFalse(result.success, "a stalled network call must still be abandoned, not parked forever")
    }

    @Test
    fun `a nested subagent run is exempt - it is bounded by its own turn loop, not by this budget`() = runTest {
        // invoke_subagent / delegate_to_strong_model run a whole nested turn loop and legitimately
        // take minutes. Applying the per-tool budget to them would kill every delegation.
        coEvery { toolExecutor.executeTool(any(), any()) } coAnswers {
            Thread.sleep(300)
            ToolResult(success = true, output = "subagent finished")
        }

        val result = execute("invoke_subagent")

        assertTrue(result.success, "delegation must not be cut off by the per-tool budget")
    }
}
