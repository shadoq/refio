package pl.jclab.refio.ui.listeners

import kotlinx.coroutines.CoroutineScope
import pl.jclab.refio.core.session.DefaultWorkflowStreamingListener
import pl.jclab.refio.core.session.SessionStateManager

/**
 * Plugin-facing alias for the platform-agnostic [DefaultWorkflowStreamingListener].
 * Kept only so existing UI call-sites continue to compile; subclass unchanged.
 */
class SwingWorkflowListener(
    taskId: String,
    stateManager: SessionStateManager,
    scope: CoroutineScope,
    streamingEnabled: Boolean,
) : DefaultWorkflowStreamingListener(taskId, stateManager, scope, streamingEnabled)
