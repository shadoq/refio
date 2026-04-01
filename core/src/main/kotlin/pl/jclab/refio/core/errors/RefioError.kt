package pl.jclab.refio.core.errors

sealed class RefioError(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause) {

    data class LLMTimeout(
        val provider: String,
        val model: String,
        val timeoutMs: Long,
        val originalCause: Throwable? = null
    ) : RefioError("LLM timeout after ${timeoutMs}ms: $provider/$model", originalCause)

    data class LLMAuthentication(
        val provider: String,
        val model: String? = null,
        val originalCause: Throwable? = null
    ) : RefioError("Authentication failed for $provider${model?.let { "/$it" } ?: ""}. Check your API key.", originalCause)

    data class LLMRateLimit(
        val provider: String,
        val retryAfterMs: Long? = null,
        val originalCause: Throwable? = null
    ) : RefioError(
        "Rate limited by $provider${retryAfterMs?.let { ", retry after ${it}ms" } ?: ""}",
        originalCause
    )

    data class LLMError(
        val provider: String,
        val model: String,
        val originalCause: Throwable? = null
    ) : RefioError("LLM error from $provider/$model: ${originalCause?.message ?: "unknown error"}", originalCause)

    data class ProviderNotConfigured(
        val provider: String,
        val key: String
    ) : RefioError("Provider '$provider' is not configured. Missing: $key")
}
