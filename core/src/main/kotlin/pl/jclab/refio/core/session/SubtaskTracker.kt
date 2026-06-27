package pl.jclab.refio.core.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import pl.jclab.refio.core.api.SubtaskResponse
import pl.jclab.refio.core.api.CoreApiRouter
import pl.jclab.refio.core.logging.dualLogger

/**
 * Loads and displays the subtasks a turn produced (AgentTurnLoop persists plan subtasks).
 *
 * The legacy step-by-step manipulation (approve / skip / reorder / delete / execute / prepare)
 * was removed with the old plan/step execution model; this now only mirrors DB subtask state
 * into the UI.
 */
class SubtaskTracker(
    private val projectRouter: CoreApiRouter,
    private val stateManager: SessionStateManager,
    scope: CoroutineScope,
) {

    private val logger = dualLogger("SubtaskTracker")

    // Tool lifecycle events fire loadSubtasks on every start/complete — up to dozens/sec
    // during parallel tool execution. scheduleReload() coalesces these bursts into a single
    // DB read after 300ms of quiet.
    private val reloadTrigger = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    // Direct callers (SessionLifecycle, CRUD ops) can fire loadSubtasks back-to-back from
    // different code paths around the same lifecycle moment — observed 5 sequential calls in
    // 80ms after a parallel tool batch completes. Coalesce calls that arrive within
    // COALESCE_WINDOW_MS of a prior successful load. Set to 0 to disable.
    private val lastLoadedAtMs = java.util.concurrent.atomic.AtomicLong(0L)

    init {
        @OptIn(FlowPreview::class)
        scope.launch {
            reloadTrigger
                .debounce(RELOAD_DEBOUNCE_MS)
                .collect { loadSubtasks() }
        }
    }

    fun scheduleReload() {
        reloadTrigger.tryEmit(Unit)
    }

    fun updateSubtasks(subtasks: List<SubtaskResponse>) {
        stateManager.setSubtasks(subtasks)
        logger.debug { "Updated subtasks: ${subtasks.size} items" }
    }

    suspend fun loadSubtasks() {
        val currentSession = stateManager.getActiveSession() ?: return

        // Coalesce calls that arrive within COALESCE_WINDOW_MS of the previous successful load.
        // Different code paths (lifecycle listener, MessageDispatcher PLAN_DEBUG resolver, session
        // restore) hit this method around the same moment after a tool batch — without this guard
        // we observed 5 sequential DB reads in 80ms.
        val now = System.currentTimeMillis()
        val prev = lastLoadedAtMs.get()
        if (prev != 0L && now - prev < COALESCE_WINDOW_MS) {
            logger.debug { "[SUBTASK] loadSubtasks coalesced (within ${COALESCE_WINDOW_MS}ms of last load)" }
            return
        }
        lastLoadedAtMs.set(now)

        try {
            logger.info { "[SUBTASK] loadSubtasks start: taskId=${currentSession.id}" }
            val response = projectRouter.subtaskRouter.getSubtasks(currentSession.id)
            logger.info { "[SUBTASK] loadSubtasks response: taskId=${currentSession.id}, count=${response.subtasks.size}" }

            val subtasks = response.subtasks
            val currentSubtasks = stateManager.getSubtasks()
            if (!areSubtasksEqual(currentSubtasks, subtasks)) {
                stateManager.setSubtasks(subtasks)
                logger.info { "Loaded ${subtasks.size} subtasks for session ${currentSession.id}" }
            } else {
                logger.debug { "Subtasks unchanged, skipping UI update (${subtasks.size} subtasks)" }
            }
        } catch (e: Exception) {
            logger.warn { "Failed to load subtasks for session ${currentSession.id}: ${e.message}" }
            stateManager.setSubtasks(emptyList())
        }
    }

    companion object {
        private const val RELOAD_DEBOUNCE_MS = 300L

        /**
         * Window during which back-to-back loadSubtasks() calls collapse to a no-op. Keep
         * this much smaller than RELOAD_DEBOUNCE_MS so user-initiated refreshes (clicks,
         * post-CRUD reloads) still feel instant; this only blocks duplicate calls that
         * happen within the same UI tick. Set to 0 to disable.
         */
        private const val COALESCE_WINDOW_MS = 100L
    }

    private fun areSubtasksEqual(current: List<SubtaskResponse>, new: List<SubtaskResponse>): Boolean {
        if (current.size != new.size) return false
        return current.zip(new).all { (a, b) ->
            a.id == b.id &&
                a.orderIndex == b.orderIndex &&
                a.status == b.status &&
                a.approvalStatus == b.approvalStatus &&
                a.requiresApproval == b.requiresApproval &&
                a.approvedByUser == b.approvedByUser &&
                a.description == b.description &&
                a.paramsJson == b.paramsJson &&
                a.stepPlanJson == b.stepPlanJson &&
                a.startedAt == b.startedAt &&
                a.finishedAt == b.finishedAt &&
                a.errorCode == b.errorCode &&
                a.errorMessage == b.errorMessage &&
                a.tokensIn == b.tokensIn &&
                a.tokensOut == b.tokensOut &&
                a.costUsd == b.costUsd &&
                a.model == b.model &&
                a.provider == b.provider &&
                a.resultSummary == b.resultSummary
        }
    }
}
