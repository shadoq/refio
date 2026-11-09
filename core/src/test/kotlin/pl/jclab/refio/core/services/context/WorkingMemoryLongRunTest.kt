package pl.jclab.refio.core.services.context

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.api.TurnProfileOverrides
import pl.jclab.refio.core.db.SubtaskKind
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.ToolCallData
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.ExecutionMode
import pl.jclab.refio.core.services.ToolExecutor
import pl.jclab.refio.core.services.ToolResultSummarizer
import pl.jclab.refio.core.services.ToolResultSummary
import pl.jclab.refio.core.services.WorkingMemoryIntegration
import pl.jclab.refio.core.services.TurnLoopConfig
import pl.jclab.refio.core.services.turn.TurnToolExecutor
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolInternalParams
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult
import pl.jclab.refio.core.tools.implementations.MemoryTool

/**
 * Working memory over a task that runs for hundreds of steps and several turns. Its job is to keep
 * what the agent must not forget: a constraint the model chose to remember, and facts from the
 * latest steps. Age is counted in steps of the whole task, so a new turn does not look older than
 * the previous one, and a subagent's facts stay in its own space.
 */
class WorkingMemoryLongRunTest {

    private fun fact(step: Int) = WorkingMemoryEntry(
        iteration = step,
        key = if (step % 2 == 0) "changes" else "file_read",
        value = "step $step " + (if (step % 2 == 0) "edited f$step.kt" else "read f$step.kt"),
        importance = if (step % 2 == 0) 8 else 7
    )

    private fun memoryWrite(tool: MemoryTool, value: String, step: Int, importance: Int = 10) = tool.executeBlocking(
        mapOf(
            "action" to "write", "key" to "constraint", "value" to value, "importance" to importance,
            ToolInternalParams.TASK_ID to "task-wm", ToolInternalParams.ITERATION to step
        )
    )

    private fun MemoryTool.executeBlocking(params: Map<String, Any>): ToolResult =
        kotlinx.coroutines.runBlocking { execute(params) }

    @Test
    fun `pinned note survives a long turn and a second turn`() {
        val wm = WorkingMemoryService(maxEntriesPerTask = 20)
        val tool = MemoryTool(wm)
        memoryWrite(tool, "use only the Kotlin 1.9 API", step = 10)
        (11..230).forEach { wm.recordEntries("task-wm", listOf(fact(it))) }

        val section = wm.buildWorkingMemorySection("task-wm", maxTokens = 8000)
        assertTrue(section.contains("use only the Kotlin 1.9 API"), "the model's own note was evicted by automatic facts")
    }

    @Test
    fun `facts from the latest steps are kept after many steps`() {
        val wm = WorkingMemoryService(maxEntriesPerTask = 20)
        (1..130).forEach { wm.recordEntries("task-wm", listOf(fact(it))) }

        val section = wm.buildWorkingMemorySection("task-wm", maxTokens = 8000)
        assertTrue(section.contains("step 130 "), "the newest fact did not make it into memory")
    }

    @Test
    fun `memory write is tagged with the task step`() {
        val wm = WorkingMemoryService(maxEntriesPerTask = 20)
        memoryWrite(MemoryTool(wm), "keep the public API unchanged", step = 137)

        assertTrue(wm.buildWorkingMemorySection("task-wm", maxTokens = 8000).contains("it#137"))
    }

    @Test
    fun `eleventh pinned note pushes out the oldest and says so`() {
        val wm = WorkingMemoryService(maxEntriesPerTask = 20)
        val tool = MemoryTool(wm)
        (1..WorkingMemoryService.MAX_PINNED_ENTRIES).forEach { memoryWrite(tool, "note $it", step = it) }

        val result = memoryWrite(tool, "note 11", step = 11)

        val section = wm.buildWorkingMemorySection("task-wm", maxTokens = 8000)
        assertFalse(section.contains("note 1 ") || section.contains("note 1\n"), "the oldest note should have been dropped")
        assertTrue(section.contains("note 11"))
        assertTrue(result.output.orEmpty().contains("note 1"), "the model was not told which note was dropped: ${result.output}")
    }

    @Test
    fun `subagent facts stay out of the parent memory`() {
        val wm = WorkingMemoryService(maxEntriesPerTask = 20)
        val integration = WorkingMemoryIntegration(wm)
        integration.recordToolKnowledge(
            taskId = "task-wm", toolName = "read_file", params = mapOf("path" to "TurnVerifier.kt"),
            result = "class TurnVerifier", iteration = 5, agentInstanceId = "sub-1"
        )

        assertFalse(wm.buildWorkingMemorySection("task-wm", maxTokens = 8000).contains("TurnVerifier"))
        val subagentScope = WorkingMemoryService.scopeKey("task-wm", "sub-1")
        assertTrue(wm.buildWorkingMemorySection(subagentScope, maxTokens = 8000).contains("TurnVerifier"))
    }

    @Test
    fun `tool executor records knowledge with the task step and the subagent scope`() = runTest {
        val integration = mockk<WorkingMemoryIntegration>(relaxed = true)
        val registry = mockk<ToolRegistry>()
        val summarizer = mockk<ToolResultSummarizer>()
        val toolExecutor = mockk<ToolExecutor>()
        val tool = mockk<Tool>(relaxed = true)
        every { tool.mode } returns ToolMode.READ_ONLY
        every { registry.getTool(any()) } returns tool
        every { registry.toSubtaskKind(any()) } returns SubtaskKind.PLAN_STEP
        coEvery { toolExecutor.executeTool(any(), any()) } returns ToolResult(success = true, output = "class TurnVerifier")
        coEvery { summarizer.summarizeToolResult(any(), any(), any(), any()) } returns
            ToolResultSummary("summary", wasSummarized = false, 0, 0, 0.0)
        val executor = TurnToolExecutor(
            toolExecutor = toolExecutor, toolRegistry = registry,
            subtaskRepository = mockk<SubtaskRepository>(relaxed = true), toolResultSummarizer = summarizer,
            workingMemoryIntegration = integration
        )

        executor.executeSingleTool(
            taskId = "task-wm",
            toolCall = ToolCallData(id = "c1", name = "read_file", arguments = "{\"path\":\"TurnVerifier.kt\"}"),
            subtaskId = "s1", listener = null, iteration = 3, _config = mockk<TurnLoopConfig>(relaxed = true),
            mode = TaskMode.AGENT, executionMode = ExecutionMode.AUTO, runId = "r1", depth = 1,
            profileOverrides = TurnProfileOverrides(agentInstanceId = "sub-1", depth = 1), _subtaskIds = emptyMap(),
            taskStep = 137
        )

        verify {
            integration.recordToolKnowledge(
                taskId = "task-wm", toolName = "read_file", params = any(), result = any(),
                iteration = 137, metadata = any(), originId = "s1", agentInstanceId = "sub-1"
            )
        }
    }
}
