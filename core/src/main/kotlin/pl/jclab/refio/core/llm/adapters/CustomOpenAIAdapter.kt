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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.jclab.refio.core.config.ConfigKeys
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
import pl.jclab.refio.core.logging.dualLogger
import java.util.UUID

open class CustomOpenAIAdapter(
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
        private const val ZAI_COOLDOWN_MS = 5_000L
        private const val ZAI_RATE_LIMIT_RETRY_DELAY_MS = 15_000L
        private val zaiRequestMutex = Mutex()
        private var zaiNextAllowedAtMs: Long = 0L
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
            val timeoutMs = configService?.getTyped(ConfigKeys.API_CALL_TIMEOUT, taskId)?.toLong()?.times(1000L)
                ?: ConfigKeys.API_CALL_TIMEOUT.default.toLong() * 1000L
            requestTimeoutMillis = timeoutMs
            connectTimeoutMillis = 30_000L
            socketTimeoutMillis = timeoutMs
        }
    }

    private fun resolveBaseUrl(): String {
        val configured = baseUrlOverride?.takeIf { it.isNotBlank() }
            ?: when (providerName) {
                "zai" -> configService?.getTyped(ConfigKeys.PROVIDER_ZAI_BASE_URL)
                else -> configService?.getTyped(ConfigKeys.PROVIDER_CUSTOM_OPENAI_BASE_URL)
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
                "zai" -> configService?.getTyped(ConfigKeys.PROVIDER_ZAI_API_KEY)
                else -> configService?.getTyped(ConfigKeys.PROVIDER_CUSTOM_OPENAI_API_KEY)
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
        val requestMessages = buildList<Map<String, Any>> {
            systemMessages.filter { it.isNotBlank() }.forEach { add(mapOf("role" to "system", "content" to it)) }
            // Remap "tool" (used by LLMMessageMapper for tool results) to "assistant" — OpenAI-compatible
            // APIs require tool_call_id alongside role="tool", which this adapter does not currently emit.
            messages.filter { it.role != "system" }.forEach {
                val mappedRole = if (it.role == "tool") "assistant" else it.role
                add(mapOf("role" to mappedRole, "content" to toOpenAiMessageContent(it)))
            }
        }
        val maxOutputLimit = configService?.getTyped(ConfigKeys.MAX_OUTPUT_SIZE, taskId) ?: ConfigKeys.MAX_OUTPUT_SIZE.default
        val effectiveMaxTokens = when {
            maxTokens != null && maxTokens > 0 -> minOf(maxTokens, maxOutputLimit)
            else -> maxOutputLimit
        }
        val requestId = UUID.randomUUID().toString()
        val requestBody = buildMap<String, Any> {
            put("request_id", requestId)
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
        val startTime = System.currentTimeMillis()
        val logPrefix = "[${providerName.uppercase()}][$requestId]"

        return try {
            if (streaming && onStreamChunk != null) {
                executeStreaming(baseUrl, apiKey, requestBody, requestJson, startTime, onStreamChunk, logPrefix)
            } else {
                executeStandard(baseUrl, apiKey, requestBody, requestJson, startTime, logPrefix)
            }
        } catch (e: HttpRequestTimeoutException) {
            throw RefioError.LLMTimeout(providerName, model, configService?.getTyped(ConfigKeys.API_CALL_TIMEOUT, taskId)?.toLong()?.times(1000L) ?: 0L, e)
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
            logger.info { "$logPrefix Request start: endpoint=$baseUrl$CHAT_ENDPOINT, body=${SecureLogger.redactAndTruncate(requestJson)}" }
            val response = executeWithZaiRateLimitRetry("$baseUrl$CHAT_ENDPOINT") {
                withProviderRateLimit("$baseUrl$CHAT_ENDPOINT") {
                    client.post("$baseUrl$CHAT_ENDPOINT") {
                        contentType(ContentType.Application.Json)
                        apiKey?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                        setBody(requestBody)
                    }
                }
            }

            httpStatus = response.status.value
            val rawResponse: Map<String, Any?> = response.body()
            ensureSuccess(httpStatus, rawResponse, baseUrl)

            val usage = extractUsage(rawResponse)
            @Suppress("UNCHECKED_CAST")
            val choices = rawResponse["choices"] as? List<Map<String, Any?>> ?: emptyList()
            val firstChoice = choices.firstOrNull() ?: emptyMap()
            @Suppress("UNCHECKED_CAST")
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
            logger.info { "$logPrefix Request start: endpoint=$baseUrl$CHAT_ENDPOINT, body=${SecureLogger.redactAndTruncate(requestJson)}" }
            executeWithZaiRateLimitRetry("$baseUrl$CHAT_ENDPOINT") {
                withProviderRateLimit("$baseUrl$CHAT_ENDPOINT") {
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

                            try {
                                @Suppress("UNCHECKED_CAST")
                                val chunk = gson.fromJson(data, Map::class.java) as Map<String, Any?>
                                @Suppress("UNCHECKED_CAST")
                                val choices = chunk["choices"] as? List<Map<String, Any?>> ?: emptyList()
                                val first = choices.firstOrNull() ?: emptyMap()
                                @Suppress("UNCHECKED_CAST")
                                val delta = first["delta"] as? Map<String, Any?>
                                toolCallAccumulator.consumeDelta(delta)
                                val content = delta?.get("content") as? String
                                if (!content.isNullOrEmpty()) {
                                    contentBuilder.append(content)
                                    onStreamChunk(StreamChunk(delta = content))
                                }
                                finalFinishReason = first["finish_reason"] as? String ?: finalFinishReason
                            } catch (e: CancellationException) {
                                // Let stream abort (guardrail trip) propagate out of the loop.
                                // NOTE: replaced an earlier `runCatching { }` here — runCatching
                                // swallowed CancellationException into a Result and the abort was lost.
                                throw e
                            } catch (_: Exception) {
                                // Match previous behavior of silently skipping malformed chunks.
                            }
                        }
                    }
                }
            }

            if (contentBuilder.isEmpty()) {
                toolCallAccumulator.toCanonicalJson()?.let { contentBuilder.append(it) }
            }

            @Suppress("UNCHECKED_CAST")
            val inputTokensEstimate = (requestBody["messages"] as? List<Map<String, Any?>>)?.sumOf {
                when (val content = it["content"]) {
                    is String -> content.length
                    is List<*> -> content.sumOf { part ->
                        @Suppress("UNCHECKED_CAST")
                        val partMap = part as? Map<String, Any?>
                        (partMap?.get("text") as? String)?.length ?: 0
                    }
                    else -> 0
                }
            } ?: 0
            val usage = LLMUsage(
                inputTokens = inputTokensEstimate,
                outputTokens = contentBuilder.length / 4,
                totalTokens = inputTokensEstimate + contentBuilder.length / 4
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
            throw e
        } catch (e: CancellationException) {
            // Guardrail-triggered abort — log and rethrow as-is, do NOT wrap.
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

    open suspend fun listModels(): List<ModelConfig> = withContext(Dispatchers.IO) {
        val baseUrl = resolveBaseUrl()
        val apiKey = resolveApiKey()
        val startTime = System.currentTimeMillis()

        try {
            logger.info { "[${providerName.uppercase()}] Request start: endpoint=$baseUrl$MODELS_ENDPOINT" }
            val response = withProviderRateLimit("$baseUrl$MODELS_ENDPOINT") {
                client.get("$baseUrl$MODELS_ENDPOINT") {
                    apiKey?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                }
            }
            val rawBody = response.body<String>()

            if (response.status.value !in 200..299) {
                throw mapHttpError(response.status.value, rawBody)
            }

            parseModelsPayload(rawBody)
        } catch (e: RefioError) {
            logger.apiError(
                provider = provider,
                model = "models",
                endpoint = "$baseUrl$MODELS_ENDPOINT",
                requestJson = "",
                httpStatus = null,
                error = e,
                latencyMs = (System.currentTimeMillis() - startTime).toInt(),
                taskId = taskId,
                subtaskId = subtaskId,
                source = source
            )
            throw e
        } catch (e: Exception) {
            logger.apiError(
                provider = provider,
                model = "models",
                endpoint = "$baseUrl$MODELS_ENDPOINT",
                requestJson = "",
                httpStatus = null,
                error = e,
                latencyMs = (System.currentTimeMillis() - startTime).toInt(),
                taskId = taskId,
                subtaskId = subtaskId,
                source = source
            )
            throw RefioError.LLMError(providerName, model, e)
        }
    }

    internal fun parseModelsPayload(rawBody: String): List<ModelConfig> {
        val parsed = gson.fromJson(rawBody, Any::class.java)
        val modelsData: List<*> = when (parsed) {
            is Map<*, *> -> parsed["data"] as? List<*> ?: emptyList<Any?>()
            is List<*> -> parsed
            else -> emptyList<Any?>()
        }

        return modelsData.mapNotNull { item ->
            val modelData = item as? Map<*, *> ?: return@mapNotNull null
            val modelId = modelData["id"] as? String ?: return@mapNotNull null
            val contextLength = (modelData["context_length"] as? Number)?.toInt() ?: DEFAULT_CONTEXT_SIZE
            val definition = ModelDefinitions.getDefinition(providerName, modelId)
                ?: ModelDefinitions.createFallback(providerName, modelId, contextLength)
            definition.toModelConfig()
        }
    }

    override fun estimateCost(usage: LLMUsage): Double = 0.0

    override suspend fun close() {
        client.close()
    }

    private fun extractUsage(rawResponse: Map<String, Any?>): LLMUsage {
        @Suppress("UNCHECKED_CAST")
        val usageMap = rawResponse["usage"] as? Map<String, Any?> ?: emptyMap()
        val promptTokens = (usageMap["prompt_tokens"] as? Number)?.toInt() ?: 0
        val completionTokens = (usageMap["completion_tokens"] as? Number)?.toInt() ?: 0
        val totalTokens = (usageMap["total_tokens"] as? Number)?.toInt() ?: promptTokens + completionTokens
        return LLMUsage(promptTokens, completionTokens, totalTokens)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun ensureSuccess(httpStatus: Int, rawResponse: Map<String, Any?>, _baseUrl: String) {
        if (httpStatus in 200..299) return

        val message = (rawResponse["error"] as? Map<*, *>)?.get("message") as? String
            ?: "OpenAI-compatible API error (HTTP $httpStatus)"
        val code = (rawResponse["error"] as? Map<*, *>)?.get("code")?.toString()
        throw mapHttpError(httpStatus, message, code)
    }

    private fun mapHttpError(httpStatus: Int, message: String): RefioError {
        val parsed = parseProviderError(message)
        return mapHttpError(
            httpStatus = httpStatus,
            message = parsed.message ?: message,
            businessCode = parsed.code
        )
    }

    private fun mapHttpError(httpStatus: Int, message: String, businessCode: String?): RefioError {
        val zaiMessage = if (providerName == "zai") {
            buildZAIErrorMessage(httpStatus, businessCode, message)
        } else {
            message
        }

        return when (httpStatus) {
            401, 403 -> RefioError.LLMAuthentication(providerName, model, IllegalStateException(zaiMessage))
            429 -> RefioError.LLMRateLimit(providerName, null, IllegalStateException(zaiMessage))
            434 -> RefioError.LLMAuthentication(providerName, model, IllegalStateException(zaiMessage))
            else -> RefioError.LLMError(providerName, model, IllegalStateException(zaiMessage))
        }
    }

    internal data class ProviderErrorPayload(
        val code: String? = null,
        val message: String? = null
    )

    internal fun parseProviderError(rawBody: String): ProviderErrorPayload {
        return runCatching {
            val parsed = gson.fromJson(rawBody, Map::class.java)
            val error = parsed?.get("error") as? Map<*, *>
            ProviderErrorPayload(
                code = error?.get("code")?.toString(),
                message = error?.get("message")?.toString()
            )
        }.getOrDefault(ProviderErrorPayload(message = rawBody))
    }

    internal fun buildZAIErrorMessage(httpStatus: Int, businessCode: String?, message: String): String {
        val normalized = businessCode?.trim()
        val detail = when (normalized) {
            "1000", "1001", "1002", "1003", "1004" -> "Authentication failed or token expired"
            "1110" -> "Account is inactive"
            "1111" -> "Account does not exist"
            "1112", "1121" -> "Account has been locked"
            "1113" -> "Account balance exhausted"
            "1120" -> "Unable to access account temporarily"
            "1210", "1213", "1214", "1215" -> "Invalid request parameters"
            "1211" -> "Model does not exist"
            "1212" -> "Model does not support this API method"
            "1220" -> "No permission to access this API"
            "1221" -> "API has been taken offline"
            "1222" -> "API does not exist"
            "1230" -> "API call process error"
            "1231" -> "An identical request is already in progress"
            "1234" -> "Network error on provider side"
            "1301" -> "Request blocked by safety policy"
            "1302" -> "API concurrency limit exceeded"
            "1303" -> "API frequency limit exceeded"
            "1304" -> "Daily API call limit reached"
            "1305" -> "API rate limit triggered"
            "1308" -> "Usage limit reached"
            "1309" -> "GLM Coding Plan expired"
            "1310" -> "Weekly or monthly limit exhausted"
            else -> when (httpStatus) {
                401, 403 -> "Authentication failure or token timeout"
                429 -> "Rate limit or account quota restriction"
                434 -> "No API permission"
                else -> null
            }
        }

        return buildString {
            if (!normalized.isNullOrBlank()) {
                append("Z.AI error ")
                append(normalized)
                append(": ")
            }
            if (!detail.isNullOrBlank()) {
                append(detail)
                if (message.isNotBlank() && !message.contains(detail, ignoreCase = true)) {
                    append(". ")
                }
            }
            if (message.isNotBlank()) {
                append(message)
            } else if (detail.isNullOrBlank()) {
                append("HTTP ")
                append(httpStatus)
            }
        }
    }

    private suspend fun <T> withProviderRateLimit(endpoint: String, block: suspend () -> T): T {
        if (providerName != "zai") {
            return block()
        }

        return zaiRequestMutex.withLock {
            val now = System.currentTimeMillis()
            val waitMs = (zaiNextAllowedAtMs - now).coerceAtLeast(0L)
            if (waitMs > 0) {
                logger.info { "[ZAI] Waiting ${waitMs}ms before next request: $endpoint" }
                delay(waitMs)
            }

            try {
                block()
            } finally {
                zaiNextAllowedAtMs = System.currentTimeMillis() + ZAI_COOLDOWN_MS
            }
        }
    }

    private suspend fun <T> executeWithZaiRateLimitRetry(endpoint: String, block: suspend () -> T): T {
        if (providerName != "zai") {
            return block()
        }

        return try {
            block()
        } catch (e: RefioError.LLMRateLimit) {
            logger.warn {
                "[ZAI] Rate limit hit for $endpoint. Waiting ${ZAI_RATE_LIMIT_RETRY_DELAY_MS}ms before retry"
            }
            zaiNextAllowedAtMs = maxOf(
                zaiNextAllowedAtMs,
                System.currentTimeMillis() + ZAI_RATE_LIMIT_RETRY_DELAY_MS
            )
            delay(ZAI_RATE_LIMIT_RETRY_DELAY_MS)
            block()
        }
    }
}
