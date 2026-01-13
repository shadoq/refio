package pl.jclab.refio.core.llm.adapters

import pl.jclab.refio.core.llm.BaseLLMAdapter
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.LLMUsage
import pl.jclab.refio.core.llm.ModelConfig
import pl.jclab.refio.core.llm.StreamChunk
import pl.jclab.refio.core.llm.toModelConfig
import pl.jclab.refio.core.utils.GsonInstance.gson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.logging.Logger as KtorLogger
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.gson.gson
import pl.jclab.refio.services.logging.dualLogger
import pl.jclab.refio.core.security.SecureLogger
import io.ktor.client.request.get
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import io.ktor.utils.io.ByteReadChannel

/**
 * Adapter for Google Gemini models (generateContent API).
 */
class GeminiAdapter(
    model: String = "gemini-2.5-flash",
    private val configService: pl.jclab.refio.core.services.ConfigService? = null,
    private val taskId: String? = null,
    private val subtaskId: String? = null,
    private val source: String? = null
) : BaseLLMAdapter(model, "gemini") {

    private val logger = dualLogger("GeminiAdapter")

    companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        private const val GENERATE_PATH = "/models/%s:generateContent"
        private const val STREAM_PATH = "/models/%s:streamGenerateContent?alt=sse"
        private const val MODELS_PATH = "/models"
    }

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
                    this@GeminiAdapter.logger.debug { message }
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
                ?: pl.jclab.refio.core.services.ConfigService.DEFAULT_API_CALL_TIMEOUT * 1000L
            requestTimeoutMillis = timeoutMs
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = timeoutMs
        }
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
        val apiKey = configService?.get(
            key = pl.jclab.refio.core.services.ConfigService.KEY_PROVIDER_GEMINI_API_KEY,
            scope = pl.jclab.refio.core.db.ConfigScope.APP
        )
            ?: System.getProperty("GEMINI_API_KEY")
            ?: System.getenv("GEMINI_API_KEY")
            ?: throw IllegalStateException("Gemini API key not provided")

        logger.info { "[GEMINI] Sending ${if (streaming) "streaming" else "standard"} chat request: model=$model, messages=${messages.size}, systemMessages=${systemMessages.size}" }

        val requestBody = buildRequestBody(messages, systemMessages, maxTokens, temperature, kwargs)
        val requestJson = gson.toJson(requestBody)
        logger.debug { "[GEMINI] Request: ${SecureLogger.redact(requestJson)}" }

        val startTime = System.currentTimeMillis()
        return if (streaming && onStreamChunk != null) {
            executeStreaming(apiKey, requestBody, requestJson, startTime, onStreamChunk)
        } else {
            executeStandard(apiKey, requestBody, requestJson, startTime)
        }
    }

    private fun buildRequestBody(
        messages: List<LLMMessage>,
        systemMessages: List<String>,
        maxTokens: Int?,
        temperature: Double,
        kwargs: Map<String, Any>
    ): Map<String, Any> {
        // Filter out any system messages from conversation messages (they should be in systemMessages parameter)
        val nonSystemMessages = messages.filter { it.role != "system" }

        // Combine all system messages from systemMessages parameter
        val combinedSystem = systemMessages
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
            .takeIf { it.isNotBlank() }

        val contents = nonSystemMessages.map { msg ->
            val role = when (msg.role.lowercase()) {
                "assistant", "model" -> "model"
                else -> "user"
            }
            mapOf(
                "role" to role,
                "parts" to listOf(mapOf("text" to msg.content))
            )
        }.ifEmpty {
            // Gemini requires at least one content; fallback to placeholder if only system prompt provided
            listOf(
                mapOf(
                    "role" to "user",
                    "parts" to listOf(mapOf("text" to ""))
                )
            )
        }

        val generationConfig = mutableMapOf<String, Any>()

        val maxOutputLimit = configService?.getMaxOutputTokens(taskId)
            ?: pl.jclab.refio.core.services.ConfigService.DEFAULT_MAX_OUTPUT_SIZE
        val requestedMax = when {
            maxTokens != null && maxTokens > 0 -> minOf(maxTokens, maxOutputLimit)
            else -> maxOutputLimit
        }
        val modelLimit = pl.jclab.refio.core.llm.ModelDefinitions
            .getDefinition("gemini", model)
            ?.maxOutputTokens
        val effectiveMax = if (modelLimit != null && modelLimit > 0 && requestedMax > modelLimit) {
            logger.warn {
                "[GEMINI] Requested maxOutputTokens=$requestedMax exceeds model limit ($modelLimit) for $model - clamping to safe value"
            }
            modelLimit
        } else {
            requestedMax
        }
        generationConfig["maxOutputTokens"] = effectiveMax
        generationConfig["temperature"] = temperature

        val thinkingEnabled = kwargs["thinking"] as? Boolean ?: false
        if (!thinkingEnabled) {
            generationConfig["thinkingConfig"] = mapOf("thinkingBudget" to 0)
        }

        // Optional extras
        (kwargs["top_p"] as? Number)?.let { generationConfig["topP"] = it }
        (kwargs["top_k"] as? Number)?.let { generationConfig["topK"] = it }

        val body = mutableMapOf<String, Any>(
            "model" to model,
            "contents" to contents
        )

        combinedSystem?.let {
            body["system_instruction"] = mapOf(
                "parts" to listOf(mapOf("text" to it))
            )
        }

        if (generationConfig.isNotEmpty()) {
            body["generationConfig"] = generationConfig
        }

        return body
    }

    private suspend fun executeStandard(
        apiKey: String,
        requestBody: Map<String, Any>,
        requestJson: String,
        startTime: Long
    ): LLMResponse {
        var httpStatus: Int? = null
        val url = "$BASE_URL${GENERATE_PATH.format(model)}"
        try {
            val httpResponse = client.post(url) {
                contentType(ContentType.Application.Json)
                header("x-goog-api-key", apiKey)
                setBody(requestBody)
            }
            httpStatus = httpResponse.status.value

            val rawResponse: Map<String, Any?> = httpResponse.body()
            val responseJson = gson.toJson(rawResponse)
            logger.debug { "[GEMINI] Response: ${SecureLogger.redact(responseJson)}" }

            if (httpStatus !in 200..299) {
                val latencyMs = (System.currentTimeMillis() - startTime).toInt()
                logger.apiError(
                    provider = provider,
                    model = model,
                    endpoint = url,
                    requestJson = requestJson,
                    httpStatus = httpStatus,
                    error = Exception("Gemini API error (HTTP $httpStatus)"),
                    latencyMs = latencyMs,
                    taskId = taskId,
                    subtaskId = subtaskId,
                    source = source
                )
                throw IllegalStateException("Gemini API error (HTTP $httpStatus)")
            }

            val usage = extractUsage(rawResponse)
            val cost = estimateCost(usage)
            val content = extractContent(rawResponse)
            val finishReason = extractFinishReason(rawResponse)

            val latencyMs = (System.currentTimeMillis() - startTime).toInt()
            logger.apiResponse(
                provider = provider,
                model = model,
                endpoint = url,
                requestJson = requestJson,
                responseJson = responseJson,
                httpStatus = httpStatus,
                inputTokens = usage.inputTokens,
                outputTokens = usage.outputTokens,
                costUsd = cost,
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
                cost = cost,
                finishReason = finishReason,
                rawResponse = rawResponse
            )
        } catch (e: Exception) {
            val latencyMs = (System.currentTimeMillis() - startTime).toInt()
            logger.apiError(
                provider = provider,
                model = model,
                endpoint = url,
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
        apiKey: String,
        requestBody: Map<String, Any>,
        requestJson: String,
        startTime: Long,
        onStreamChunk: (StreamChunk) -> Unit
    ): LLMResponse {
        val url = "$BASE_URL${STREAM_PATH.format(model)}"
        val contentBuilder = StringBuilder()
        var httpStatus: Int? = null
        var finalFinishReason: String? = null
        var finalUsage: LLMUsage? = null

        try {
            client.preparePost(url) {
                contentType(ContentType.Application.Json)
                header("x-goog-api-key", apiKey)
                setBody(requestBody)
            }.execute { response ->
                httpStatus = response.status.value

                if (httpStatus !in 200..299) {
                    val errorBody = response.body<String>()
                    val latencyMs = (System.currentTimeMillis() - startTime).toInt()
                    logger.apiError(
                        provider = provider,
                        model = model,
                        endpoint = url,
                        requestJson = requestJson,
                        httpStatus = httpStatus,
                        error = Exception("Gemini streaming error: $errorBody"),
                        latencyMs = latencyMs,
                        taskId = taskId,
                        subtaskId = subtaskId,
                        source = source
                    )
                    throw IllegalStateException("Gemini streaming error (HTTP $httpStatus)")
                }

                val channel: ByteReadChannel = response.body()
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line(limit = Int.MAX_VALUE) ?: continue
                    if (line.isBlank() || !line.startsWith("data:")) continue

                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break

                    val chunkMap = try {
                        @Suppress("UNCHECKED_CAST")
                        gson.fromJson(payload, Map::class.java) as Map<String, Any?>
                    } catch (e: Exception) {
                        logger.warn { "[GEMINI] Failed to parse stream chunk: ${e.message}" }
                        continue
                    }

                    val delta = extractContent(chunkMap)
                    if (delta.isNotEmpty()) {
                        contentBuilder.append(delta)
                        onStreamChunk(
                            StreamChunk(
                                delta = delta,
                                finishReason = null
                            )
                        )
                    }

                    finalFinishReason = extractFinishReason(chunkMap) ?: finalFinishReason
                    finalUsage = extractUsageSafe(chunkMap) ?: finalUsage
                }
            }

            val usage = finalUsage ?: LLMUsage(0, 0, 0)
            val cost = estimateCost(usage)
            val latencyMs = (System.currentTimeMillis() - startTime).toInt()

            onStreamChunk(
                StreamChunk(
                    delta = "",
                    finishReason = finalFinishReason,
                    usage = usage
                )
            )

            logger.apiResponse(
                provider = provider,
                model = model,
                endpoint = url,
                requestJson = requestJson,
                responseJson = "{\"stream\":true}",
                httpStatus = httpStatus ?: 200,
                inputTokens = usage.inputTokens,
                outputTokens = usage.outputTokens,
                costUsd = cost,
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
                cost = cost,
                finishReason = finalFinishReason
            )
        } catch (e: Exception) {
            val latencyMs = (System.currentTimeMillis() - startTime).toInt()
            logger.apiError(
                provider = provider,
                model = model,
                endpoint = url,
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

    private fun extractContent(response: Map<String, Any?>): String {
        @Suppress("UNCHECKED_CAST")
        val candidates = response["candidates"] as? List<Map<String, Any?>> ?: emptyList()
        val candidate = candidates.firstOrNull() ?: return ""
        val content = candidate["content"] as? Map<*, *> ?: return ""
        @Suppress("UNCHECKED_CAST")
        val parts = content["parts"] as? List<Map<String, Any?>> ?: return ""
        return parts.firstNotNullOfOrNull { it["text"] as? String } ?: ""
    }

    private fun extractFinishReason(response: Map<String, Any?>): String? {
        @Suppress("UNCHECKED_CAST")
        val candidates = response["candidates"] as? List<Map<String, Any?>> ?: emptyList()
        val candidate = candidates.firstOrNull() ?: return null
        return candidate["finishReason"] as? String
    }

    private fun extractUsage(response: Map<String, Any?>): LLMUsage {
        val usage = extractUsageSafe(response)
        if (usage != null) return usage
        return LLMUsage(0, 0, 0)
    }

    private fun extractUsageSafe(response: Map<String, Any?>): LLMUsage? {
        @Suppress("UNCHECKED_CAST")
        val usageMetadata = response["usageMetadata"] as? Map<String, Any?> ?: return null
        val promptTokens = (usageMetadata["promptTokenCount"] as? Number)?.toInt() ?: 0
        val candidateTokens = (usageMetadata["candidatesTokenCount"] as? Number)?.toInt() ?: 0
        val totalTokens = (usageMetadata["totalTokenCount"] as? Number)?.toInt()
            ?: (promptTokens + candidateTokens)
        return LLMUsage(
            inputTokens = promptTokens,
            outputTokens = candidateTokens,
            totalTokens = totalTokens
        )
    }

    /**
     * Lists available Gemini models.
     */
    suspend fun listModels(): List<ModelConfig> = withContext(Dispatchers.IO) {
        val apiKey = configService?.get(
            key = pl.jclab.refio.core.services.ConfigService.KEY_PROVIDER_GEMINI_API_KEY,
            scope = pl.jclab.refio.core.db.ConfigScope.APP
        )
            ?: System.getProperty("GEMINI_API_KEY")
            ?: System.getenv("GEMINI_API_KEY")
            ?: return@withContext emptyList()

        try {
            val response: Map<String, Any?> = client.get("$BASE_URL$MODELS_PATH") {
                header("x-goog-api-key", apiKey)
            }.body()

            @Suppress("UNCHECKED_CAST")
            val models = response["models"] as? List<Map<String, Any?>> ?: emptyList()
            models.mapNotNull { modelMap ->
                val modelId = modelMap["name"] as? String ?: return@mapNotNull null
                val shortId = modelId.substringAfterLast("/")

                if (!pl.jclab.refio.core.llm.SupportedModels.isSupported("gemini", shortId)) {
                    return@mapNotNull null
                }

                val definition = pl.jclab.refio.core.llm.ModelDefinitions.getDefinition("gemini", shortId)
                    ?: pl.jclab.refio.core.llm.ModelDefinitions.createFallback("gemini", shortId)
                definition.toModelConfig()
            }
        } catch (e: Exception) {
            logger.error(e) { "[GEMINI] Failed to fetch models: ${e.message}" }
            emptyList()
        }
    }

    override fun estimateCost(usage: LLMUsage): Double {
        return pl.jclab.refio.core.llm.calculateCost(
            provider = provider,
            model = model,
            inputTokens = usage.inputTokens,
            outputTokens = usage.outputTokens
        )
    }

    override suspend fun close() {
        client.close()
    }
}
