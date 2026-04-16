package pl.jclab.refio.core.agents.orchestration

import pl.jclab.refio.core.agents.AgentResult
import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.api.StreamCallback
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.ConfigService

private val logger = dualLogger("ResultMerger")

/**
 * Collapses N subagent outputs back into a single coherent answer for the user.
 *
 * Uses the configured default model (user expects high-quality synthesis, not a cheap model).
 * Streams the merged response so the main chat bubble fills in real time, after the per-agent
 * bubbles have already been written.
 */
class ResultMerger(
    private val llmClient: LLMClient,
    private val configService: ConfigService,
) {
    suspend fun merge(
        originalPrompt: String,
        results: Map<String, AgentResult>,
        stream: Boolean = false,
        onChunk: StreamCallback? = null,
    ): String {
        if (results.isEmpty()) {
            logger.warn { "No results to merge — returning empty response" }
            return ""
        }

        val successful = results.values.filter { it.success && it.response.isNotBlank() }
        if (successful.isEmpty()) {
            logger.warn { "All agent runs failed — returning concatenated errors" }
            return results.values.joinToString("\n\n") { "[${it.agentName}] FAILED: ${it.error ?: "unknown error"}" }
        }

        val (model, provider) = configService.getModel(ModelOperation.DEFAULT)
        val systemPrompt = """
            You are a synthesis assistant. Multiple specialist subagents have analyzed the user's
            request independently. Merge their findings into a single coherent response.

            Rules:
            - Do not lose concrete findings; preserve file references, code snippets, and metrics.
            - If agents disagree, surface the disagreement and note both positions.
            - Remove redundancy when multiple agents report the same thing.
            - Use the same tone a senior engineer would when handing a review back to a colleague.
        """.trimIndent()

        val bundle = buildString {
            appendLine("Original user request:")
            appendLine(originalPrompt)
            appendLine()
            appendLine("Agent outputs:")
            successful.forEach { result ->
                appendLine()
                appendLine("--- ${result.agentName} ---")
                appendLine(result.response)
            }
        }

        val response = llmClient.complete(
            provider = provider,
            model = model,
            systemPrompt = systemPrompt,
            messages = listOf(LLMMessage(role = "user", content = bundle)),
            temperature = 0.3,
            stream = stream,
            onChunk = onChunk,
            source = "ResultMerger",
        )

        return response.content
    }
}
