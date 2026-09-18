package pl.jclab.refio.core.llm

import pl.jclab.refio.core.llm.adapters.OllamaAdapter
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.ConfigService
import java.util.concurrent.ConcurrentHashMap

private val logger = dualLogger("ProviderToolSupport")

/**
 * What the serving provider says about a model's function-calling support, cached for the life of
 * the process.
 *
 * Exists because the static model table is always late by however long it takes to ship a release,
 * while a local user downloads a model on the day it appears - or builds their own with a different
 * name to pin a context window, which is enough to make it unknown. An unknown model used to be
 * treated as one that cannot call tools, silently switching the whole agent onto its weakest path.
 * The server already knows the answer, so ask it.
 *
 * Three states, on purpose: true, false, and "could not ask". Only the first two are worth caching
 * as an answer; the third is re-asked, because an unreachable server now may be reachable later.
 */
object ProviderToolSupport {

    private val answers = ConcurrentHashMap<String, Boolean>()

    private fun key(provider: String, endpoint: String, model: String) = "$provider|$endpoint|$model"

    /**
     * The cached answer for [model], or null when the provider was never asked or could not answer.
     * Pure lookup: safe to call from a non-suspending decision path, which is why [probe] has to
     * have run first.
     */
    fun cached(provider: String, model: String, configService: ConfigService?): Boolean? {
        if (!provider.equals("ollama", ignoreCase = true)) return null
        return answers[key(provider, endpointFor(configService), model)]
    }

    private fun endpointFor(configService: ConfigService?): String =
        OllamaAdapter(configService = configService).endpoint()

    /**
     * Ask the provider whether [model] supports native tool calls, once per model per process.
     *
     * Only Ollama can be asked today; every other provider returns null, which leaves the decision
     * exactly where it was before. One request per model, not per iteration.
     */
    suspend fun probe(provider: String, model: String, configService: ConfigService?): Boolean? {
        if (!provider.equals("ollama", ignoreCase = true)) return null
        val adapter = OllamaAdapter(configService = configService)
        val endpoint = adapter.endpoint()
        val cacheKey = key(provider, endpoint, model)
        answers[cacheKey]?.let { return it }

        val capabilities = adapter.fetchCapabilities(model) ?: return null
        val supported = OllamaAdapter.CAPABILITY_TOOLS in capabilities
        answers[cacheKey] = supported
        logger.info {
            "[PROVIDER_TOOLS] $provider/$model at $endpoint reports tools=$supported " +
                "(capabilities=$capabilities)"
        }
        return supported
    }

    /** Test-only: forget every cached answer. */
    fun reset() {
        answers.clear()
    }
}
