package pl.jclab.refio.core.errors

import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.network.sockets.SocketTimeoutException
import java.io.IOException

object LLMErrorMapper {

    fun missingConfig(provider: String, key: String): RefioError.ProviderNotConfigured {
        return RefioError.ProviderNotConfigured(provider, key)
    }

    fun fromThrowable(
        provider: String,
        model: String,
        timeoutMs: Long,
        throwable: Throwable
    ): RuntimeException {
        if (throwable is RefioError) return throwable

        if (throwable is HttpRequestTimeoutException || throwable is SocketTimeoutException) {
            return RefioError.LLMTimeout(provider, model, timeoutMs, throwable)
        }

        val message = throwable.message.orEmpty()
        if (message.contains("401") || message.contains("403") || message.contains("api key", ignoreCase = true)) {
            return RefioError.LLMAuthentication(provider, model, throwable)
        }
        if (message.contains("429") || message.contains("rate limit", ignoreCase = true)) {
            return RefioError.LLMRateLimit(provider, null, throwable)
        }

        return RefioError.LLMError(provider, model, throwable)
    }

    fun fromHttpStatus(
        provider: String,
        model: String,
        statusCode: Int,
        message: String
    ): RefioError {
        return when (statusCode) {
            401, 403 -> RefioError.LLMAuthentication(provider, model, IllegalStateException(message))
            429 -> RefioError.LLMRateLimit(provider, null, IllegalStateException(message))
            else -> RefioError.LLMError(provider, model, IllegalStateException(message))
        }
    }

    fun listModelsFailure(provider: String, throwable: Throwable): RuntimeException {
        if (throwable is RefioError) return throwable
        val message = throwable.message ?: "unknown error"
        return RefioError.LLMError(provider, "models", IOException(message, throwable))
    }
}
