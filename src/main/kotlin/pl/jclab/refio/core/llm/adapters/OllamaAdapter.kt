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
import pl.jclab.refio.core.errors.LLMErrorMapper
import pl.jclab.refio.core.security.SecureLogger
import pl.jclab.refio.core.services.OllamaRequestGate
import pl.jclab.refio.services.logging.dualLogger
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.jclab.refio.core.services.ConfigService

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
        get() = configService?.getApiCallTimeoutMs(taskId) ?: ConfigService.DEFAULT_TOOL_EXECUTION_TIMEOUT * 1000L

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
                ?: ConfigService.DEFAULT_TOOL_EXECUTION_TIMEOUT * 1000L
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

        return try {
            chatInternal(messages, systemMessages, maxTokens, temperature, streaming, onStreamChunk, kwargs)
        } catch (e: Exception) {
            throw LLMErrorMapper.fromThrowable(provider, model, timeout, e)
        }
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

        val responseFormat = kwargs["response_format"] as? Map<*, *>
        val jsonMode = responseFormat?.get("type") == "json_object"
        val thinkingRequested = kwargs["thinking"] as? Boolean ?: false

        // Build request body
        val requestBody = buildMap {
            put("model", model)
            put("messages", ollamaMessages)
            put("stream", streaming)  // Enable streaming if requested

            // Keep model in GPU memory to avoid loading delays
            // Get from config (default: 1800 seconds = 30 minutes)
            val keepAlive = configService?.getOllamaKeepAlive() ?: 1800
            put("keep_alive", keepAlive)

            // JSON mode for Ollama (if requested)
            if (jsonMode) {
                put("format", "json")
                logger.info { "[OLLAMA] Enabled JSON mode" }
            } else if (thinkingRequested) {
                // Enable thinking mode for reasoning models (e.g., deepseek-r1)
                put("think", true)
                logger.info { "[OLLAMA] Enabled thinking mode for $model" }
            }

            put("options", buildMap {
                put("temperature", temperature)

                // Get configured context size for Ollama
                val contextSize = configService?.get(ConfigService.KEY_PROVIDER_OLLAMA_CONTEXT_SIZE)?.toIntOrNull()
                    ?: DEFAULT_CONTEXT_SIZE
                put("num_ctx", contextSize)

                // Use min of provided maxTokens and configured limit
                val maxOutputLimit = configService?.getMaxOutputTokens(taskId) ?: ConfigService.DEFAULT_MAX_OUTPUT_SIZE
                val requestedMaxTokens = when {
                    maxTokens != null && maxTokens > 0 -> minOf(maxTokens, maxOutputLimit)
                    else -> maxOutputLimit
                }
                val modelLimit =
                    pl.jclab.refio.core.llm.ModelDefinitions.getDefinition("ollama", model)?.maxOutputTokens
                val effectiveMaxTokens = if (modelLimit != null && modelLimit > 0 && requestedMaxTokens > modelLimit) {
                    logger.warn {
                        "[OLLAMA] Requested num_predict=$requestedMaxTokens exceeds model limit ($modelLimit) for $model - clamping to safe value"
                    }
                    modelLimit
                } else {
                    requestedMaxTokens
                }
                put("num_predict", effectiveMaxTokens)
                logger.info {
                    "[OLLAMA] Using maxTokens=$effectiveMaxTokens, context=$contextSize, temp=$temperature " +
                        "(requested=$maxTokens, configLimit=$maxOutputLimit, modelLimit=${modelLimit ?: "n/a"})"
                }
            })
        }

        val requestJson = gson.toJson(requestBody)
        val requestId = UUID.randomUUID().toString()
        val logPrefix = "[OLLAMA][$requestId]"
        logger.debug { "$logPrefix Request: ${SecureLogger.redactAndTruncate(requestJson)}" }

        val startTime = System.currentTimeMillis()

        val forceNonStreamingForJsonMode = jsonMode && streaming && onStreamChunk != null

        if (forceNonStreamingForJsonMode) {
            logger.info {
                "[OLLAMA] JSON mode with streaming requested - using non-streaming request to avoid Ollama EOF on some models"
            }
            // Force stream=false for fallback standard request.
            // Otherwise Ollama may still return NDJSON (application/x-ndjson).
            val nonStreamingRequestBody = requestBody.toMutableMap().apply {
                this["stream"] = false
            }
            val nonStreamingRequestJson = gson.toJson(nonStreamingRequestBody)
            val response = executeStandard(nonStreamingRequestBody, nonStreamingRequestJson, startTime, logPrefix)

            // Preserve streaming UI behavior by emitting a single accumulated chunk + final usage chunk.
            if (response.content.isNotEmpty()) {
                onStreamChunk?.invoke(
                    StreamChunk(
                        delta = response.content,
                        finishReason = null
                    )
                )
            }
            onStreamChunk?.invoke(
                StreamChunk(
                    delta = "",
                    finishReason = response.finishReason,
                    usage = response.usage
                )
            )
            return response
        }

        return if (streaming && onStreamChunk != null) {
            // Streaming mode
            executeStreaming(requestBody, requestJson, startTime, onStreamChunk, logPrefix)
        } else {
            // Standard mode
            executeStandard(requestBody, requestJson, startTime, logPrefix)
        }
    }

    private suspend fun executeStandard(
        requestBody: Map<String, Any>,
        requestJson: String,
        startTime: Long,
        logPrefix: String
    ): LLMResponse {
        var httpStatus: Int? = null

        try {
            // Make HTTP request
            logger.info {
                "$logPrefix Request start: endpoint=$baseUrl$CHAT_ENDPOINT, body=${
                    SecureLogger.redactAndTruncate(
                        requestJson
                    )
                }"
            }
            val httpResponse = OllamaRequestGate.withPermit(baseUrl) {
                client.post("$baseUrl$CHAT_ENDPOINT") {
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }
            }

            httpStatus = httpResponse.status.value
            val response: Map<String, Any?> = httpResponse.body()

            val responseJson = gson.toJson(response)
            logger.debug { "$logPrefix Response: ${SecureLogger.redactAndTruncate(responseJson)}" }
            logger.info {
                "$logPrefix Response received: status=$httpStatus, durationMs=${System.currentTimeMillis() - startTime}, " +
                        "bodySize=${responseJson.length}"
            }

            // Check for error response
            if (httpStatus !in 200..299) {
                val latencyMs = (System.currentTimeMillis() - startTime).toInt()

                // Extract error details from Ollama error response
                val errorMessage = response["error"] as? String ?: "Unknown error"
                val fullErrorMessage = "Ollama API error (HTTP $httpStatus): $errorMessage"

                logger.error { "$logPrefix $fullErrorMessage" }

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

                throw LLMErrorMapper.fromHttpStatus(provider, model, httpStatus, fullErrorMessage)
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
            var rawContent = messageMap["content"] as? String ?: ""
            val rawThinking = messageMap["thinking"] as? String  // Reasoning models (gpt-oss)
            val rawToolCalls = extractOllamaToolCalls(messageMap)

            if (rawContent.isBlank() && rawToolCalls.isNotEmpty()) {
                rawContent = convertToolCallsToCanonicalJson(rawToolCalls)
                logger.info {
                    "$logPrefix [TOOL_CALLS_NORMALIZED] Converted Ollama message.tool_calls to canonical JSON content " +
                        "(count=${rawToolCalls.size}, contentLength=${rawContent.length})"
                }
            }

            // Debug: Log thinking content for gpt-oss models
            if (!rawThinking.isNullOrEmpty()) {
                logger.info { "$logPrefix [THINKING_DEBUG] Raw thinking preview (150 chars): ${rawThinking.take(150)}" }
                logger.info { "$logPrefix [THINKING_DEBUG] Raw thinking length: ${rawThinking.length}" }
                logger.info { "$logPrefix [THINKING_DEBUG] Raw content length: ${rawContent.length}" }
            }

            // Process thinking field to extract actual content and thinking
            val (finalContent, finalThinking) = processThinkingField(rawContent, rawThinking)

            logger.info {
                "$logPrefix [CONTENT_DEBUG] Final content length: ${finalContent.length}, " +
                "final thinking length: ${finalThinking?.length ?: 0}, " +
                "content starts with JSON: ${finalContent.trim().startsWith("{")}"
            }

            val doneReason = response["done_reason"] as? String

            logger.info {
                "$logPrefix Response processed: tokens_in=${usage.inputTokens}, " +
                        "tokens_out=${usage.outputTokens}, done_reason=$doneReason" +
                        ", content=${finalContent.length}, thinking=${finalThinking?.length ?: 0}"
            }

            return LLMResponse(
                content = finalContent,
                usage = usage,
                model = model,
                provider = provider,
                cost = 0.0,  // Free local execution
                finishReason = doneReason,
                rawResponse = response,
                thinking = finalThinking
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

            throw LLMErrorMapper.fromThrowable(provider, model, timeout, e)
        }
    }

    /**
     * Execute streaming request with automatic retry on model loading.
     * When done_reason="load", the model is still loading into GPU memory.
     * We automatically retry with exponential backoff.
     */
    private suspend fun executeStreaming(
        requestBody: Map<String, Any>,
        requestJson: String,
        startTime: Long,
        onStreamChunk: (StreamChunk) -> Unit,
        logPrefix: String
    ): LLMResponse {
        val rawContentBuilder = StringBuilder()
        val rawThinkingBuilder = StringBuilder()
        val rawToolCalls = mutableListOf<Map<String, Any?>>()
        var inputTokens = 0
        var outputTokens = 0
        var httpStatus: Int? = null
        var finalDoneReason: String? = null
        var receivedDoneChunk = false
        var lastRawChunkLine: String? = null

        try {
            // Make streaming HTTP request
            logger.info {
                "$logPrefix Request start: endpoint=$baseUrl$CHAT_ENDPOINT, body=${
                    SecureLogger.redactAndTruncate(
                        requestJson
                    )
                }"
            }
            OllamaRequestGate.withPermit(baseUrl) {
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
                    logger.error { "$logPrefix $errorMessage" }

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

                    throw LLMErrorMapper.fromHttpStatus(provider, model, httpStatus ?: 500, errorMessage)
                }

                val channel: io.ktor.utils.io.ByteReadChannel = httpResponse.body()

                // Read NDJSON stream line by line
                var chunkCount = 0
                while (!channel.isClosedForRead) {
                    // Check cancellation - break to return partial response
                    if (pl.jclab.refio.core.services.monitoring.GlobalMetrics.isCancelled()) {
                        logger.info { "$logPrefix Streaming cancelled by user - returning partial response" }
                        finalDoneReason = "cancelled"
                        break
                    }

                    val line = channel.readUTF8Line(limit = Int.MAX_VALUE) ?: continue
                    if (line.isBlank()) continue
                    lastRawChunkLine = line

                    chunkCount++
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val chunk = gson.fromJson(line, Map::class.java) as Map<String, Any?>

                        // Debug: Log first chunk structure when using JSON mode
                        if (chunkCount == 1) {
                            logger.info { "$logPrefix First chunk keys: ${chunk.keys.joinToString()}, has_message=${chunk.containsKey("message")}" }
                            val msg = chunk["message"] as? Map<*, *>
                            if (msg != null) {
                                logger.info { "$logPrefix Message keys: ${msg.keys.joinToString()}, has_content=${msg.containsKey("content")}, has_thinking=${msg.containsKey("thinking")}" }
                                // Log first 100 chars of thinking to debug gpt-oss models
                                val firstThinking = msg["thinking"] as? String
                                if (!firstThinking.isNullOrEmpty()) {
                                    logger.info { "$logPrefix First thinking preview (100 chars): ${firstThinking.take(100)}" }
                                    logger.info { "$logPrefix First thinking starts with JSON: ${firstThinking.trim().startsWith("{")}" }
                                }
                            }
                        }

                        // Extract message content
                        @Suppress("UNCHECKED_CAST")
                        val message = chunk["message"] as? Map<String, Any?>
                        val content = message?.get("content") as? String ?: ""
                        val thinking = message?.get("thinking") as? String ?: ""  // Reasoning field
                        if (message != null) {
                            val toolCalls = extractOllamaToolCalls(message)
                            if (toolCalls.isNotEmpty()) {
                                rawToolCalls.clear()
                                rawToolCalls.addAll(toolCalls)
                            }
                        }

                        // Debug: Log first chunk structure
                        if (chunkCount == 1) {
                            logger.info { "$logPrefix First chunk keys: ${chunk.keys.joinToString()}, has_message=${chunk.containsKey("message")}" }
                            if (message != null) {
                                logger.info { "$logPrefix Message keys: ${message.keys.joinToString()}, has_content=${message.containsKey("content")}, has_thinking=${message.containsKey("thinking")}" }
                                if (thinking.isNotEmpty()) {
                                    logger.info { "$logPrefix First thinking preview (150 chars): ${thinking.take(150)}" }
                                }
                            }
                        }

                        // Collect raw content and thinking (will be processed after stream completes)
                        if (content.isNotEmpty()) {
                            rawContentBuilder.append(content)
                            // For UI streaming, emit content as-is during streaming
                            onStreamChunk(
                                StreamChunk(
                                    delta = content,
                                    finishReason = null
                                )
                            )
                        }

                        // Also collect and emit thinking for UI display
                        if (thinking.isNotEmpty()) {
                            rawThinkingBuilder.append(thinking)
                            onStreamChunk(
                                StreamChunk(
                                    delta = "",  // No content delta
                                    thinking = thinking,  // Thinking delta
                                    finishReason = null
                                )
                            )
                        }

                        // Check if done
                        val done = chunk["done"] as? Boolean ?: false
                        if (done) {
                            receivedDoneChunk = true
                            // Extract usage from final chunk
                            inputTokens = (chunk["prompt_eval_count"] as? Number)?.toInt() ?: 0
                            outputTokens = (chunk["eval_count"] as? Number)?.toInt() ?: 0
                            finalDoneReason = chunk["done_reason"] as? String

                            logger.info {
                                "$logPrefix Stream complete: input=$inputTokens, output=$outputTokens, " +
                                "rawContent=${rawContentBuilder.length}, rawThinking=${rawThinkingBuilder.length}"
                            }
                            break
                        }
                    } catch (e: Exception) {
                        logger.warn { "$logPrefix Failed to parse chunk : ${line.take(100)} - ${e.message}" }
                        continue
                    }
                }
                }
            }

            if (!receivedDoneChunk && finalDoneReason != "cancelled") {
                throw LLMErrorMapper.fromThrowable(
                    provider,
                    model,
                    timeout,
                    IllegalStateException("Ollama stream ended before done=true final chunk")
                )
            }

            val latencyMs = (System.currentTimeMillis() - startTime).toInt()

            val usage = LLMUsage(
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                totalTokens = inputTokens + outputTokens
            )

            // Emit final chunk with usage
            onStreamChunk(
                StreamChunk(
                    delta = "",
                    finishReason = finalDoneReason,
                    usage = usage
                )
            )

            // Create synthetic response JSON for logging (use raw content at this point)
            val syntheticResponse = mapOf(
                "message" to mapOf("role" to "assistant", "content" to rawContentBuilder.toString()),
                "done" to true,
                "done_reason" to finalDoneReason,
                "prompt_eval_count" to inputTokens,
                "eval_count" to outputTokens,
                "model" to model
            )
            val responseJson = gson.toJson(syntheticResponse)
            logger.info {
                "$logPrefix Response received: status=${httpStatus ?: 200}, durationMs=${System.currentTimeMillis() - startTime}, " +
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
                source = source,
                rawApiResponseChunk = lastRawChunkLine
            )

            logger.info { "$logPrefix Streaming completed in ${latencyMs}ms, tokens=$inputTokens/$outputTokens" }

            // Process raw thinking field to extract actual content (post-processing)
            var rawContent = rawContentBuilder.toString()
            val rawThinking = rawThinkingBuilder.takeIf { it.isNotEmpty() }?.toString()

            if (rawContent.isBlank() && rawToolCalls.isNotEmpty()) {
                rawContent = convertToolCallsToCanonicalJson(rawToolCalls)
                logger.info {
                    "$logPrefix [TOOL_CALLS_NORMALIZED] Converted Ollama streamed message.tool_calls to canonical JSON content " +
                        "(count=${rawToolCalls.size}, contentLength=${rawContent.length})"
                }
            }

            logger.info {
                "$logPrefix [STREAM_CONTENT_DEBUG] Raw content length: ${rawContent.length}, " +
                "raw thinking length: ${rawThinking?.length ?: 0}"
            }

            val (finalContent, finalThinking) = processThinkingField(rawContent, rawThinking)

            logger.info {
                "$logPrefix [STREAM_CONTENT_DEBUG] Final content length: ${finalContent.length}, " +
                "final thinking length: ${finalThinking?.length ?: 0}, " +
                "content starts with JSON: ${finalContent.trim().startsWith("{")}"
            }

            return LLMResponse(
                content = finalContent,
                usage = usage,
                model = model,
                provider = provider,
                cost = 0.0,  // Free local execution
                finishReason = finalDoneReason,
                rawResponse = syntheticResponse,
                thinking = finalThinking
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
            throw LLMErrorMapper.fromThrowable(provider, model, timeout, e)
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
    suspend fun listModels(): List<ModelConfig> = withContext(Dispatchers.IO) {
        logger.info { "[OLLAMA] Fetching available models from $baseUrl$TAGS_ENDPOINT" }

        try {
            val httpResponse = OllamaRequestGate.withPermit(baseUrl) {
                client.get("$baseUrl$TAGS_ENDPOINT")
            }
            val response: Map<String, Any?> = httpResponse.body()

            @Suppress("UNCHECKED_CAST")
            val modelsData = response["models"] as? List<Map<String, Any?>> ?: emptyList()

            if (modelsData.isEmpty()) {
                logger.warn { "[OLLAMA] No models found. Is Ollama running?" }
                return@withContext emptyList()
            }

            // Get context size from ConfigService (global setting for all Ollama models)
            val contextSize =
                configService?.get(pl.jclab.refio.core.services.ConfigService.KEY_PROVIDER_OLLAMA_CONTEXT_SIZE)
                    ?.toIntOrNull()
                    ?: DEFAULT_CONTEXT_SIZE

            logger.info { "[OLLAMA] Using context size: $contextSize tokens (from config)" }

            val modelConfigs = modelsData.mapNotNull { modelData ->
                val modelName = modelData["name"] as? String ?: return@mapNotNull null

                // Get definition from registry or create fallback with configured context size
                val baseDefinition = pl.jclab.refio.core.llm.ModelDefinitions.getDefinition("ollama", modelName)
                    ?: pl.jclab.refio.core.llm.ModelDefinitions.createFallback(
                        provider = "ollama",
                        modelId = modelName,
                        maxContext = contextSize  // Use configured context size
                    )

                // Always override maxContext with configured value for Ollama models
                val definition = baseDefinition.copy(maxContext = contextSize)

                logger.debug { "[OLLAMA] Found model: $modelName (context: ${definition.maxContext})" }

                definition.toModelConfig()
            }

            logger.info { "[OLLAMA] Found ${modelConfigs.size} models" }
            return@withContext modelConfigs

        } catch (e: Exception) {
            logger.error(e) { "[OLLAMA] Failed to fetch models: ${e.message}" }
            throw LLMErrorMapper.listModelsFailure(provider, e)
        }
    }

    /**
     * Keep content and thinking separated.
     * We do not copy thinking into content - JSON/tool parsing is driven by content only.
     *
     * @return Pair of (content, thinking)
     */
    private fun processThinkingField(rawContent: String, rawThinking: String?): Pair<String, String?> {
        // If we have content, use it directly and keep thinking separate
        if (rawContent.isNotBlank()) {
            return Pair(rawContent, rawThinking?.takeIf { it.isNotBlank() })
        }

        // Content is empty, keep thinking separate (don't duplicate it into content)
        if (rawThinking.isNullOrBlank()) {
            return Pair("", null)
        }

        // Keep fields separate - content is empty, thinking has the value
        return Pair("", rawThinking)
    }

    private fun extractOllamaToolCalls(messageMap: Map<String, Any?>): List<Map<String, Any?>> {
        @Suppress("UNCHECKED_CAST")
        val toolCalls = messageMap["tool_calls"] as? List<Map<String, Any?>> ?: return emptyList()
        return toolCalls
    }

    private fun convertToolCallsToCanonicalJson(toolCalls: List<Map<String, Any?>>): String {
        val actions = toolCalls.mapNotNull { toolCall ->
            @Suppress("UNCHECKED_CAST")
            val function = toolCall["function"] as? Map<String, Any?> ?: return@mapNotNull null

            val rawName = function["name"] as? String ?: return@mapNotNull null
            val normalizedName = normalizeToolName(rawName)
            val arguments = normalizeToolArguments(function["arguments"])

            mapOf(
                "tool" to normalizedName,
                "arguments" to arguments
            )
        }

        val canonical = mapOf(
            "actions" to actions,
            "response" to ""
        )
        return gson.toJson(canonical)
    }

    private fun normalizeToolName(rawName: String): String {
        val byDot = rawName.substringAfterLast('.')
        return byDot.substringAfterLast('/')
    }

    private fun normalizeToolArguments(rawArguments: Any?): Any {
        return when (rawArguments) {
            null -> emptyMap<String, Any?>()
            is Map<*, *> -> rawArguments.entries.associate { (k, v) -> k.toString() to v }
            is String -> {
                try {
                    @Suppress("UNCHECKED_CAST")
                    gson.fromJson(rawArguments, Map::class.java) as? Map<String, Any?> ?: emptyMap<String, Any?>()
                } catch (_: Exception) {
                    mapOf("raw" to rawArguments)
                }
            }
            else -> mapOf("value" to rawArguments.toString())
        }
    }

    override suspend fun close() {
        client.close()
    }
}
