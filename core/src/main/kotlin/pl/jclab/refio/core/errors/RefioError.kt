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

    data class LLMConnectionFailed(
        val provider: String,
        val endpoint: String? = null,
        val originalCause: Throwable? = null
    ) : RefioError(
        "LLM server is not responding${endpoint?.let { " at $it" } ?: ""} ($provider). " +
            if (provider == "ollama") {
                "Is Ollama running? Start it with: ollama serve"
            } else {
                "Check that the server is running and the endpoint is correct."
            },
        originalCause
    )

    data class LLMModelNotFound(
        val provider: String,
        val model: String,
        val originalCause: Throwable? = null
    ) : RefioError(
        "Model '$model' was not found on $provider." +
            if (provider == "ollama") " Pull it with: ollama pull $model" else "",
        originalCause
    )

    data class LLMContextOverflow(
        val provider: String,
        val model: String,
        val originalCause: Throwable? = null
    ) : RefioError(
        "Context is too large for $provider/$model. " +
            "Reduce the context (fewer files, shorter history) or increase the model's context window.",
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

    /**
     * Thrown when provider returns a response that doesn't match expected structure.
     *
     * `bodyPreview` is the first ~500 chars of raw JSON body (for debugging).
     */
    class MalformedResponse(
        val provider: String,
        val model: String,
        val reason: String,
        val bodyPreview: String,
        cause: Throwable? = null,
    ) : RefioError(
        "Malformed response from $provider/$model: $reason. Preview: ${bodyPreview.take(500)}",
        cause,
    )
}
