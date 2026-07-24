package pl.jclab.refio.core.errors

/**
 * Shared decision on whether an LLM failure is transient (worth a bounded retry) or terminal.
 *
 * Single source of truth so every retry site agrees: [pl.jclab.refio.core.services.LLMRetryHandler]
 * (the turn-loop retry stack) and tools that call the LLM directly without that stack
 * (e.g. AdvanceCodeEditingTool's editor call).
 */
object TransientErrorClassifier {

    // Transient upstream statuses safe to retry: standard 5xx, Anthropic 529 (overloaded),
    // and Cloudflare edge errors 520-524. 501/505 are excluded (a retry won't change them).
    private val TRANSIENT_HTTP_STATUSES = setOf(500, 502, 503, 504, 520, 521, 522, 523, 524, 529)

    // A 3-digit code right after an http/code/status marker (allowing a short separator like
    // ": ", " ", or "="). Anchoring to the marker avoids matching stray 3-digit numbers.
    private val HTTP_STATUS_REGEX =
        Regex("""(?:http|code|status)\D{0,3}(\d{3})""", RegexOption.IGNORE_CASE)

    /**
     * True if the message carries a transient upstream HTTP status. Matches
     * "Anthropic API error (HTTP 500)", "error code: 520", "status=503" while ignoring
     * unrelated 3-digit numbers elsewhere in the message.
     */
    fun hasTransientHttpStatus(message: String): Boolean =
        HTTP_STATUS_REGEX.findAll(message)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .any { it in TRANSIENT_HTTP_STATUSES }

    /**
     * Full transient check for callers without their own retry stack. Covers rate limits,
     * timeouts, gateway/overload prose, mid-flight stream cut-offs, and bare transient HTTP
     * status codes. Cancellation and authentication are NOT handled here — the caller must
     * reject those before consulting this classifier.
     */
    fun isTransient(throwable: Throwable): Boolean {
        val m = throwable.message?.lowercase() ?: return false
        return when {
            m.contains("rate limit") || m.contains("too many requests") -> true
            m.contains("timeout") || m.contains("timed out") -> true
            m.contains("service unavailable") || m.contains("bad gateway") -> true
            m.contains("overloaded") || m.contains("server is busy") -> true
            m.contains("connection refused") || m.contains("connection reset") -> true
            m.contains("stream ended before") || m.contains("unexpected end of stream") -> true
            else -> hasTransientHttpStatus(m)
        }
    }
}
