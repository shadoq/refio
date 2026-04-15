package pl.jclab.refio.services.execution

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import pl.jclab.refio.api.models.ExecutionMode
import pl.jclab.refio.core.session.ExecutionStateController
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.logging.dualLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Service for managing step execution lifecycle.
 *
 * REFACTORED: Removed polling mechanism to avoid race conditions with UI listener.
 * UI updates are now handled exclusively by ExecutionEventListener in SessionManager.
 *
 * This service now only:
 * - Manages execution state (_isExecuting)
 * - Handles execution completion/cancellation
 */
@Service(Service.Level.PROJECT)
class StepExecutionService(private val project: Project) : ExecutionStateController {

    private val logger = dualLogger("StepExecutionService")

    // Use proper coroutine scope for Project-level service
    private val cs = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _isExecuting = MutableStateFlow(false)
    val isExecuting: StateFlow<Boolean> = _isExecuting.asStateFlow()

    private val _currentStep = MutableStateFlow(0)
    val currentStep: StateFlow<Int> = _currentStep.asStateFlow()

    private val _totalSteps = MutableStateFlow(0)
    val totalSteps: StateFlow<Int> = _totalSteps.asStateFlow()

    private var executionJob: Job? = null

    /**
     * Start interactive execution - no polling, UI updates via listener.
     */
    override fun startInteractiveExecution(taskId: String) {
        logger.info { "Starting INTERACTIVE mode for task: $taskId" }
        _isExecuting.value = true
        // UI updates handled by ExecutionEventListener in SessionManager
    }

    /**
     * Stop execution
     */
    override fun stopExecution() {
        logger.info { "Stopping execution" }
        _isExecuting.value = false
        executionJob?.cancel()
        executionJob = null
    }

    /**
     * Update progress state (called by UI listener via SessionManager).
     */
    fun updateProgress(current: Int, total: Int) {
        _currentStep.value = current
        _totalSteps.value = total
        logger.debug { "[EXECUTION] Progress updated: current=$current, total=$total" }
    }

    /**
     * Mark execution as complete (called by UI listener).
     */
    override fun markComplete() {
        _isExecuting.value = false
        GlobalMetrics.clearCurrentOperation()
        logger.info { "[EXECUTION] Marked execution complete" }
    }

    /**
     * Switch execution mode during active execution.
     *
     * Mode switching is only allowed BEFORE execution starts.
     */
    @Suppress("UNUSED_PARAMETER")
    fun switchExecutionMode(_taskId: String, newMode: ExecutionMode) {
        logger.info { "Switching execution mode to: $newMode" }

        if (_isExecuting.value) {
            logger.warn { "Cannot switch mode during active execution" }
            logger.warn { "Stop execution first, then start with new mode" }
            return
        }

        logger.info { "Mode will be applied when execution starts" }
    }

    companion object {
        fun getInstance(project: Project): StepExecutionService {
            return project.getService(StepExecutionService::class.java)
        }
    }
}
