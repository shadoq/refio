package pl.jclab.refio.core.services.context

import pl.jclab.refio.core.services.PromptTokenEstimator

/**
 * Lightweight token estimator used by ContextService and friends during section budget math.
 *
 * Delegates to [PromptTokenEstimator.estimateBase] so all three estimators in :core share
 * the same chars/token ratio ([PromptTokenEstimator.CHARS_PER_TOKEN_BASE]). This used to be
 * a 4 chars/token implementation, which diverged ~14% from the 3.5 ratio in
 * [PromptTokenEstimator] and caused compaction to fire at different thresholds than
 * section truncation.
 */
object ContextTokenEstimator {
    fun estimateTokens(text: String): Int = PromptTokenEstimator.estimateBase(text)

    fun truncateToTokens(text: String, maxTokens: Int): String {
        if (maxTokens <= 0 || text.isBlank()) return ""
        val maxChars = PromptTokenEstimator.maxCharsForTokens(maxTokens)
        if (text.length <= maxChars) return text

        val suffix = "\n... (truncated ${text.length - maxChars} more chars)"
        val contentLimit = (maxChars - suffix.length).coerceAtLeast(0)
        val truncated = text.take(contentLimit)
        return truncated + suffix
    }
}
