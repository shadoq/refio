package pl.jclab.refio.core.llm.adapters

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger as KtorLogger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.gson.gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.jclab.refio.core.errors.RefioError
import pl.jclab.refio.core.llm.BaseLLMAdapter
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.LLMUsage
import pl.jclab.refio.core.llm.ModelConfig
import pl.jclab.refio.core.llm.ModelDefinitions
import pl.jclab.refio.core.llm.StreamChunk
import pl.jclab.refio.core.llm.toModelConfig
import pl.jclab.refio.core.security.SecureLogger
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.ConfigService.Companion.DEFAULT_CONTEXT_SIZE
import pl.jclab.refio.core.utils.GsonInstance.gson
import pl.jclab.refio.services.logging.dualLogger
import java.util.UUID

class CustomOpenAIAdapter(
    model: String,
    private val providerName: String = "custom_openai",
    private val configService: ConfigService? = null,
    private val taskId: String? = null,
    private val subtaskId: String? = null,
    private val source: String? = null,
    private val baseUrlOverride: String? = null,
    private val apiKeyOverride: String? = null,
    private val requireApiKey: Boolean = false,
    private val defaultBaseUrl: String? = null
) : BaseLLMAdapter(model, providerName) {

    companion object {
        private const val CHAT_ENDPOINT = "/chat/completions"
        private const val MODELS_ENDPOINT = "/models"
    }

    private val logger = dualLogger("CustomOpenAIAdapter")

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
                    this@CustomOpenAIAdapter.logger.debug { message }
                }
            }
            sanitizeHeader { header ->
                header.equals(HttpHeaders.Authorization, ignoreCase = true)
            }
        }
        install(HttpTimeout) {
            val timeoutMs = configService?.getApiCallTimeoutMs(taskId)
                ?: ConfigService.DEFAULT_API_CALL_TIMEOUT * 1000L
            requestTimeoutMillis = timeoutMs
            connectTimeoutMillis = 30_000L
            socketTimeoutMillis = timeoutMs
        }
    }

    private fun resolveBaseUrl(): String {
        val configured = baseUrlOverride?.takeIf { it.isNotBlank() }
            ?: when (providerName) {
                "zai" -> configService?.getZAIBaseUrl()
                else -> configService?.getCustomOpenAIBaseUrl()
            }
            ?: when (providerName) {
                "zai" -> System.getProperty("ZAI_BASE_URL") ?: System.getenv("ZAI_BASE_URL")
                else -> System.getProperty("CUSTOM_OPENAI_BASE_URL") ?: System.getenv("CUSTOM_OPENAI_BASE_URL")
            }
            ?: defaultBaseUrl

        return configured?.trimEnd('/')
            ?: throw RefioError.ProviderNotConfigured(providerName, "base_url")
    }

    private fun resolveApiKey(): String? {
        val key = apiKeyOverride?.takeIf { it.isNotBlank() }
            ?: when (providerName) {
                "zai" -> configService?.getZAIApiKey()
                else -> configService?.getCustomOpenAIApiKey()
            }
            ?: when (providerName) {
                "zai" -> System.getProperty("ZAI_API_KEY") ?: System.getenv("ZAI_API_KEY")
                else -> System.getProperty("CUSTOM_OPENAI_API_KEY") ?: System.getenv("CUSTOM_OPENAI_API_KEY")
            }

        if (requireApiKey && key.isNullOrBlank()) {
            throw RefioError.ProviderNotConfigured(providerName, "api_key")
        }
        return key
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
        val baseUrl = resolveBaseUrl()
        val apiKey = resolveApiKey()
        val requestMessages = buildList {
            systemMessages.filter { it.isNotBlank() }.forEach { add(mapOf("role" to "system", "content" to it)) }
            messages.filter { it.role != "system" }.forEach { add(mapOf("role" to it.role, "content" to it.content)) }
        }
        val maxOutputLimit = configService?.getMaxOutputTokens(taskId) ?: ConfigService.DEFAULT_MAX_OUTPUT_SIZE
        val effectiveMaxTokens = when {
            maxTokens != null && maxTokens > 0 -> minOf(maxTokens, maxOutputLimit)
            else -> maxOutputLimit
        }
        val requestBody = buildMap<String, Any> {
            put("model", model)
            put("messages", requestMessages)
            put("temperature", temperature)
            put("max_tokens", effectiveMaxTokens)
            if (streaming) put("stream", true)
            (kwargs["top_p"] as? Number)?.let { put("top_p", it) }
            (kwargs["frequency_penalty"] as? Number)?.let { put("frequency_penalty", it) }
            (kwargs["presence_penalty"] as? Number)?.let { put("presence_penalty", it) }
            kwargs["stop"]?.let { put("stop", it) }
            kwargs["response_format"]?.let { put("response_format", it) }
        }
        val requestJson = gson.toJson(requestBody)
        val requestId = UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()
        val logPrefix = "[${providerName.uppercase()}][$requestId]"

        return try {
            if (streaming && onStreamChunk != null) {
                executeStreaming(baseUrl, apiKey, requestBody, requestJson, startTime, onStreamChunk, logPrefix)
            } else {
                executeStandard(baseUrl, apiKey, requestBody, requestJson, startTime, logPrefix)
            }
        } catch (e: HttpRequestTimeoutException) {
            throw RefioError.LLMTimeout(providerName, model, configService?.getApiCallTimeoutMs(taskId) ?: 0L, e)
        }
    }

    private suspend fun executeStandard(
        baseUrl: String,
        apiKey: String?,
        requestBody: Map<String, Any>,
        requestJson: String,
        startTime: Long,
        logPrefix: String
    ): LLMResponse {
        var httpStatus: Int? = null

        try {
            val response = client.post("$baseUrl$CHAT_ENDPOINT") {
                contentType(ContentType.Application.Json)
                apiKey?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                setBody(requestBody)
            }

            httpStatus = response.status.value
            val rawResponse: Map<String, Any?> = response.body()
            ensureSuccess(httpStatus, rawResponse, baseUrl)

            val usage = extractUsage(rawResponse)
            val choices = rawResponse["choices"] as? List<Map<String, Any?>> ?: emptyList()
            val firstChoice = choices.firstOrNull() ?: emptyMap()
            val message = firstChoice["message"] as? Map<String, Any?> ?: emptyMap()
            val content = message["content"] as? String ?: ""
            val normalizedToolCallsJson = if (content.isBlank()) {
                ToolCallContentNormalizer.fromOpenAiToolCalls(message["tool_calls"])
            } else {
                null
            }

            logger.apiResponse(
                provider = provider,
                model = model,
                endpoint = "$baseUrl$CHAT_ENDPOINT",
                requestJson = requestJson,
                responseJson = gson.toJson(rawResponse),
                httpStatus = httpStatus,
                inputTokens = usage.inputTokens,
                outputTokens = usage.outputTokens,
                costUsd = 0.0,
                latencyMs = (System.currentTimeMillis() - startTime).toInt(),
                taskId = taskId,
                subtaskId = subtaskId,
                source = source
            )

            return LLMResponse(
                content = normalizedToolCallsJson ?: content,
                usage = usage,
                model = model,
                provider = provider,
                cost = 0.0,
                finishReason = firstChoice["finish_reason"] as? String,
                rawResponse = rawResponse
            )
        } catch (e: RefioError) {
            throw e
        } catch (e: Exception) {
            logger.apiError(
                provider = provider,
                model = model,
                endpoint = "$baseUrl$CHAT_ENDPOINT",
                requestJson = requestJson,
                httpStatus = httpStatus,
                error = e,
                latencyMs = (System.currentTimeMillis() - startTime).toInt(),
                taskId = taskId,
                subtaskId = subtaskId,
                source = source
            )
            throw RefioError.LLMError(providerName, model, e)
        }
    }

    private suspend fun executeStreaming(
        baseUrl: String,
        apiKey: String?,
        requestBody: Map<String, Any>,
        requestJson: String,
        startTime: Long,
        onStreamChunk: (StreamChunk) -> Unit,
        logPrefix: String
    ): LLMResponse {
        val contentBuilder = StringBuilder()
        val toolCallAccumulator = ToolCallContentNormalizer.OpenAiStreamingToolCallAccumulator()
        var httpStatus: Int? = null
        var finalFinishReason: String? = null

        try {
            client.preparePost("$baseUrl$CHAT_ENDPOINT") {
                contentType(ContentType.Application.Json)
                apiKey?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                setBody(requestBody)
            }.execute { httpResponse ->
                httpStatus = httpResponse.status.value
                if (httpStatus !in 200..299) {
                    val errorBody = httpResponse.body<String>()
                    throw mapHttpError(httpStatus ?: 500, errorBody)
                }

                val channel: io.ktor.utils.io.ByteReadChannel = httpResponse.body()
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line(limit = Int.MAX_VALUE) ?: continue
                    if (line.isBlank() || !line.startsWith("data: ")) continue

                    val data = line.removePrefix("data: ").trim()
                    if (data == "[DONE]") break

                    runCatching {
                        @Suppress("UNCHECKED_CAST")
                        val chunk = gson.fromJson(data, Map::class.java) as Map<String, Any?>
                        val choices = chunk["choices"] as? List<Map<String, Any?>> ?: emptyList()
                        val first = choices.firstOrNull() ?: emptyMap()
                        val delta = first["delta"] as? Map<String, Any?>
                        toolCallAccumulator.consumeDelta(delta)
                        val content = delta?.get("content") as? String
                        if (!content.isNullOrEmpty()) {
                            contentBuilder.append(content)
                            onStreamChunk(StreamChunk(delta = content))
                        }
                        finalFinishReason = first["finish_reason"] as? String ?: finalFinishReason
                    }
                }
            }

            if (contentBuilder.isEmpty()) {
                toolCallAccumulator.toCanonicalJson()?.let { contentBuilder.append(it) }
            }

            val usage = LLMUsage(
                inputTokens = ((requestBody["messages"] as? List<Map<String, String>>)?.sumOf { it["content"]?.length ?: 0 } ?: 0),
                outputTokens = contentBuilder.length / 4,
                totalTokens = (((requestBody["messages"] as? List<Map<String, String>>)?.sumOf { it["content"]?.length ?: 0 } ?: 0) + contentBuilder.length / 4)
            )
            onStreamChunk(StreamChunk(delta = "", finishReason = finalFinishReason, usage = usage))

            logger.apiResponse(
                provider = provider,
                model = model,
                endpoint = "$baseUrl$CHAT_ENDPOINT",
                requestJson = requestJson,
                responseJson = gson.toJson(mapOf("content" to contentBuilder.toString())),
                httpStatus = httpStatus ?: 200,
                inputTokens = usage.inputTokens,
                outputTokens = usage.outputTokens,
                costUsd = 0.0,
                latencyMs = (System.currentTimeMillis() - startTime).toInt(),
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
                rawResponse = mapOf("content" to contentBuilder.toString())
            )
        } catch (e: RefioError) {
            throw e
        } catch (e: Exception) {
            logger.apiError(
                provider = provider,
                model = model,
                endpoint = "$baseUrl$CHAT_ENDPOINT",
                requestJson = requestJson,
                httpStatus = httpStatus,
                error = e,
                latencyMs = (System.currentTimeMillis() - startTime).toInt(),
                taskId = taskId,
                subtaskId = subtaskId,
                source = source
            )
            throw RefioError.LLMError(providerName, model, e)
        }
    }

    suspend fun listModels(): List<ModelConfig> = withContext(Dispatchers.IO) {
        val baseUrl = resolveBaseUrl()
        val apiKey = resolveApiKey()

        try {
            val response = client.get("$baseUrl$MODELS_ENDPOINT") {
                apiKey?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            }
            val body: Map<String, Any?> = response.body()
            val modelsData = body["data"] as? List<Map<String, Any?>> ?: emptyList()

            modelsData.mapNotNull { modelData ->
                val modelId = modelData["id"] as? String ?: return@mapNotNull null
                val contextLength = (modelData["context_length"] as? Number)?.toInt() ?: DEFAULT_CONTEXT_SIZE
                val definition = ModelDefinitions.getDefinition(providerName, modelId)
                    ?: ModelDefinitions.createFallback(providerName, modelId, contextLength)
                definition.toModelConfig()
            }
        } catch (e: RefioError) {
            throw e
        } catch (e: Exception) {
            throw RefioError.LLMError(providerName, model, e)
        }
    }

    override fun estimateCost(usage: LLMUsage): Double = 0.0

    override suspend fun close() {
        client.close()
    }

    private fun extractUsage(rawResponse: Map<String, Any?>): LLMUsage {
        val usageMap = rawResponse["usage"] as? Map<String, Any?> ?: emptyMap()
        val promptTokens = (usageMap["prompt_tokens"] as? Number)?.toInt() ?: 0
        val completionTokens = (usageMap["completion_tokens"] as? Number)?.toInt() ?: 0
        val totalTokens = (usageMap["total_tokens"] as? Number)?.toInt() ?: promptTokens + completionTokens
        return LLMUsage(promptTokens, completionTokens, totalTokens)
    }

    private fun ensureSuccess(httpStatus: Int, rawResponse: Map<String, Any?>, baseUrl: String) {
        if (httpStatus in 200..299) return

        val message = (rawResponse["error"] as? Map<*, *>)?.get("message") as? String
            ?: "OpenAI-compatible API error (HTTP $httpStatus)"
        throw mapHttpError(httpStatus, message)
    }

    private fun mapHttpError(httpStatus: Int, message: String): RefioError {
        return when (httpStatus) {
            401, 403 -> RefioError.LLMAuthentication(providerName, model)
            429 -> RefioError.LLMRateLimit(providerName, null)
            else -> RefioError.LLMError(providerName, model, IllegalStateException(message))
        }
    }
}
