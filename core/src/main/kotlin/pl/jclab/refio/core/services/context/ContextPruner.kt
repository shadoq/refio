package pl.jclab.refio.core.services.context

import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.models.context.ProjectContextDTO
import pl.jclab.refio.core.services.ConfigService

private val logger = dualLogger("ContextPruner")

// Context budget limits
const val SMALL_CONTEXT_OVERFLOW_THRESHOLD_TOKENS = 12_000
const val SMALL_CONTEXT_OVERFLOW_RATIO = 0.75

/**
 * Handles context budget resolution and section pruning/truncation.
 *
 * Extracted from ContextService to isolate budget allocation and truncation logic.
 */
class ContextPruner(
    private val configService: ConfigService
) {
    /**
     * Resolve the context budget for a given project context and optional model operation.
     * If modelOperation is null, it is inferred from the execution metadata in the context.
     */
    fun resolveContextBudget(
        context: ProjectContextDTO,
        modelOperation: ModelOperation?,
        staticPrefixTokens: Int = 0,
    ): ContextBudget {
        val taskId = context.currentTask?.id
        val resolvedOperation = modelOperation ?: resolveModelOperationFromContext(context)
        return configService.getContextBudget(taskId, resolvedOperation, staticPrefixTokens)
    }

    /**
     * Truncate a section's content to fit within the given token budget.
     *
     * If the content is wrapped in XML-like tags (e.g. `<SECTION>...</SECTION>`),
     * the wrapper is preserved and only the inner content is truncated.
     */
    fun truncateSectionToBudget(content: String, maxTokens: Int, modelId: String? = null): String {
        if (maxTokens <= 0 || content.isBlank()) return ""

        val wrappedSectionRegex = Regex("""^\s*<([A-Z_]+)>\s*([\s\S]*?)\s*</\1>\s*$""")
        val match = wrappedSectionRegex.matchEntire(content)
        if (match == null) {
            return ContextTokenEstimator.truncateToTokens(content, maxTokens, modelId)
        }

        val tag = match.groupValues[1]
        val innerContent = match.groupValues[2].trim()
        val wrapper = "<$tag>\n\n</$tag>"
        val wrapperTokens = ContextTokenEstimator.estimateTokens(wrapper, modelId)
        val innerBudget = (maxTokens - wrapperTokens).coerceAtLeast(1)
        val truncatedInner = ContextTokenEstimator.truncateToTokens(innerContent, innerBudget, modelId).trim()
        return "<$tag>\n$truncatedInner\n</$tag>"
    }

    /**
     * Infer ModelOperation from the execution metadata's agent mode.
     */
    private fun resolveModelOperationFromContext(context: ProjectContextDTO): ModelOperation? {
        val mode = context.executionMetadata.agentMode ?: return null
        return runCatching { ModelOperation.fromTaskMode(TaskMode.valueOf(mode)) }.getOrNull()
    }
}
