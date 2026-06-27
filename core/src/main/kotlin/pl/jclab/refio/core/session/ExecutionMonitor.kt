package pl.jclab.refio.core.session

import kotlinx.coroutines.Job
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.services.monitoring.OperationInfo
import pl.jclab.refio.core.logging.dualLogger

/**
 * Cancellation / pause controls for a running turn.
 *
 * The legacy step-by-step execution (executeCurrentStep, per-step approval messages, the
 * ExecutionEventListener UI progress listener) was removed together with the old plan/step
 * model. Only the live-turn cancel/pause controls remain, which the UI cancel button uses.
 */
class ExecutionMonitor(
    private val stateManager: SessionStateManager,
    private val stepExecutionService: ExecutionStateController,
) {

    private val logger = dualLogger("ExecutionMonitor")
    private var streamingJob: Job? = null

    fun cancelStreaming() {
        logger.info { "Cancelling streaming..." }
        streamingJob?.cancel()
        streamingJob = null
    }

    fun cancelExecution() {
        logger.info { "Cancelling execution..." }
        stepExecutionService.stopExecution()
        GlobalMetrics.setCurrentOperation(OperationInfo.Idle)
    }

    fun setPaused(paused: Boolean) {
        logger.info { "Setting paused state to: $paused" }
        stateManager.setPaused(paused)
    }
}
