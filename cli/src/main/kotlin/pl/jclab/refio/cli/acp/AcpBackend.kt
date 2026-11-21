package pl.jclab.refio.cli.acp

import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.cli.StandaloneCoreBootstrap
import pl.jclab.refio.cli.headlessStatus
import pl.jclab.refio.core.api.CoreApiRouter
import pl.jclab.refio.core.api.CreateTaskRequest
import pl.jclab.refio.core.api.TurnRequest
import pl.jclab.refio.core.context.mcp.MCPServerConfig
import pl.jclab.refio.core.db.ExecutionMode
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.TaskStatus
import pl.jclab.refio.core.models.api.ChatRequest
import pl.jclab.refio.core.models.api.LLMParams
import pl.jclab.refio.core.services.TurnResult
import pl.jclab.refio.core.services.turn.ToolApprovalService
import pl.jclab.refio.core.services.turn.TurnEventListener
import java.nio.file.Path

/**
 * What the ACP layer needs from Refio's core. A seam so the protocol flow can be tested with a
 * scripted turn instead of a real model.
 */
interface AcpBackend {
    /** Opens the project once per process. Returns false when one is already open at another path. */
    suspend fun open(cwd: Path, mcpServers: List<MCPServerConfig>): Boolean

    val approvals: ToolApprovalService

    fun createTask(mode: TaskMode): String

    suspend fun runTurn(
        taskId: String,
        mode: TaskMode,
        text: String,
        contextRefs: List<ContextReference>,
        listener: TurnEventListener,
    ): TurnResult

    fun markFinished(taskId: String, result: TurnResult?)

    fun close()
}

/**
 * The real backend: the same core bootstrap the headless CLI uses. One project per process,
 * because the bootstrap installs process-wide state (context providers, MCP manager); an IDE runs
 * one agent process per project window anyway.
 */
class StandaloneAcpBackend(
    private val runConfigOverrides: Map<String, String>,
    private val model: String?,
    private val provider: String?,
    /** Servers from `--mcp-server`, connected next to the ones the IDE hands over. */
    private val extraMcpServers: List<MCPServerConfig> = emptyList(),
) : AcpBackend {
    private var bootstrap: StandaloneCoreBootstrap? = null
    private var openPath: Path? = null
    private val router: CoreApiRouter
        get() = bootstrap?.router ?: throw IllegalStateException("No session opened yet")

    override val approvals: ToolApprovalService get() = router.toolApprovalService

    @Synchronized
    private fun openSync(cwd: Path, mcpServers: List<MCPServerConfig>): Boolean {
        openPath?.let { return it == cwd }
        val boot = StandaloneCoreBootstrap(cwd, runConfigOverrides, extraMcpServers + mcpServers.filter { s -> extraMcpServers.none { it.id == s.id } })
        boot.initialize()
        // The IDE has its own stop button and a person who may step away; an expiring request
        // would turn into a silent rejection.
        boot.router.toolApprovalService.approvalTimeoutMs = 0
        bootstrap = boot
        openPath = cwd
        return true
    }

    override suspend fun open(cwd: Path, mcpServers: List<MCPServerConfig>): Boolean {
        val firstOpen = bootstrap == null
        val ok = openSync(cwd, mcpServers)
        if (ok && firstOpen) bootstrap?.awaitMcpReady()
        return ok
    }

    override fun createTask(mode: TaskMode): String =
        router.taskRouter.createTask(CreateTaskRequest(name = "acp-${System.currentTimeMillis()}", mode = mode)).id

    override suspend fun runTurn(
        taskId: String,
        mode: TaskMode,
        text: String,
        contextRefs: List<ContextReference>,
        listener: TurnEventListener,
    ): TurnResult {
        if (mode == TaskMode.CHAT) {
            val response = router.chatRouter.chat(
                ChatRequest(
                    taskId = taskId,
                    mode = mode,
                    input = text,
                    contextRefs = contextRefs,
                    params = LLMParams(model = model, provider = provider)
                ),
                stream = true,
                onChunk = {}
            )
            return TurnResult(success = true, response = response.output, iterations = 1, tokensIn = 0, tokensOut = 0, cost = 0.0)
        }
        return router.agentRouter.runTurn(
            TurnRequest(
                taskId = taskId,
                userInput = text,
                mode = mode,
                executionMode = ExecutionMode.AUTO,
                model = model,
                provider = provider,
                userContextRefs = contextRefs
            ),
            // Streaming keeps slow local models from tripping the socket idle timeout; the text
            // itself reaches the IDE once, cleaned, when the turn ends.
            streamCallback = {},
            listener = listener
        )
    }

    override fun markFinished(taskId: String, result: TurnResult?) {
        val status = if (result == null) TaskStatus.CANCELED else headlessStatus(result, null)
        runCatching { router.taskRepository.update(id = taskId, status = status) }
    }

    override fun close() {
        bootstrap?.shutdown()
    }
}
