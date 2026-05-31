package pl.jclab.refio.core.services

import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.services.context.ContextBudget
import pl.jclab.refio.core.services.context.ContextSection

/**
 * Owns the math behind context-budget resolution — the slice of [ConfigService]
 * responsible for translating raw config values into a [ContextBudget] snapshot
 * that the prompt builder can apply.
 *
 * Kept as a small helper so the logic around per-section overrides,
 * compact-prompt auto-detection, and provider-specific context sizes lives in
 * one place instead of being scattered across ConfigService.
 */
class ContextBudgetResolver(private val configService: ConfigService) {

    /**
     * Resolve context budget for prompt building.
     *
     * @param staticPrefixTokens tokens already committed to the system prompt (system markdown
     *   + tool descriptions + response contract). When > 0, these are deducted from the available
     *   input budget so dynamic sections fit alongside the static prefix inside the model's
     *   context window. Default 0 keeps callers that don't yet know the prefix size working
     *   as before.
     */
    fun getContextBudget(
        taskId: String? = null,
        operation: ModelOperation? = null,
        staticPrefixTokens: Int = 0,
    ): ContextBudget {
        val inputRatio = getInputRatio(taskId)
        val contextSize = resolveContextSize(operation, taskId)
        val totalOverride = getTotalTokensOverride(taskId)
        val overrides = getSectionOverrides(taskId)

        return ContextBudget.forContextSize(
            contextSize = contextSize,
            inputRatio = inputRatio,
            overrides = overrides,
            totalTokensOverride = totalOverride,
            staticPrefixTokens = staticPrefixTokens,
        )
    }

    /**
     * Whether compact (shorter) system prompts should be used.
     * Auto-detects based on resolved context size for the operation:
     * context <= 48000 tokens → compact mode (saves ~40% prompt tokens).
     */
    fun isCompactPrompts(operation: ModelOperation? = null, taskId: String? = null): Boolean {
        val contextSize = resolveContextSize(operation, taskId)
        return contextSize <= ConfigService.COMPACT_PROMPT_THRESHOLD
    }

    private fun getInputRatio(taskId: String?): Double {
        val raw = configService.get(ConfigKeys.CONTEXT_BUDGET_INPUT_RATIO.key, taskId = taskId)
        return raw?.toDoubleOrNull() ?: ConfigKeys.CONTEXT_BUDGET_INPUT_RATIO.default
    }

    private fun getTotalTokensOverride(taskId: String?): Int? {
        val raw = configService.get(ConfigKeys.CONTEXT_BUDGET_TOTAL_TOKENS.key, taskId = taskId)
        return raw?.toIntOrNull()
    }

    private fun getSectionOverrides(taskId: String?): Map<ContextSection, Int> {
        val overrides = mutableMapOf<ContextSection, Int>()
        ContextSection.values().forEach { section ->
            val key = "${ConfigService.KEY_CONTEXT_BUDGET_SECTION_PREFIX}${section.name.lowercase()}"
            val value = configService.get(key, taskId = taskId)?.toIntOrNull()
            if (value != null && value > 0) {
                overrides[section] = value
            }
        }
        return overrides
    }

    private fun resolveContextSize(operation: ModelOperation?, taskId: String?): Int {
        if (operation == null) {
            return configService.getTyped(ConfigKeys.MAX_CONTEXT_SIZE, taskId)
        }
        val (model, provider) = configService.getModel(operation, taskId)
        return pl.jclab.refio.core.llm.ModelWindow.resolve(provider, model, configService, taskId)
    }
}
