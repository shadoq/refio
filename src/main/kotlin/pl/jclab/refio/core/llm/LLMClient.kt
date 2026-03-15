package pl.jclab.refio.core.llm

import pl.jclab.refio.core.api.StreamCallback
import pl.jclab.refio.core.api.StreamChunk
import pl.jclab.refio.core.errors.RefioError
import pl.jclab.refio.core.llm.adapters.AnthropicAdapter
import pl.jclab.refio.core.llm.adapters.CustomOpenAIAdapter
import pl.jclab.refio.core.llm.adapters.GeminiAdapter
import pl.jclab.refio.core.llm.adapters.LMStudioAdapter
import pl.jclab.refio.core.llm.adapters.OllamaAdapter
import pl.jclab.refio.core.llm.adapters.OpenAIAdapter
import pl.jclab.refio.core.llm.adapters.OpenRouterAdapter
import pl.jclab.refio.core.llm.adapters.ZAIAdapter
import pl.jclab.refio.core.services.logging.coreLogger
import pl.jclab.refio.core.services.monitoring.GlobalMetrics

private val logger = coreLogger("LLMClient")

/**
 * Unified LLM client that automatically selects the appropriate adapter.
 *
 * Example:
 * ```
 * val client = LLMClient(configService)
 *
 * // Use Ollama (local)
 * val response = client.complete(
 *     provider = "ollama",
 *     model = "qwen2.5:7b",
 *     messages = listOf(LLMMessage(role = "user", content = "Hello")),
 *     taskId = "task-123",
 *     apiLogRepository = apiLogRepo
 * )
 *
 * // Use OpenAI
 * val response = client.complete(
 *     provider = "openai",
 *     model = "gpt-4o-mini",
 *     messages = listOf(LLMMessage(role = "user", content = "Hello"))
 * )
 *
 * // Use Anthropic
 * val response = client.complete(
 *     provider = "anthropic",
 *     model = "claude-3-5-sonnet-20241022",
 *     messages = listOf(LLMMessage(role = "user", content = "Hello"))
 * )
 * ```
 */
class LLMClient(
    private val configService: pl.jclab.refio.core.services.ConfigService? = null
) {
    // Bug #18: Load HTTP client configuration from database
    private val httpClientConfig: HttpClientConfig by lazy {
        HttpClientConfig.fromConfigService(configService)
    }

    data class PreparedRequestPayload(
        val systemMessages: List<String>,
        val messages: List<LLMMessage>,
        val estimatedInputTokens: Int
    )

    companion object {
        fun prepareRequestPayload(
            messages: List<LLMMessage>,
            systemPrompt: String? = null,
            contextContent: String? = null,
            systemMessages: List<String> = emptyList()
        ): PreparedRequestPayload {
            val allSystemMessages = buildList {
                addAll(systemMessages.filter { it.isNotBlank() })
                if (!systemPrompt.isNullOrBlank()) add(systemPrompt)
            }

            val finalMessages = injectContextAsUserMessage(messages, contextContent)
            val estimatedTokens = TokenEstimator.estimateRequestTokens(
                messages = finalMessages,
                systemPrompt = null,
                systemMessages = allSystemMessages
            )

            return PreparedRequestPayload(
                systemMessages = allSystemMessages,
                messages = finalMessages,
                estimatedInputTokens = estimatedTokens
            )
        }

        private fun injectContextAsUserMessage(
            messages: List<LLMMessage>,
            contextContent: String?
        ): List<LLMMessage> {
            if (contextContent.isNullOrBlank()) return messages

            val lastUserIndex = messages.indexOfLast { it.role == "user" }
            if (lastUserIndex >= 0) {
                val mutableMessages = messages.toMutableList()
                mutableMessages.add(lastUserIndex, LLMMessage(role = "user", content = contextContent))
                return mutableMessages.toList()
            }

            return messages + LLMMessage(role = "user", content = contextContent)
        }
    }
    /**
     * Send completion request to LLM provider (RFC 0032: Unified streaming).
     *
     * This is the SINGLE entry point for all LLM requests. Supports both streaming
     * and non-streaming modes with identical response handling.
     *
     * @param provider Provider name (ollama, openai, anthropic, openrouter)
     * @param model Model identifier
     * @param messages Conversation messages
     * @param systemPrompt Optional system prompt
     * @param maxTokens Maximum tokens to generate
     * @param temperature Sampling temperature
     * @param responseFormat Response format specification (OpenAI/OpenRouter only)
     * @param thinking Enable thinking mode (Anthropic Claude 3.5+ only)
     * @param noEgressEnabled If true, blocks external network calls (US-006)
     * @param stream If true, streams response and calls onChunk callback
     * @param onChunk Callback for streaming chunks (only used when stream=true)
     * @param taskId For API logging
     * @param subtaskId For API logging
     * @param source Source of request: "Chat", "StepPlanner", "ToolSelector", etc.
     * @param contextContent Optional context content to inject as [user] message before last user prompt
     * @param kwargs Additional provider-specific parameters
     * @return LLMResponse with generated text and metadata (ALWAYS returned, regardless of streaming)
     * @throws IllegalArgumentException If provider is unknown
     * @throws NoEgressViolationException If no-egress is enabled and cloud provider is used
     * @throws Exception On API errors
     */
    suspend fun complete(
        provider: String,
        model: String,
        messages: List<LLMMessage>,
        systemPrompt: String? = null,
        maxTokens: Int? = null,
        temperature: Double = 0.7,
        responseFormat: Map<String, Any>? = null,
        thinking: Boolean = false,
        noEgressEnabled: Boolean = false,
        stream: Boolean = false,
        onChunk: StreamCallback? = null,
        taskId: String? = null,
        subtaskId: String? = null,
        source: String? = null,
        contextContent: String? = null,
        systemMessages: List<String> = emptyList(),
        kwargs: Map<String, Any> = emptyMap()
    ): LLMResponse {
        val preparedRequest = prepareRequestPayload(
            messages = messages,
            systemPrompt = systemPrompt,
            contextContent = contextContent,
            systemMessages = systemMessages
        )
        val allSystemMessages = preparedRequest.systemMessages

        logger.info {
            "[LLM_CLIENT] Starting request to: $provider/$model " +
                    "messages: ${messages.size} systemMessages: ${allSystemMessages.size} " +
                    "max_tokens: $maxTokens temperature: $temperature stream: $stream " +
                    "response_format: $responseFormat thinking: $thinking noEgress: $noEgressEnabled"
        }

        // US-006: No-egress enforcement
        if (noEgressEnabled) {
            val cloudProviders = listOf("openai", "anthropic", "openrouter", "gemini", "zai")
            if (provider.lowercase() in cloudProviders) {
                logger.warn {
                    "[LLM_CLIENT] No-egress violation blocked: attempted to use cloud provider '$provider' " +
                    "with model '$model' while no-egress mode is enabled"
                }
                throw NoEgressViolationException(
                    "No-egress mode is enabled. Cannot use cloud provider: $provider. " +
                    "Only local providers (ollama) are allowed."
                )
            }
        }

        // Context size validation - estimate tokens before sending
        // Include contextContent in token estimation
        val baseEstimatedTokens = TokenEstimator.estimateRequestTokens(messages, systemPrompt, systemMessages)
        val contextTokens = contextContent?.let { TokenEstimator.estimateTokens(it) + 10 } ?: 0  // +10 for message overhead
        val estimatedTokens = preparedRequest.estimatedInputTokens

        val maxContext = TokenEstimator.getMaxContextForModel(model, provider, configService)
        // Reserve for output: use requested maxTokens, but cap at 50% of context to ensure input space
        val requestedReserve = maxTokens ?: 4096
        val reserveForOutput = minOf(requestedReserve, maxContext / 2)
        val availableForInput = maxContext - reserveForOutput

        logger.info {
            "[LLM_CLIENT] Token estimate: ~$estimatedTokens tokens (base: $baseEstimatedTokens, context: $contextTokens), " +
            "model max context: $maxContext, available for input: $availableForInput (reserving $reserveForOutput for output)"
        }

        if (estimatedTokens > availableForInput) {
            logger.error {
                "[LLM_CLIENT] Context too large: estimated $estimatedTokens tokens exceeds available " +
                "$availableForInput tokens (model max: $maxContext, output reserve: $reserveForOutput)"
            }
            throw ContextTooLargeException(
                estimatedTokens = estimatedTokens,
                maxContextTokens = maxContext,
                availableTokens = availableForInput
            )
        }

        // Log system messages
        allSystemMessages.forEachIndexed { i, sysMsg ->
            val sysPreview = if (sysMsg.length > 200) sysMsg.substring(0, 200) + "..." else sysMsg
            logger.debug { "[LLM_CLIENT] System message #${i+1}: ${sysMsg.length} chars, preview=$sysPreview" }
        }

        // Log message contents (truncated for readability)
        messages.forEachIndexed { i, msg ->
            val contentPreview = if (msg.content.length > 200) {
                msg.content.substring(0, 200) + "..."
            } else {
                msg.content
            }
            logger.debug { "[LLM_CLIENT] Message $i: role=${msg.role}, content=$contentPreview" }
        }

        logger.debug {
            "[LLM_CLIENT] Using timeout configuration: request=${httpClientConfig.requestTimeoutMs}ms, " +
            "connect=${httpClientConfig.connectTimeoutMs}ms"
        }

        // Create adapter
        val adapter: BaseLLMAdapter = when (provider.lowercase()) {
            "ollama" -> OllamaAdapter(
                model = model,
                configService = configService,
                taskId = taskId,
                subtaskId = subtaskId,
                source = source
            )
            "openai" -> OpenAIAdapter(
                model = model,
                configService = configService,
                taskId = taskId,
                subtaskId = subtaskId,
                source = source
            )
            "anthropic" -> AnthropicAdapter(
                model = model,
                configService = configService,
                taskId = taskId,
                subtaskId = subtaskId,
                source = source
            )
            "gemini" -> GeminiAdapter(
                model = model,
                configService = configService,
                taskId = taskId,
                subtaskId = subtaskId,
                source = source
            )
            "lmstudio" -> LMStudioAdapter(
                model = model,
                configService = configService,
                taskId = taskId,
                subtaskId = subtaskId,
                source = source
            )
            "custom_openai" -> CustomOpenAIAdapter(
                model = model,
                providerName = "custom_openai",
                configService = configService,
                taskId = taskId,
                subtaskId = subtaskId,
                source = source
            )
            "zai" -> ZAIAdapter(
                model = model,
                configService = configService,
                taskId = taskId,
                subtaskId = subtaskId,
                source = source
            )
            "openrouter" -> OpenRouterAdapter(
                model = model,
                configService = configService,
                taskId = taskId,
                subtaskId = subtaskId,
                source = source
            )
            else -> {
                logger.error { "[LLM_CLIENT] Unknown provider: $provider" }
                throw RefioError.ProviderNotConfigured(provider, "provider")
            }
        }

        try {
            logger.info { "[LLM_CLIENT] Calling $provider adapter for model $model (stream=$stream)" }

            val adapterKwargs = buildMap {
                putAll(kwargs)
                responseFormat?.let { put("response_format", it) }
                if (thinking) put("thinking", true)
            }

            val finalMessages = preparedRequest.messages
            if (!contextContent.isNullOrBlank()) {
                logger.debug { "[LLM_CLIENT] Injected context as user message. Final message order:" }
                finalMessages.forEachIndexed { idx, msg ->
                    val preview = if (msg.content.length > 80) msg.content.take(80) + "..." else msg.content
                    logger.debug { "[LLM_CLIENT]   [$idx] [${msg.role}] $preview" }
                }
            }

            // Streaming mode: accumulate content and call callback
            val contentBuilder = StringBuilder()
            var finalUsage: LLMUsage? = null
            var finalFinishReason: String? = null

            val streamCallback: ((pl.jclab.refio.core.llm.StreamChunk) -> Unit)? = if (stream) { llmChunk ->
                contentBuilder.append(llmChunk.delta)

                val chunkCost = if (llmChunk.usage != null) {
                    estimateCost(llmChunk.usage, provider, model)
                } else 0.0

                logger.debug { "[LLM_CLIENT] Stream chunk received: delta=${llmChunk.delta.length} chars, hasOnChunk=${onChunk != null}, finishReason=${llmChunk.finishReason}" }

                // Convert to API StreamChunk and call user callback
                onChunk?.invoke(StreamChunk(
                    delta = llmChunk.delta,
                    accumulated = contentBuilder.toString(),
                    isComplete = llmChunk.finishReason != null,
                    source = source,
                    usage = llmChunk.usage,
                    cost = chunkCost
                ))

                // Capture final usage/finishReason
                if (llmChunk.usage != null) {
                    finalUsage = llmChunk.usage
                }
                if (llmChunk.finishReason != null) {
                    finalFinishReason = llmChunk.finishReason
                }
            } else null

            // Call adapter (streaming or non-streaming based on flag)
            logger.info { "[LLM_CLIENT] Calling adapter.chat with streaming=$stream, hasStreamCallback=${streamCallback != null}, systemMessages=${allSystemMessages.size}, messages=${finalMessages.size}" }
            val response = adapter.chat(
                messages = finalMessages,
                systemMessages = allSystemMessages,
                maxTokens = maxTokens,
                temperature = temperature,
                streaming = stream,
                onStreamChunk = streamCallback,
                kwargs = adapterKwargs
            )

            // For streaming mode, build response from accumulated data
            val finalResponse = if (stream && contentBuilder.isNotEmpty()) {
                val usage = finalUsage ?: response.usage
                val cost = estimateCost(usage, provider, model)
                LLMResponse(
                    content = contentBuilder.toString(),
                    usage = usage,
                    model = model,
                    provider = provider,
                    cost = cost,
                    finishReason = finalFinishReason ?: response.finishReason
                )
            } else {
                response
            }

            // Log response details
            logger.info {
                "[LLM_CLIENT] Response received from $provider: " +
                        "tokens_in=${finalResponse.usage.inputTokens}, " +
                        "tokens_out=${finalResponse.usage.outputTokens}, " +
                        "cost=$${String.format("%.4f", finalResponse.cost)}, " +
                        "finish_reason=${finalResponse.finishReason}"
            }

            val contentPreview = if (finalResponse.content.length > 500) {
                finalResponse.content.substring(0, 500) + "..."
            } else {
                finalResponse.content
            }
            logger.debug { "[LLM_CLIENT] Response content: $contentPreview" }

            // Record request in global metrics
            try {
                GlobalMetrics.recordRequest(
                    tokensIn = finalResponse.usage.inputTokens,
                    tokensOut = finalResponse.usage.outputTokens,
                    costUsd = finalResponse.cost,
                    success = true
                )
            } catch (e: Exception) {
                logger.error(e) { "[LLM_CLIENT] Failed to record request in global metrics" }
            }

            logger.info { "[LLM_CLIENT] Request completed successfully for $provider/$model" }
            return finalResponse

        } catch (e: Exception) {
            logger.error(e) { "[LLM_CLIENT] Error during $provider request: ${e::class.simpleName}: ${e.message}" }

            try {
                GlobalMetrics.recordRequest(tokensIn = 0, tokensOut = 0, costUsd = 0.0, success = false)
            } catch (e2: Exception) {
                logger.error(e2) { "[LLM_CLIENT] Failed to record failed request in global metrics" }
            }

            throw e
        } finally {
            adapter.close()
        }
    }

    private fun estimateCost(usage: LLMUsage, provider: String, model: String): Double {
        return calculateCost(
            provider = provider,
            model = model,
            inputTokens = usage.inputTokens,
            outputTokens = usage.outputTokens
        )
    }

    /**
     * Get list of supported providers.
     */
    fun getSupportedProviders(): List<String> {
        return listOf("ollama", "openai", "anthropic", "gemini", "openrouter", "lmstudio", "custom_openai", "zai")
    }

    /**
     * Infer provider from model name using pattern matching.
     *
     * @param model Model identifier
     * @return Provider name
     *
     * Examples:
     * ```
     * client.inferProvider("gpt-4o-mini")  // "openai"
     * client.inferProvider("claude-3-5-sonnet")  // "anthropic"
     * client.inferProvider("qwen2.5:7b")  // "ollama"
     * client.inferProvider("anthropic/claude-3.5-sonnet")  // "openrouter"
     * ```
     */
    fun inferProvider(model: String): String {
        return inferProvider(model, default = "ollama")
    }
}
