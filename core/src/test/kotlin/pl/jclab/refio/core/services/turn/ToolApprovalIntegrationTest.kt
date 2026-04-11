package pl.jclab.refio.core.services.turn

import io.mockk.*
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.db.ExecutionMode
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.SubtaskKind
import pl.jclab.refio.core.db.TaskStatus
import pl.jclab.refio.core.db.ToolCallData
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.services.PermissionLevel
import pl.jclab.refio.core.services.ToolExecutor
import pl.jclab.refio.core.services.ToolPermissionsService
import pl.jclab.refio.core.services.ToolResultSummarizer
import pl.jclab.refio.core.services.ToolResultSummary
import pl.jclab.refio.core.tools.base.ToolRegistry
import pl.jclab.refio.core.tools.base.ToolResult
import pl.jclab.refio.core.services.ToolCall as CoreToolCall
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ToolApprovalIntegrationTest {

    private val toolExecutor = mockk<ToolExecutor>()
    private val toolRegistry = mockk<ToolRegistry>()
    private val subtaskRepository = mockk<SubtaskRepository>(relaxed = true)
    private val toolResultSummarizer = mockk<ToolResultSummarizer>()
    private val permissionsService = mockk<ToolPermissionsService>()
    private val approvalService = ToolApprovalService(approvalTimeoutMs = 0) // no timeout for tests

    private lateinit var executor: TurnToolExecutor

    @BeforeEach
    fun setup() {
        executor = TurnToolExecutor(
            toolExecutor = toolExecutor,
            toolRegistry = toolRegistry,
            subtaskRepository = subtaskRepository,
            toolResultSummarizer = toolResultSummarizer,
            approvalService = approvalService,
            permissionsService = permissionsService
        )

        every { toolRegistry.getTool(any()) } returns null
        every { toolRegistry.toSubtaskKind(any()) } returns SubtaskKind.PLAN_STEP
        every { subtaskRepository.create(any(), any(), any(), any(), any(), any()) } returns mockk(relaxed = true)
        every { subtaskRepository.getMaxOrderIndex(any()) } returns 0
        every { subtaskRepository.findById(any()) } returns null
        coEvery { toolResultSummarizer.summarizeToolResult(any(), any(), any(), any()) } returns
            ToolResultSummary("summary", wasSummarized = false, 0, 0, 0.0)
    }

    private fun toolCall(name: String = "run_terminal_command", args: String = """{"command":"git status"}""") =
        ToolCallData(id = "call_1", name = name, arguments = args)

    @Nested
    inner class ApprovalFlowTests {

        @Test
        fun `tool with ON permission executes without approval`() = runTest {
            every { permissionsService.getPermission("read_file", any()) } returns PermissionLevel.ON
            coEvery { toolExecutor.executeTool(any(), any()) } returns ToolResult(success = true, output = "ok")

            val call = toolCall(name = "read_file", args = """{"path":"test.txt"}""")
            val result = executor.executeSingleTool(
                taskId = "t1", toolCall = call, subtaskId = "s1",
                listener = null, iteration = 1, _config = mockk(relaxed = true),
                mode = TaskMode.AGENT, executionMode = ExecutionMode.AUTO,
                runId = "r1", depth = 0, profileOverrides = null, _subtaskIds = emptyMap()
            )

            assertEquals("ok", result.content)
            // No approval was requested
            assertTrue(approvalService.pendingRequests.value.isEmpty())
        }

        @Test
        fun `tool with ASK permission waits for approval then executes`() = runTest {
            every { permissionsService.getPermission("run_terminal_command", any()) } returns PermissionLevel.ASK
            coEvery { toolExecutor.executeTool(any(), any()) } returns ToolResult(success = true, output = "done")

            val call = toolCall()

            // Launch execution in background
            val resultDeferred = async {
                executor.executeSingleTool(
                    taskId = "t1", toolCall = call, subtaskId = "s1",
                    listener = null, iteration = 1, _config = mockk(relaxed = true),
                    mode = TaskMode.AGENT, executionMode = ExecutionMode.AUTO,
                    runId = "r1", depth = 0, profileOverrides = null, _subtaskIds = emptyMap()
                )
            }

            delay(50)

            // Approval should be pending
            assertEquals(1, approvalService.pendingRequests.value.size)

            // Approve it
            val req = approvalService.pendingRequests.value.first()
            approvalService.resolveApproval(req.requestId, ToolApprovalService.ApprovalDecision.Approved)

            val result = resultDeferred.await()
            assertEquals("done", result.content)
        }

        @Test
        fun `tool with ASK permission throws on rejection`() = runTest {
            every { permissionsService.getPermission("run_terminal_command", any()) } returns PermissionLevel.ASK

            val call = toolCall()

            val resultDeferred = async {
                runCatching {
                    executor.executeSingleTool(
                        taskId = "t1", toolCall = call, subtaskId = "s1",
                        listener = null, iteration = 1, _config = mockk(relaxed = true),
                        mode = TaskMode.AGENT, executionMode = ExecutionMode.AUTO,
                        runId = "r1", depth = 0, profileOverrides = null, _subtaskIds = emptyMap()
                    )
                }
            }

            delay(50)

            // Reject it
            val req = approvalService.pendingRequests.value.first()
            approvalService.resolveApproval(req.requestId, ToolApprovalService.ApprovalDecision.Rejected("too risky"))

            val result = resultDeferred.await()
            assertTrue(result.isFailure)
            assertIs<ToolRejectedException>(result.exceptionOrNull())
            assertEquals("too risky", (result.exceptionOrNull() as ToolRejectedException).reason)
        }

        @Test
        fun `trusted tool auto-approves on second call`() = runTest {
            every { permissionsService.getPermission("run_terminal_command", any()) } returns PermissionLevel.ASK
            coEvery { toolExecutor.executeTool(any(), any()) } returns ToolResult(success = true, output = "ok")

            // First call: manual approval with Trust
            val call1 = toolCall()
            val deferred1 = async {
                executor.executeSingleTool(
                    taskId = "t1", toolCall = call1, subtaskId = "s1",
                    listener = null, iteration = 1, _config = mockk(relaxed = true),
                    mode = TaskMode.AGENT, executionMode = ExecutionMode.AUTO,
                    runId = "r1", depth = 0, profileOverrides = null, _subtaskIds = emptyMap()
                )
            }
            delay(50)

            val req1 = approvalService.pendingRequests.value.first()
            approvalService.resolveApproval(
                req1.requestId,
                ToolApprovalService.ApprovalDecision.Trusted("run_terminal_command")
            )
            deferred1.await()

            // Second call: should auto-approve (no pending request)
            val call2 = toolCall(args = """{"command":"git diff"}""")
            val result2 = executor.executeSingleTool(
                taskId = "t1", toolCall = call2.copy(id = "call_2"), subtaskId = "s2",
                listener = null, iteration = 2, _config = mockk(relaxed = true),
                mode = TaskMode.AGENT, executionMode = ExecutionMode.AUTO,
                runId = "r1", depth = 0, profileOverrides = null, _subtaskIds = emptyMap()
            )

            assertEquals("ok", result2.content)
            // No pending approval for second call
            assertTrue(approvalService.pendingRequests.value.isEmpty())
        }
    }

    @Nested
    inner class TimeoutTests {
        @Test
        fun `approval timeout results in rejection`() = runTest {
            val timedService = ToolApprovalService(approvalTimeoutMs = 100) // 100ms timeout
            val timedExecutor = TurnToolExecutor(
                toolExecutor = toolExecutor,
                toolRegistry = toolRegistry,
                subtaskRepository = subtaskRepository,
                toolResultSummarizer = toolResultSummarizer,
                approvalService = timedService,
                permissionsService = permissionsService
            )

            every { permissionsService.getPermission("run_terminal_command", any()) } returns PermissionLevel.ASK

            val call = toolCall()
            val result = runCatching {
                timedExecutor.executeSingleTool(
                    taskId = "t1", toolCall = call, subtaskId = "s1",
                    listener = null, iteration = 1, _config = mockk(relaxed = true),
                    mode = TaskMode.AGENT, executionMode = ExecutionMode.AUTO,
                    runId = "r1", depth = 0, profileOverrides = null, _subtaskIds = emptyMap()
                )
            }

            assertTrue(result.isFailure)
            assertIs<ToolRejectedException>(result.exceptionOrNull())
            assertTrue((result.exceptionOrNull() as ToolRejectedException).reason!!.contains("timeout"))
        }
    }
}
