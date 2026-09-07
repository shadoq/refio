package pl.jclab.refio.core.services.turn

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertTrue

/**
 * Locks what a user's Stop means for a tool call in flight.
 *
 * WHY this matters: `CancellationException` is an `Exception` in Kotlin, so the broad catch that
 * turns a crashing tool into a failed tool result also catches the user pressing Stop. The turn then
 * reports a tool that "failed", writes a fake error into the chat history and marks the subtask
 * FAILED - for work the user deliberately interrupted. Stop has to leave the turn as cancelled.
 */
class TurnToolExecutorCancellationTest {

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
        every { config.toolTimeout } returns Duration.ofSeconds(60)
        every { config.networkToolTimeout } returns Duration.ofSeconds(60)
        every { toolRegistry.toSubtaskKind(any()) } returns SubtaskKind.PLAN_STEP
        every { toolRegistry.getTool(any()) } returns mockk<Tool>(relaxed = true).also {
            every { it.mode } returns ToolMode.READ_ONLY
        }
        coEvery { toolResultSummarizer.summarizeToolResult(any(), any(), any(), any()) } returns
            ToolResultSummary("summary", wasSummarized = false, 0, 0, 0.0)
    }

    private suspend fun execute(toolName: String) = executor.executeSingleTool(
        taskId = "t1",
        toolCall = ToolCallData(id = "c1", name = toolName, arguments = "{}"),
        subtaskId = "s1",
        listener = null, iteration = 1, _config = config,
        mode = TaskMode.AGENT, executionMode = ExecutionMode.AUTO,
        runId = "r1", depth = 0, profileOverrides = null, _subtaskIds = emptyMap()
    )

    @Test
    fun `stopping the turn cancels the call instead of reporting the tool as failed`() = runBlocking {
        val toolStarted = CountDownLatch(1)
        coEvery { toolExecutor.executeTool(any(), any()) } coAnswers {
            toolStarted.countDown()
            Thread.sleep(30_000)
            ToolResult(success = true, output = "finished after the user gave up")
        }

        val outcome = AtomicReference<Any?>(null)
        val job = launch(Dispatchers.Default) {
            try {
                outcome.set(execute("grep_search"))
            } catch (e: Throwable) {
                outcome.set(e)
            }
        }

        assertTrue(toolStarted.await(10, TimeUnit.SECONDS), "the tool never started")
        job.cancelAndJoin()

        assertTrue(
            outcome.get() is CancellationException,
            "Stop must stay a cancellation, not become a failed tool result: ${outcome.get()}"
        )
    }
}
