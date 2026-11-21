package pl.jclab.refio.core.llm.decision

import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.security.NetworkPolicy
import pl.jclab.refio.core.services.ConfigService
import java.util.concurrent.ConcurrentHashMap

/**
 * Hands out one [DecisionClient] per provider and endpoint. Each client owns an HTTP engine, so
 * clients are cached instead of being created per judge call; a changed Ollama endpoint gets a
 * new client on the next call.
 */
class DecisionClientFactory(private val configService: ConfigService) {

    private val networkPolicy = NetworkPolicy(configService)
    private val clients = ConcurrentHashMap<String, DecisionClient>()

    fun forProvider(provider: String): DecisionClient = when (provider.lowercase()) {
        OllamaDecisionClient.PROVIDER -> {
            val endpoint = configService.getTyped(ConfigKeys.PROVIDER_OLLAMA_ENDPOINT)
            clients.computeIfAbsent("ollama|$endpoint") {
                OllamaDecisionClient(
                    endpoint = endpoint,
                    keepAliveSeconds = configService.getTyped(ConfigKeys.PROVIDER_OLLAMA_KEEP_ALIVE),
                    networkPolicy = networkPolicy
                )
            }
        }
        OpenAIDecisionClient.PROVIDER -> clients.computeIfAbsent("openai") {
            OpenAIDecisionClient(apiKeyProvider = ::openAIApiKey, networkPolicy = networkPolicy)
        }
        else -> throw DecisionCallException(
            "Provider '$provider' has no decision-model API - use ollama or openai"
        )
    }

    /** Same lookup order as the OpenAI chat adapter: settings, then system property, then environment. */
    private fun openAIApiKey(): String? =
        configService.get(key = ConfigKeys.PROVIDER_OPENAI_API_KEY.key, scope = ConfigScope.APP)
            ?: System.getProperty("OPENAI_API_KEY")
            ?: System.getenv("OPENAI_API_KEY")
}
