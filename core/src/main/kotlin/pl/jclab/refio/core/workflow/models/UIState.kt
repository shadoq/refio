package pl.jclab.refio.core.workflow.models

import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.api.models.ExecutionMode
import pl.jclab.refio.api.models.TaskMode

/**
 * UI state snapshot for a workflow turn.
 */
data class UIState(
    val taskId: String?,
    val mode: TaskMode,
    val executionMode: ExecutionMode,
    val input: String,
    val contextRefs: List<ContextReference> = emptyList(),
    val model: String? = null,
    val provider: String? = null,
    val streamingEnabled: Boolean = true,
    val thinkingEnabled: Boolean = false,
    val noEgressEnabled: Boolean = false
)
