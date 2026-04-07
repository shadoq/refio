package pl.jclab.refio.core.tools.base

/**
 * Constants for internal metadata parameters injected into tool argument maps.
 * Used by TurnToolExecutor (injection) and SYSTEM tools (reading).
 */
object ToolInternalParams {
    const val TASK_ID = "_task_id"
    const val MODE = "_mode"
    const val ITERATION = "_iteration"
    const val SESSION_ID = "_session_id"
    const val AGENT_NAME = "_agent_name"
    const val AGENT_ID = "_agent_id"
    const val PARENT_RUN_ID = "_parent_run_id"
    const val SUBTASK_ID = "_subtask_id"
}
