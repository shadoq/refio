package pl.jclab.refio.core.api.modules

import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.EmbeddingProvider
import pl.jclab.refio.core.services.OllamaEmbeddingProvider
import pl.jclab.refio.core.services.OpenAIEmbeddingProvider
import pl.jclab.refio.core.services.ConfigService

private val logger = dualLogger("EmbeddingProviderFactory")

/**
 * Resolves `"provider/model"` strings and produces the concrete [EmbeddingProvider].
 *
 * Kept as a small helper rather than inlined in CoreApiRouter so both RagModule
 * and direct callers share the same resolution rules.
 */
class EmbeddingProviderFactory(private val configService: ConfigService) {

    /**
     * Create a provider by the configured model string (supports "provider/model" or bare model).
     */
    fun create(model: String): EmbeddingProvider {
        val (providerId, _) = resolve(model)
        return forProvider(providerId)
    }

    /**
     * Parse "provider/model" or a bare model name; default to a reasonable provider.
     */
    fun resolve(model: String): Pair<String, String> {
        return if (model.contains("/")) {
            val parts = model.split("/", limit = 2)
            parts[0].lowercase() to parts[1]
        } else {
            val provider = when {
                model.startsWith("text-embedding") -> "openai"
                model in OLLAMA_DEFAULT_MODELS -> "ollama"
                else -> "openai"
            }
            provider to model
        }
    }

    fun forProvider(providerId: String): EmbeddingProvider {
        return when (providerId.lowercase()) {
            "ollama" -> {
                val ollamaEndpoint = configService.getTyped(ConfigKeys.PROVIDER_OLLAMA_ENDPOINT)
                OllamaEmbeddingProvider(ollamaEndpoint)
            }
            "openai" -> OpenAIEmbeddingProvider()
            else -> {
                logger.warn { "Unknown embedding provider: $providerId, defaulting to OpenAI" }
                OpenAIEmbeddingProvider()
            }
        }
    }

    companion object {
        private val OLLAMA_DEFAULT_MODELS = setOf(
            "nomic-embed-text",
            "mxbai-embed-large",
            "all-minilm",
            "all-MiniLM-L6-v2"
        )
    }
}
