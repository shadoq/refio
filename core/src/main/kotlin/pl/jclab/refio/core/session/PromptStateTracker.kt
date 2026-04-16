package pl.jclab.refio.core.session

import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.core.api.ContextSectionTokenInfo
import pl.jclab.refio.core.logging.dualLogger

class PromptStateTracker(
    private val stateManager: SessionStateManager
) {

    private val logger = dualLogger("PromptStateTracker")

    fun updatePendingContextRefs(refs: List<ContextReference>) {
        stateManager.setPendingContextRefs(refs)
        logger.debug { "Updated pending context refs: ${refs.size} items" }
    }

    fun clearPendingContextRefs() {
        stateManager.setPendingContextRefs(emptyList())
    }

    fun updatePendingUserInput(text: String) {
        stateManager.setPendingUserInput(text)
    }

    fun clearPendingUserInput() {
        stateManager.setPendingUserInput("")
    }

    fun updateContextSectionTokens(
        sections: Map<String, ContextSectionTokenInfo>,
        totalTokens: Int = 0
    ) {
        stateManager.setContextSectionTokens(sections)
        stateManager.setTotalEstimatedTokens(totalTokens)
        logger.debug { "Updated context section tokens: ${sections.size} sections, $totalTokens total tokens" }
    }
}
