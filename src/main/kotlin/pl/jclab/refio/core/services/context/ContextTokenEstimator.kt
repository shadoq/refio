package pl.jclab.refio.core.services.context

import kotlin.math.max

object ContextTokenEstimator {
    fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        return max(1, text.length / 4)
    }

    fun truncateToTokens(text: String, maxTokens: Int): String {
        if (maxTokens <= 0 || text.isBlank()) return ""
        val maxChars = maxTokens * 4
        if (text.length <= maxChars) return text

        val suffix = "\n... (truncated ${text.length - maxChars} more chars)"
        val contentLimit = (maxChars - suffix.length).coerceAtLeast(0)
        val truncated = text.take(contentLimit)
        return truncated + suffix
    }
}
