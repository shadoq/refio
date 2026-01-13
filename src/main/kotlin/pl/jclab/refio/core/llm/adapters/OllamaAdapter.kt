package pl.jclab.refio.core.llm.adapters

import pl.jclab.refio.core.llm.BaseLLMAdapter
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.LLMResponse
import pl.jclab.refio.core.llm.LLMUsage
import pl.jclab.refio.core.llm.ModelConfig
import pl.jclab.refio.core.llm.StreamChunk
import pl.jclab.refio.core.llm.toModelConfig
import pl.jclab.refio.core.services.ConfigService.Companion.DEFAULT_CONTEXT_SIZE
import pl.jclab.refio.core.utils.GsonInstance.gson
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.logging.Logger as KtorLogger
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.gson.*
import pl.jclab.refio.core.security.SecureLogger
import pl.jclab.refio.services.logging.dualLogger

/**
 * Adapter for Ollama local models.
 *
 * Provides integration with locally-running Ollama server (localhost:11434).
 * Uses Ktor HTTP client to call Ollama REST API.
 */
class OllamaAdapter(
    model: String = "qwen2.5:7b",
    baseUrlOverride: String? = null,
    private val configService: pl.jclab.refio.core.services.ConfigService? = null,
    private val taskId: String? = null,
    private val subtaskId: String? = null,
    private val source: String? = null
) : BaseLLMAdapter(model, "ollama") {

    private val logger = dualLogger("OllamaAdapter")
    private val baseUrl: String = baseUrlOverride?.takeIf { it.isNotBlank() }
        ?: configService?.getOllamaEndpoint()
        ?: System.getProperty("OLLAMA_BASE_URL")?.takeIf { it.isNotBlank() }
        ?: System.getProperty("OLLAMA_ENDPOINT")?.takeIf { it.isNotBlank() }
        ?: System.getenv("OLLAMA_BASE_URL")?.takeIf { it.isNotBlank() }
        ?: DEFAULT_BASE_URL

    companion object {
        const val DEFAULT_BASE_URL = "http://localhost:11434"
        const val CHAT_ENDPOINT = "/api/chat"
        const val TAGS_ENDPOINT = "/api/tags"
    }

    // Get timeout from ConfigService (fallback to 120s for Ollama local models)
    private val timeout: Long
        get() = configService?.getApiCallTimeoutMs(taskId)
            ?: pl.jclab.refio.core.services.ConfigService.DEFAULT_TOOL_EXECUTION_TIMEOUT * 1000L

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
                    this@OllamaAdapter.logger.debug { message }
                }
            }
            sanitizeHeader { header ->
                header.equals(HttpHeaders.Authorization, ignoreCase = true) ||
                    header.equals("x-api-key", ignoreCase = true) ||
                    header.equals("x-goog-api-key", ignoreCase = true)
            }
        }
        install(HttpTimeout) {
            // Get dynamic timeout from ConfigService
            val timeoutMs = configService?.getApiCallTimeoutMs(taskId)
                ?: pl.jclab.refio.core.services.ConfigService.DEFAULT_TOOL_EXECUTION_TIMEOUT * 1000L
            requestTimeoutMillis = timeoutMs
            connectTimeoutMillis = 30000
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
        logger.info { "[OLLAMA] Sending ${if (streaming) "streaming" else "standard"} chat request: model=$model, messages=${messages.size}, systemMessages=${systemMessages.size}" }

        return chatInternal(messages, systemMessages, maxTokens, temperature, streaming, onStreamChunk, kwargs)
    }

    private suspend fun chatInternal(
        messages: List<LLMMessage>,
        systemMessages: List<String>,
        maxTokens: Int?,
        temperature: Double,
        streaming: Boolean,
        onStreamChunk: ((StreamChunk) -> Unit)?,
        kwargs: Map<String, Any>
    ): LLMResponse {
        // Prepare messages
        val ollamaMessages = mutableListOf<Map<String, String>>()

        // Add system messages from systemMessages parameter
        systemMessages.filter { it.isNotBlank() }.forEach { sysMsg ->
            ollamaMessages.add(mapOf("role" to "system", "content" to sysMsg))
        }

        // Add conversation messages (filter out any system messages as they should be in systemMessages parameter)
        for (msg in messages.filter { it.role != "system" }) {
            ollamaMessages.add(mapOf("role" to msg.role, "content" to msg.content))
        }

        // Build request body
        val requestBody = buildMap {
            put("model", model)
            put("messages", ollamaMessages)
            put("stream", streaming)  // Enable streaming if requested

            // JSON mode for Ollama (if requested)
            val responseFormat = kwargs["response_format"] as? Map<*, *>
            if (responseFormat != null && responseFormat["type"] == "json_object") {
                put("format", "json")
                logger.info { "[OLLAMA] Enabled JSON mode" }
            }

            put("options", buildMap {
                put("temperature", temperature)
                // Use min of provided maxTokens and configured limit
                val maxOutputLimit = configService?.getMaxOutputTokens(taskId)
                    ?: pl.jclab.refio.core.services.ConfigService.DEFAULT_MAX_OUTPUT_SIZE
                val requestedMaxTokens = when {
                    maxTokens != null && maxTokens > 0 -> minOf(maxTokens, maxOutputLimit)
                    else -> maxOutputLimit
                }
                val modelLimit = pl.jclab.refio.core.llm.ModelDefinitions
                    .getDefinition("ollama", model)
                    ?.maxOutputTokens
                val effectiveMaxTokens = if (modelLimit != null && modelLimit > 0 && requestedMaxTokens > modelLimit) {
                    logger.warn {
                        "[OLLAMA] Requested num_predict=$requestedMaxTokens exceeds model limit ($modelLimit) for $model - clamping to safe value"
                    }
                    modelLimit
                } else {
                    requestedMaxTokens
                }
                put("num_predict", effectiveMaxTokens)
                logger.debug {
                    "[OLLAMA] Using maxTokens=$effectiveMaxTokens (requested=$maxTokens, configLimit=$maxOutputLimit, modelLimit=${modelLimit ?: "n/a"})"
                }
            })
        }

        val requestJson = gson.toJson(requestBody)
        logger.debug { "[OLLAMA] Request: ${SecureLogger.redact(requestJson)}" }

        val startTime = System.currentTimeMillis()

        return if (streaming && onStreamChunk != null) {
            // Streaming mode
            executeStreaming(requestBody, requestJson, startTime, onStreamChunk)
        } else {
            // Standard mode
            executeStandard(requestBody, requestJson, startTime)
        }
    }

    private suspend fun executeStandard(
        requestBody: Map<String, Any>,
        requestJson: String,
        startTime: Long
    ): LLMResponse {
        var httpStatus: Int? = null

        try {
            // Make HTTP request
            logger.info { "[OLLAMA] Request start: endpoint=$baseUrl$CHAT_ENDPOINT, body=${SecureLogger.redact(requestJson)}" }
            val httpResponse = client.post("$baseUrl$CHAT_ENDPOINT") {
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }

            httpStatus = httpResponse.status.value
            val response: Map<String, Any?> = httpResponse.body()

            val responseJson = gson.toJson(response)
            logger.debug { "[OLLAMA] Response: ${SecureLogger.redact(responseJson)}" }
            logger.info {
                "[OLLAMA] Response received: status=$httpStatus, durationMs=${System.currentTimeMillis() - startTime}, " +
                    "bodySize=${responseJson.length}"
            }

            // Check for error response
            if (httpStatus !in 200..299) {
                val latencyMs = (System.currentTimeMillis() - startTime).toInt()

                // Extract error details from Ollama error response
                val errorMessage = response["error"] as? String ?: "Unknown error"
                val fullErrorMessage = "Ollama API error (HTTP $httpStatus): $errorMessage"

                logger.error { "[OLLAMA] $fullErrorMessage" }

                // Log error to API logs
                logger.apiError(
                    provider = provider,
                    model = model,
                    endpoint = "$baseUrl$CHAT_ENDPOINT",
                    requestJson = requestJson,
                    httpStatus = httpStatus,
                    error = Exception(fullErrorMessage),
                    latencyMs = latencyMs,
                    taskId = taskId,
                    subtaskId = subtaskId,
                    source = source
                )

                throw IllegalStateException(fullErrorMessage)
            }

            val latencyMs = (System.currentTimeMillis() - startTime).toInt()

            // Extract usage (Ollama provides eval_count, prompt_eval_count)
            val evalCount = (response["eval_count"] as? Number)?.toInt() ?: 0
            val promptEvalCount = (response["prompt_eval_count"] as? Number)?.toInt() ?: 0

            val usage = LLMUsage(
                inputTokens = promptEvalCount,
                outputTokens = evalCount,
                totalTokens = promptEvalCount + evalCount
            )

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

            // Parse response
            @Suppress("UNCHECKED_CAST")
            val messageMap = response["message"] as? Map<String, Any?> ?: emptyMap()
            val content = messageMap["content"] as? String ?: ""

            val doneReason = response["done_reason"] as? String

            logger.info { "[OLLAMA] Response processed: tokens_in=${usage.inputTokens}, " +
                    "tokens_out=${usage.outputTokens}, done_reason=$doneReason" }

            return LLMResponse(
                content = content,
                usage = usage,
                model = model,
                provider = provider,
                cost = 0.0,  // Free local execution
                finishReason = doneReason,
                rawResponse = response
            )

        } catch (e: Exception) {
            // Error #15: Log error (console + database)
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
        requestBody: Map<String, Any>,
        requestJson: String,
        startTime: Long,
        onStreamChunk: (StreamChunk) -> Unit
    ): LLMResponse {
        val contentBuilder = StringBuilder()
        var inputTokens = 0
        var outputTokens = 0
        var httpStatus: Int? = null
        var finalDoneReason: String? = null

        try {
            // Make streaming HTTP request
            logger.info { "[OLLAMA] Request start: endpoint=$baseUrl$CHAT_ENDPOINT, body=${SecureLogger.redact(requestJson)}" }
            client.preparePost("$baseUrl$CHAT_ENDPOINT") {
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }.execute { httpResponse ->
                httpStatus = httpResponse.status.value

                // Check for error response before reading stream
                if (httpStatus !in 200..299) {
                    val errorBody = httpResponse.body<String>()
                    val latencyMs = (System.currentTimeMillis() - startTime).toInt()

                    val errorMessage = "Ollama API error (HTTP $httpStatus): $errorBody"
                    logger.error { "[OLLAMA] $errorMessage" }

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

                // Read NDJSON stream line by line
                while (!channel.isClosedForRead) {
                    // Check cancellation - break to return partial response
                    if (pl.jclab.refio.core.services.monitoring.GlobalMetrics.isCancelled()) {
                        logger.info { "[OLLAMA] Streaming cancelled by user - returning partial response" }
                        finalDoneReason = "cancelled"
                        break
                    }

                    val line = channel.readUTF8Line(limit = Int.MAX_VALUE) ?: continue
                    if (line.isBlank()) continue

                    try {
                        @Suppress("UNCHECKED_CAST")
                        val chunk = gson.fromJson(line, Map::class.java) as Map<String, Any?>

                        // Extract message content
                        @Suppress("UNCHECKED_CAST")
                        val message = chunk["message"] as? Map<String, Any?>
                        val content = message?.get("content") as? String

                        // Emit content chunk
                        if (content != null && content.isNotEmpty()) {
                            contentBuilder.append(content)
                            onStreamChunk(StreamChunk(
                                delta = content,
                                finishReason = null
                            ))
                        }

                        // Check if done
                        val done = chunk["done"] as? Boolean ?: false
                        if (done) {
                            // Extract usage from final chunk
                            inputTokens = (chunk["prompt_eval_count"] as? Number)?.toInt() ?: 0
                            outputTokens = (chunk["eval_count"] as? Number)?.toInt() ?: 0
                            finalDoneReason = chunk["done_reason"] as? String

                            logger.debug { "[OLLAMA] Stream complete: input=$inputTokens, output=$outputTokens" }
                            break
                        }
                    } catch (e: Exception) {
                        logger.warn { "[OLLAMA] Failed to parse chunk: $line - ${e.message}" }
                        continue
                    }
                }
            }

            val latencyMs = (System.currentTimeMillis() - startTime).toInt()

            val usage = LLMUsage(
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                totalTokens = inputTokens + outputTokens
            )

            // Emit final chunk with usage
            onStreamChunk(StreamChunk(
                delta = "",
                finishReason = finalDoneReason,
                usage = usage
            ))

            // Create synthetic response JSON for logging
            val syntheticResponse = mapOf(
                "message" to mapOf("role" to "assistant", "content" to contentBuilder.toString()),
                "done" to true,
                "done_reason" to finalDoneReason,
                "prompt_eval_count" to inputTokens,
                "eval_count" to outputTokens,
                "model" to model
            )
            val responseJson = gson.toJson(syntheticResponse)
            logger.info {
                "[OLLAMA] Response received: status=${httpStatus ?: 200}, durationMs=${System.currentTimeMillis() - startTime}, " +
                    "bodySize=${responseJson.length}"
            }

            // Log successful stream completion to API logs
            logger.apiResponse(
                provider = provider,
                model = model,
                endpoint = "$baseUrl$CHAT_ENDPOINT",
                requestJson = requestJson,
                responseJson = responseJson,
                httpStatus = httpStatus ?: 200,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                costUsd = 0.0,
                latencyMs = latencyMs,
                taskId = taskId,
                subtaskId = subtaskId,
                source = source
            )

            logger.info { "[OLLAMA] Streaming completed in ${latencyMs}ms, tokens=$inputTokens/$outputTokens" }

            return LLMResponse(
                content = contentBuilder.toString(),
                usage = usage,
                model = model,
                provider = provider,
                cost = 0.0,  // Free local execution
                finishReason = finalDoneReason,
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

    override fun estimateCost(usage: LLMUsage): Double {
        // Ollama is free (local execution)
        return 0.0
    }

    /**
     * Lists all available models from Ollama /api/tags endpoint.
     *
     * @return List of ModelConfig objects with model metadata
     * @throws Exception if connection fails or response is invalid
     */
    suspend fun listModels(): List<ModelConfig> {
        logger.info { "[OLLAMA] Fetching available models from $baseUrl$TAGS_ENDPOINT" }

        try {
            val httpResponse = client.get("$baseUrl$TAGS_ENDPOINT")
            val response: Map<String, Any?> = httpResponse.body()

            @Suppress("UNCHECKED_CAST")
            val modelsData = response["models"] as? List<Map<String, Any?>> ?: emptyList()

            if (modelsData.isEmpty()) {
                logger.warn { "[OLLAMA] No models found. Is Ollama running?" }
                return emptyList()
            }

            // Get context size from ConfigService (global setting for all Ollama models)
            val contextSize = configService?.get(pl.jclab.refio.core.services.ConfigService.KEY_PROVIDER_OLLAMA_CONTEXT_SIZE)?.toIntOrNull()
                ?: DEFAULT_CONTEXT_SIZE

            logger.info { "[OLLAMA] Using context size: $contextSize tokens (from config)" }

            val modelConfigs = modelsData.mapNotNull { modelData ->
                val modelName = modelData["name"] as? String ?: return@mapNotNull null

                // Get definition from registry or create fallback with configured context size
                val definition = pl.jclab.refio.core.llm.ModelDefinitions.getDefinition("ollama", modelName)
                    ?: pl.jclab.refio.core.llm.ModelDefinitions.createFallback(
                        provider = "ollama",
                        modelId = modelName,
                        maxContext = contextSize  // Use configured context size
                    )

                logger.debug { "[OLLAMA] Found model: $modelName (context: ${definition.maxContext})" }

                definition.toModelConfig()
            }

            logger.info { "[OLLAMA] Found ${modelConfigs.size} models" }
            return modelConfigs

        } catch (e: Exception) {
            logger.error(e) { "[OLLAMA] Failed to fetch models: ${e.message}" }
            throw Exception("Failed to fetch Ollama models. Is Ollama running at $baseUrl?", e)
        }
    }

    override suspend fun close() {
        client.close()
    }
}
