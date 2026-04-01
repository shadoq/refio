package pl.jclab.refio.core.workflow.models

/**
 * Request wrapper for a single workflow turn.
 */
data class WorkflowRequest(
    val uiState: UIState,
    /**
     * Optional project analysis context for LLM-based intent classification.
     * Only needed when intent classification feature is enabled.
     */
    val projectAnalysis: String? = null
)
