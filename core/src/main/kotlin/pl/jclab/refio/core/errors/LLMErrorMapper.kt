package pl.jclab.refio.core.errors

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.network.sockets.SocketTimeoutException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.nio.channels.UnresolvedAddressException

object LLMErrorMapper {

    fun missingConfig(provider: String, key: String): RefioError.ProviderNotConfigured {
        return RefioError.ProviderNotConfigured(provider, key)
    }

    fun fromThrowable(
        provider: String,
        model: String,
        timeoutMs: Long,
        throwable: Throwable,
        endpoint: String? = null
    ): RuntimeException {
        if (throwable is RefioError) return throwable

        // Connection failures are often wrapped (Ktor wraps them in its own types),
        // so match along the whole cause chain, not just the outermost exception.
        val causeChain = generateSequence(throwable) { it.cause }
        if (causeChain.any { isConnectionFailure(it) }) {
            return RefioError.LLMConnectionFailed(provider, endpoint, throwable)
        }

        if (throwable is HttpRequestTimeoutException || throwable is SocketTimeoutException) {
            return RefioError.LLMTimeout(provider, model, timeoutMs, throwable)
        }

        val message = throwable.message.orEmpty()
        if (isContextOverflowMessage(message)) {
            return RefioError.LLMContextOverflow(provider, model, throwable)
        }
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
        if (isContextOverflowMessage(message)) {
            return RefioError.LLMContextOverflow(provider, model, IllegalStateException(message))
        }
        return when (statusCode) {
            401, 403 -> RefioError.LLMAuthentication(provider, model, IllegalStateException(message))
            404 -> if (message.contains("not found", ignoreCase = true)) {
                RefioError.LLMModelNotFound(provider, model, IllegalStateException(message))
            } else {
                RefioError.LLMError(provider, model, IllegalStateException(message))
            }
            429 -> RefioError.LLMRateLimit(provider, null, IllegalStateException(message))
            else -> RefioError.LLMError(provider, model, IllegalStateException(message))
        }
    }

    private fun isConnectionFailure(throwable: Throwable): Boolean {
        if (throwable is ConnectException ||
            throwable is ConnectTimeoutException ||
            throwable is NoRouteToHostException ||
            throwable is UnresolvedAddressException
        ) {
            return true
        }
        val message = throwable.message.orEmpty()
        return message.contains("connection refused", ignoreCase = true) ||
            message.contains("host unreachable", ignoreCase = true) ||
            message.contains("no route to host", ignoreCase = true) ||
            message.contains("failed to connect", ignoreCase = true)
    }

    private fun isContextOverflowMessage(message: String): Boolean {
        // Ollama phrases the overflow differently across versions; match common wordings.
        return message.contains("context", ignoreCase = true) &&
            (
                message.contains("length", ignoreCase = true) ||
                    message.contains("too large", ignoreCase = true) ||
                    message.contains("exceed", ignoreCase = true)
                )
    }

    fun listModelsFailure(provider: String, throwable: Throwable): RuntimeException {
        if (throwable is RefioError) return throwable
        val message = throwable.message ?: "unknown error"
        return RefioError.LLMError(provider, "models", IOException(message, throwable))
    }
}
