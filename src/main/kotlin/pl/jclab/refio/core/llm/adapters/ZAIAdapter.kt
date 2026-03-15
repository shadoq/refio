package pl.jclab.refio.core.llm.adapters

import pl.jclab.refio.core.llm.ModelConfig
import pl.jclab.refio.core.services.ConfigService

class ZAIAdapter(
    model: String = "glm-4.5",
    configService: ConfigService? = null,
    taskId: String? = null,
    subtaskId: String? = null,
    source: String? = null
) : CustomOpenAIAdapter(
    model = model,
    providerName = "zai",
    configService = configService,
    taskId = taskId,
    subtaskId = subtaskId,
    source = source,
    requireApiKey = true,
    defaultBaseUrl = configService?.getZAIBaseUrl() ?: ConfigService.DEFAULT_ZAI_BASE_URL
) {
    override suspend fun listModels(): List<ModelConfig> = super.listModels()
}
