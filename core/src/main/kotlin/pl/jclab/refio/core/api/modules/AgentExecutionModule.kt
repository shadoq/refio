package pl.jclab.refio.core.api.modules

import pl.jclab.refio.api.models.ExecutionMode
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.services.SnapshotService
import pl.jclab.refio.core.services.ToolExecutor
import pl.jclab.refio.core.services.ToolPermissionsService
import pl.jclab.refio.core.tools.base.ToolRegistry

/**
 * Builds the [ToolExecutor] shared by AgentTurnLoop (the only PLAN/AGENT execution path).
 *
 * Null when the router has no `toolRegistry` (app-level router, no project selected).
 * The legacy step-execution stack (StepPlanner / AgentExecutor / StepSummarizer) was removed.
 */
class AgentExecutionModule(
    private val persistence: PersistenceModule,
    private val toolPermissionsService: ToolPermissionsService,
    private val snapshotService: SnapshotService?,
    private val toolRegistry: ToolRegistry?
) {
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
}
