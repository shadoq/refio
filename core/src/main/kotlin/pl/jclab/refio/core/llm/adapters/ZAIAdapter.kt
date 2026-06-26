package pl.jclab.refio.core.llm.adapters

import io.ktor.client.HttpClient
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.services.ConfigService

class ZAIAdapter(
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
}
