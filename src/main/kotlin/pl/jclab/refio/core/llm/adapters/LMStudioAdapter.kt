package pl.jclab.refio.core.llm.adapters

import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.llm.BaseLLMAdapter
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.LLMUsage
import pl.jclab.refio.core.llm.ModelConfig
import pl.jclab.refio.core.llm.StreamChunk
import pl.jclab.refio.core.llm.toModelConfig
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.ConfigService.Companion.DEFAULT_CONTEXT_SIZE
import pl.jclab.refio.core.utils.GsonInstance.gson
import pl.jclab.refio.services.logging.dualLogger
import pl.jclab.refio.core.security.SecureLogger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.logging.Logger as KtorLogger
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.gson.gson

/**
 * Adapter for LM Studio (local) using OpenAI-compatible API.
 */
class LMStudioAdapter(
    model: String = "local",
    private val baseUrl: String? = null,
    private val configService: ConfigService? = null,
    private val taskId: String? = null,
    private val subtaskId: String? = null,
    private val source: String? = null
) : BaseLLMAdapter(model, "lmstudio") {

    companion object {
        const val DEFAULT_BASE_URL = "http://localhost:1234/v1"
        private const val CHAT_ENDPOINT = "/chat/completions"
        private const val MODELS_ENDPOINT = "/models"
    }

    private val logger = dualLogger("LMStudioAdapter")

    private val timeout: Long
        get() = configService?.getApiCallTimeoutMs(taskId)
            ?: ConfigService.DEFAULT_API_CALL_TIMEOUT * 1000L

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            gson {
                setPrettyPrinting()
                serializeNulls()
            }
        }
        install(Logging) {
            level = LogLevel.INFO
            logger = object : KtorLogger {
                override fun log(message: String) {
                    this@LMStudioAdapter.logger.debug { message }
                }
            }
            sanitizeHeader { header ->
                header.equals(HttpHeaders.Authorization, ignoreCase = true) ||
                    header.equals("x-api-key", ignoreCase = true) ||
                    header.equals("x-goog-api-key", ignoreCase = true)
            }
        }
        install(HttpTimeout) {
            val timeoutMs = configService?.getApiCallTimeoutMs(taskId)
                ?: ConfigService.DEFAULT_API_CALL_TIMEOUT * 1000L
            requestTimeoutMillis = timeoutMs
            connectTimeoutMillis = 30000
            socketTimeoutMillis = timeoutMs
        }
    }

    private fun resolveBaseUrl(): String {
        return baseUrl
            ?: configService?.get(ConfigService.KEY_PROVIDER_LM_STUDIO_BASE_URL, ConfigScope.APP)
            ?: System.getProperty("LM_STUDIO_BASE_URL")
            ?: System.getenv("LM_STUDIO_BASE_URL")
            ?: DEFAULT_BASE_URL
    }

    private fun resolveApiKey(): String? {
        return configService?.get(ConfigService.KEY_PROVIDER_LM_STUDIO_API_KEY, ConfigScope.APP)
            ?: System.getProperty("LM_STUDIO_API_KEY")
            ?: System.getenv("LM_STUDIO_API_KEY")
    }

    override suspend fun chat(
        messages: List<LLMMessage>,
        systemMessages: List<String>,
        maxTokens: Int?,
        temperature: Double,
        streaming: Boolean,
        onStreamChunk: ((StreamChunk) -> Unit)?,
        kwargs: Map<String, Any>
    ): LLMResponse {
        logger.info { "[LMStudio] Sending ${if (streaming) "streaming" else "standard"} chat request: model=$model, messages=${messages.size}, systemMessages=${systemMessages.size}" }

        val resolvedBaseUrl = resolveBaseUrl()
        val apiKey = resolveApiKey()

        val lmMessages = mutableListOf<Map<String, String>>()

        // Add system messages from systemMessages parameter
        systemMessages.filter { it.isNotBlank() }.forEach { sysMsg ->
            lmMessages.add(mapOf("role" to "system", "content" to sysMsg))
        }

        // Add conversation messages (filter out any system messages as they should be in systemMessages parameter)
        messages.filter { it.role != "system" }.forEach { msg ->
            lmMessages.add(mapOf("role" to msg.role, "content" to msg.content))
        }

        val maxOutputLimit = configService?.getMaxOutputTokens(taskId)
            ?: ConfigService.DEFAULT_MAX_OUTPUT_SIZE
        val requestedMaxTokens = when {
            maxTokens != null && maxTokens > 0 -> minOf(maxTokens, maxOutputLimit)
            else -> maxOutputLimit
        }
        val modelLimit = pl.jclab.refio.core.llm.ModelDefinitions
            .getDefinition("lmstudio", model)
            ?.maxOutputTokens
        val effectiveMaxTokens = if (modelLimit != null && modelLimit > 0 && requestedMaxTokens > modelLimit) {
            logger.warn {
                "[LMStudio] Requested max_tokens=$requestedMaxTokens exceeds model limit ($modelLimit) for $model - clamping to safe value"
            }
            modelLimit
        } else {
            requestedMaxTokens
        }

        val requestBody = buildMap<String, Any> {
            put("model", model)
            put("messages", lmMessages)
            put("temperature", temperature)
            put("max_tokens", effectiveMaxTokens)
            if (streaming) put("stream", true)

            (kwargs["top_p"] as? Number)?.let { put("top_p", it) }
            (kwargs["frequency_penalty"] as? Number)?.let { put("frequency_penalty", it) }
            (kwargs["presence_penalty"] as? Number)?.let { put("presence_penalty", it) }
            kwargs["stop"]?.let { put("stop", it) }
        }

        val requestJson = gson.toJson(requestBody)
        logger.debug { "[LMStudio] Request: ${SecureLogger.redact(requestJson)}" }

        val startTime = System.currentTimeMillis()

        return if (streaming && onStreamChunk != null) {
            executeStreaming(resolvedBaseUrl, apiKey, requestBody, requestJson, startTime, onStreamChunk)
        } else {
            executeStandard(resolvedBaseUrl, apiKey, requestBody, requestJson, startTime)
        }
    }

    private suspend fun executeStandard(
        baseUrl: String,
        apiKey: String?,
        requestBody: Map<String, Any>,
        requestJson: String,
        startTime: Long
    ): LLMResponse {
        var httpStatus: Int? = null

        try {
            logger.info { "[LMStudio] Request start: endpoint=$baseUrl$CHAT_ENDPOINT, body=${SecureLogger.redact(requestJson)}" }
            val response = client.post("$baseUrl$CHAT_ENDPOINT") {
                contentType(ContentType.Application.Json)
                apiKey?.let { header("Authorization", "Bearer $it") }
                setBody(requestBody)
            }

            httpStatus = response.status.value
            val rawResponse: Map<String, Any?> = response.body()

            if (httpStatus !in 200..299) {
                val latencyMs = (System.currentTimeMillis() - startTime).toInt()
                val errorMessage = (rawResponse["error"] as? Map<*, *>)?.get("message") as? String
                    ?: "LM Studio API error (HTTP $httpStatus)"

                logger.apiError(
                    provider = provider,
                    model = model,
                    endpoint = "$baseUrl$CHAT_ENDPOINT",
                    requestJson = requestJson,
                    httpStatus = httpStatus,
                    error = Exception(errorMessage),
                    latencyMs = latencyMs,
                    taskId = taskId,
                    subtaskId = subtaskId,
                    source = source
                )
                throw IllegalStateException(errorMessage)
            }

            val usageMap = rawResponse["usage"] as? Map<String, Any?> ?: emptyMap()
            val promptTokens = (usageMap["prompt_tokens"] as? Number)?.toInt() ?: 0
            val completionTokens = (usageMap["completion_tokens"] as? Number)?.toInt() ?: 0
            val totalTokens = (usageMap["total_tokens"] as? Number)?.toInt() ?: promptTokens + completionTokens

            val usage = LLMUsage(
                inputTokens = promptTokens,
                outputTokens = completionTokens,
                totalTokens = totalTokens
            )

            val choices = rawResponse["choices"] as? List<Map<String, Any?>> ?: emptyList()
            val firstChoice = choices.firstOrNull() ?: emptyMap()
            val message = firstChoice["message"] as? Map<String, Any?> ?: emptyMap()
            val content = message["content"] as? String ?: ""
            val finishReason = firstChoice["finish_reason"] as? String

            val latencyMs = (System.currentTimeMillis() - startTime).toInt()
            val responseJson = gson.toJson(rawResponse)
            logger.info {
                "[LMStudio] Response received: status=$httpStatus, durationMs=${System.currentTimeMillis() - startTime}, " +
                    "bodySize=${responseJson.length}"
            }

            logger.apiResponse(
                provider = provider,
                model = model,
                endpoint = "$baseUrl$CHAT_ENDPOINT",
                requestJson = requestJson,
                responseJson = responseJson,
                httpStatus = httpStatus,
                inputTokens = usage.inputTokens,
                outputTokens = usage.outputTokens,
                costUsd = 0.0,
                latencyMs = latencyMs,
                taskId = taskId,
                subtaskId = subtaskId,
                source = source
            )

            return LLMResponse(
                content = content,
                usage = usage,
                model = model,
                provider = provider,
                cost = 0.0,
                finishReason = finishReason,
                rawResponse = rawResponse
            )
        } catch (e: Exception) {
            val latencyMs = (System.currentTimeMillis() - startTime).toInt()
            logger.apiError(
                provider = provider,
                model = model,
                endpoint = "$baseUrl$CHAT_ENDPOINT",
                requestJson = requestJson,
                httpStatus = httpStatus,
                error = e,
                latencyMs = latencyMs,
                taskId = taskId,
                subtaskId = subtaskId,
                source = source
            )
            throw e
        }
    }

    private suspend fun executeStreaming(
        baseUrl: String,
        apiKey: String?,
        requestBody: Map<String, Any>,
        requestJson: String,
        startTime: Long,
        onStreamChunk: (StreamChunk) -> Unit
    ): LLMResponse {
        val contentBuilder = StringBuilder()
        var httpStatus: Int? = null
        var finalFinishReason: String? = null

        try {
            logger.info { "[LMStudio] Request start: endpoint=$baseUrl$CHAT_ENDPOINT, body=${SecureLogger.redact(requestJson)}" }
            client.preparePost("$baseUrl$CHAT_ENDPOINT") {
                contentType(ContentType.Application.Json)
                apiKey?.let { header("Authorization", "Bearer $it") }
                setBody(requestBody)
            }.execute { httpResponse ->
                httpStatus = httpResponse.status.value

                if (httpStatus !in 200..299) {
                    val errorBody = httpResponse.body<String>()
                    val latencyMs = (System.currentTimeMillis() - startTime).toInt()
                    val errorMessage = "LM Studio API error (HTTP $httpStatus): $errorBody"

                    logger.apiError(
                        provider = provider,
                        model = model,
                        endpoint = "$baseUrl$CHAT_ENDPOINT",
                        requestJson = requestJson,
                        httpStatus = httpStatus,
                        error = Exception(errorMessage),
                        latencyMs = latencyMs,
                        taskId = taskId,
                        subtaskId = subtaskId,
                        source = source
                    )
                    throw IllegalStateException(errorMessage)
                }

                val channel: io.ktor.utils.io.ByteReadChannel = httpResponse.body()
                while (!channel.isClosedForRead) {
                    if (pl.jclab.refio.core.services.monitoring.GlobalMetrics.isCancelled()) {
                        finalFinishReason = "cancelled"
                        break
                    }

                    val line = channel.readUTF8Line(limit = Int.MAX_VALUE) ?: continue
                    if (line.isBlank() || !line.startsWith("data: ")) continue

                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") break

                    try {
                        @Suppress("UNCHECKED_CAST")
                        val chunk = gson.fromJson(data, Map::class.java) as Map<String, Any?>
                        val choices = chunk["choices"] as? List<Map<String, Any?>> ?: emptyList()
                        val first = choices.firstOrNull() ?: emptyMap()
                        val delta = first["delta"] as? Map<String, Any?>
                        val content = delta?.get("content") as? String
                        val finishReason = first["finish_reason"] as? String

                        if (!content.isNullOrEmpty()) {
                            contentBuilder.append(content)
                            onStreamChunk(StreamChunk(delta = content, finishReason = null))
                        }

                        if (finishReason != null) {
                            finalFinishReason = finishReason
                        }
                    } catch (_: Exception) {
                        continue
                    }
                }
            }

            val inputTokensEstimate = (requestBody["messages"] as? List<Map<String, String>>)
                ?.sumOf { it["content"]?.length ?: 0 } ?: 0
            val outputTokensEstimate = contentBuilder.length / 4

            val usage = LLMUsage(
                inputTokens = inputTokensEstimate,
                outputTokens = outputTokensEstimate,
                totalTokens = inputTokensEstimate + outputTokensEstimate
            )

            onStreamChunk(StreamChunk(delta = "", finishReason = finalFinishReason, usage = usage))

            val latencyMs = (System.currentTimeMillis() - startTime).toInt()
            val syntheticResponse = mapOf(
                "choices" to listOf(
                    mapOf(
                        "message" to mapOf("role" to "assistant", "content" to contentBuilder.toString()),
                        "finish_reason" to finalFinishReason
                    )
                ),
                "usage" to mapOf(
                    "prompt_tokens" to usage.inputTokens,
                    "completion_tokens" to usage.outputTokens,
                    "total_tokens" to usage.totalTokens
                ),
                "model" to model
            )
            val responseJson = gson.toJson(syntheticResponse)
            logger.info {
                "[LMStudio] Response received: status=${httpStatus ?: 200}, durationMs=${System.currentTimeMillis() - startTime}, " +
                    "bodySize=${responseJson.length}"
            }

            logger.apiResponse(
                provider = provider,
                model = model,
                endpoint = "$baseUrl$CHAT_ENDPOINT",
                requestJson = requestJson,
                responseJson = responseJson,
                httpStatus = httpStatus ?: 200,
                inputTokens = usage.inputTokens,
                outputTokens = usage.outputTokens,
                costUsd = 0.0,
                latencyMs = latencyMs,
                taskId = taskId,
                subtaskId = subtaskId,
                source = source
            )

            return LLMResponse(
                content = contentBuilder.toString(),
                usage = usage,
                model = model,
                provider = provider,
                cost = 0.0,
                finishReason = finalFinishReason,
                rawResponse = syntheticResponse
            )
        } catch (e: Exception) {
            val latencyMs = (System.currentTimeMillis() - startTime).toInt()
            logger.apiError(
                provider = provider,
                model = model,
                endpoint = "$baseUrl$CHAT_ENDPOINT",
                requestJson = requestJson,
                httpStatus = httpStatus,
                error = e,
                latencyMs = latencyMs,
                taskId = taskId,
                subtaskId = subtaskId,
                source = source
            )
            throw e
        }
    }

    /**
     * Lists models from LM Studio /v1/models endpoint.
     */
    suspend fun listModels(): List<ModelConfig> {
        val resolvedBaseUrl = resolveBaseUrl()
        val apiKey = resolveApiKey()

        try {
            val response = client.get("$resolvedBaseUrl$MODELS_ENDPOINT") {
                apiKey?.let { header("Authorization", "Bearer $it") }
            }

            val body: Map<String, Any?> = response.body()
            val modelsData = body["data"] as? List<Map<String, Any?>> ?: emptyList()

            val contextSize = configService?.getLMStudioContextSize() ?: DEFAULT_CONTEXT_SIZE

            return modelsData.mapNotNull { modelData ->
                val modelId = modelData["id"] as? String ?: return@mapNotNull null

                if (!pl.jclab.refio.core.llm.SupportedModels.isSupported("lmstudio", modelId)) {
                    return@mapNotNull null
                }

                val definition = pl.jclab.refio.core.llm.ModelDefinitions.getDefinition("lmstudio", modelId)
                    ?: pl.jclab.refio.core.llm.ModelDefinitions.createFallback(
                        provider = "lmstudio",
                        modelId = modelId,
                        maxContext = (modelData["context_length"] as? Number)?.toInt() ?: contextSize
                    )

                definition.toModelConfig()
            }
        } catch (e: Exception) {
            logger.error(e) { "[LMStudio] Failed to fetch models: ${e.message}" }
            throw Exception("Failed to fetch LM Studio models. Is the server running at $resolvedBaseUrl?", e)
        }
    }

    override fun estimateCost(usage: LLMUsage): Double = 0.0

    override suspend fun close() {
        client.close()
    }
}
