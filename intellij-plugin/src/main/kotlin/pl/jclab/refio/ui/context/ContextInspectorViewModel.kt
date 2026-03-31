package pl.jclab.refio.ui.context

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pl.jclab.refio.core.services.turn.PromptSnapshot
import pl.jclab.refio.services.session.SessionManager

/**
 * ViewModel for the Context Inspector panel.
 * Observes PromptSnapshot from SessionManager and provides it to the UI.
 */
class ContextInspectorViewModel(
    private val sessionManager: SessionManager,
    private val scope: CoroutineScope
) {
    private val _snapshot = MutableStateFlow<PromptSnapshot?>(null)
    val snapshot: StateFlow<PromptSnapshot?> = _snapshot.asStateFlow()

    fun start() {
        val snapshotFlow = sessionManager.lastPromptSnapshot ?: return
        scope.launch {
            snapshotFlow.collect { _snapshot.value = it }
        }
    }
}
