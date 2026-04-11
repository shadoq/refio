package pl.jclab.refio.core.services.turn

import pl.jclab.refio.core.api.ContextSectionTokenInfo
import pl.jclab.refio.core.services.context.ContextSection
import pl.jclab.refio.core.services.context.ContextPriority
import java.time.Instant

/**
 * Record of a single context section that was considered for inclusion.
 */
/**
 * Why a context section was excluded or truncated.
 */
enum class DropReason {
    EMPTY_CONTENT,
    BUDGET_EXCEEDED,
    TRUNCATED
}

data class ContextSectionRecord(
    val section: ContextSection,
    val priority: ContextPriority,
    val included: Boolean,
    val estimatedTokens: Int,
    val actualTokens: Int? = null,
    val dropReason: DropReason? = null,
    val source: String? = null
)

/**
 * Trace of all context decisions made during prompt building.
 * Explains what entered the prompt, what was dropped, and why.
 */
data class ContextDecisionTrace(
    val sections: List<ContextSectionRecord>,
    val totalBudget: Int,
    val totalUsed: Int
) {
    val includedSections: List<ContextSectionRecord> by lazy { sections.filter { it.included } }
    val droppedSections: List<ContextSectionRecord> by lazy { sections.filter { !it.included } }
    val droppedCount: Int get() = droppedSections.size
}

/**
 * Complete snapshot of what was sent to the LLM.
 * Captured before each model call for debugging and inspection.
 */
data class PromptSnapshot(
    val taskId: String,
    val iteration: Int,
    val timestamp: Instant = Instant.now(),
    val systemPromptTokens: Int,
    val messagesTokens: Int,
    val totalTokens: Int,
    val toolCount: Int,
    val toolNames: List<String>,
    val contextTrace: ContextDecisionTrace,
    val systemPromptPreview: String? = null,
    /**
     * Granular section token breakdown for UI visualization.
     * Keys match ContextSectionColorPalette (e.g. "system_prompt", "recent_work", "key_components").
     * Includes system prompt + context sections + messages — same breakdown as manual refresh.
     */
    val sectionTokens: Map<String, ContextSectionTokenInfo> = emptyMap()
)

/**
 * Result of context building — prompt text plus decision trace.
 */
data class ContextBuildResult(
    val prompt: String,
    val trace: ContextDecisionTrace
)
