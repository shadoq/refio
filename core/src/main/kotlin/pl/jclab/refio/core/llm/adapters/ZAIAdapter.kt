package pl.jclab.refio.core.llm.adapters

import io.ktor.client.HttpClient
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.llm.ReasoningEffort
import pl.jclab.refio.core.services.ConfigService

open class ZAIAdapter(
    model: String = "glm-4.5",
    configService: ConfigService? = null,
    taskId: String? = null,
    subtaskId: String? = null,
    source: String? = null,
    httpClientOverride: HttpClient? = null
) : GenericOpenAIAdapter(
    model = model,
    providerName = "zai",
    configService = configService,
    taskId = taskId,
    subtaskId = subtaskId,
    source = source,
    requireApiKey = true,
    defaultBaseUrl = configService?.getTyped(ConfigKeys.PROVIDER_ZAI_BASE_URL) ?: ZAIUrls.DEFAULT,
    httpClientOverride = httpClientOverride
) {
    override suspend fun listModels() = super.listModels()

    /**
     * GLM 4.5+ models think by default, so "thinking OFF" has to be sent explicitly as
     * `thinking.type=disabled`; omitting the field leaves reasoning on and lets it eat the
     * whole output budget before any tool call is emitted.
     */
    override fun buildRequestBody(
        requestMessages: List<Map<String, Any>>,
        effectiveMaxTokens: Int,
        temperature: Double,
        streaming: Boolean,
        kwargs: Map<String, Any>,
        requestId: String,
    ): Map<String, Any> = super.buildRequestBody(
        requestMessages, effectiveMaxTokens, temperature, streaming, kwargs, requestId,
    ) + ("thinking" to mapOf(
        "type" to if (ReasoningEffort.fromThinkingKwarg(kwargs["thinking"]).isOn) "enabled" else "disabled"
    ))
}
