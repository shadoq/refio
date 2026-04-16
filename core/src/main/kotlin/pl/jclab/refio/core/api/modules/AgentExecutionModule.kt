package pl.jclab.refio.core.api.modules

import pl.jclab.refio.api.models.ExecutionMode
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.prompts.ToolDescriptionBuilder
import pl.jclab.refio.core.services.AgentExecutor
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.ContextService
import pl.jclab.refio.core.services.PromptsService
import pl.jclab.refio.core.services.SnapshotService
import pl.jclab.refio.core.services.StepPlanner
import pl.jclab.refio.core.services.StepSummarizer
import pl.jclab.refio.core.services.ToolExecutor
import pl.jclab.refio.core.services.turn.ToolApprovalService
import pl.jclab.refio.core.services.ToolPermissionsService
import pl.jclab.refio.core.tools.base.ToolRegistry
import java.nio.file.Path

/**
 * Construction of the step execution stack — [StepPlanner], [ToolExecutor],
 * [AgentExecutor], [StepSummarizer] — used by AgentRouter for PLAN/AGENT mode.
 *
 * All three downstream components are optional: they are null when the router
 * has no `toolRegistry` (app-level router, no project selected).
 */
class AgentExecutionModule(
    private val persistence: PersistenceModule,
    private val llmClient: LLMClient,
    private val configService: ConfigService,
    private val promptsService: PromptsService,
    private val toolDescriptionBuilder: ToolDescriptionBuilder,
    private val toolPermissionsService: ToolPermissionsService,
    private val toolApprovalService: ToolApprovalService,
    private val contextService: ContextService?,
    private val snapshotService: SnapshotService?,
    private val projectRoot: Path?,
    private val toolRegistry: ToolRegistry?
) {
    val stepPlanner: StepPlanner? = if (toolRegistry != null) {
        StepPlanner(
            persistence.taskRepository,
            persistence.subtaskRepository,
            toolRegistry,
            llmClient,
            promptsService,
            toolDescriptionBuilder,
            configService,
            toolPermissionsService,
            contextService,
            projectRoot
        )
    } else null

    val stepSummarizer: StepSummarizer = StepSummarizer(
        llmClient = llmClient,
        promptsService = promptsService,
        configService = configService,
        taskRepository = persistence.taskRepository
    )

    val toolExecutor: ToolExecutor? = if (toolRegistry != null) {
        ToolExecutor(
            toolRegistry = toolRegistry,
            taskRepository = persistence.taskRepository,
            subtaskRepository = persistence.subtaskRepository,
            snapshotService = snapshotService,
            toolPermissionsService = toolPermissionsService,
            mode = TaskMode.AGENT,
            executionMode = ExecutionMode.AUTO
        )
    } else null

    val agentExecutor: AgentExecutor? = if (toolExecutor != null && stepPlanner != null) {
        AgentExecutor(
            taskRepository = persistence.taskRepository,
            subtaskRepository = persistence.subtaskRepository,
            toolExecutor = toolExecutor,
            llmClient = llmClient,
            promptsService = promptsService,
            configService = configService,
            stepPlanner = stepPlanner
        )
    } else null
}
