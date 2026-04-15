package pl.jclab.refio.core.session

/**
 * Port for managing step execution lifecycle state.
 *
 * IntelliJ plugin implements this via `StepExecutionService` (project-level IDE service).
 * CLI can provide a simple stub implementation when running without execution state UI.
 */
interface ExecutionStateController {
    fun startInteractiveExecution(taskId: String)
    fun stopExecution()
    fun markComplete()
}
