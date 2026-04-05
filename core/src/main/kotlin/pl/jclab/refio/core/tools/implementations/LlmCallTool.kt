package pl.jclab.refio.core.tools.implementations

import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.inferProvider
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.tools.PathSandbox
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult
import pl.jclab.refio.core.tools.normalizePath
import pl.jclab.refio.core.tools.security.FileLimits
import java.nio.file.Files
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile

private val logger = dualLogger("LlmCallTool")

/**
 * LLM Call Tool — sends a prompt directly to an LLM and returns the text response.
 *
 * Use for classification, transformation, evaluation, summarization, or short generation tasks.
 * No tools, no conversation history, no project context — just a raw single-turn LLM call.
 *
 * Optionally reads a file and appends its contents to the prompt.
 * Optionally saves the LLM response to a file instead of returning it in context.
 *
 * Parameters:
 * - prompt: System prompt — instructions, role, or task description
 * - data: Inline data or text to analyze (optional if file_path is provided)
 * - file_path: Optional file whose contents are used as data input
 * - model: Optional model override, e.g. "ollama/qwen2.5:7b". Defaults to weak model.
 * - temperature: 0.0–2.0 (default: 0.7)
 * - max_tokens: Max output tokens (default: 2048)
 * - save_to_file: Optional path to save the LLM response to disk
 */
class LlmCallTool(
    private val llmClient: LLMClient,
    private val configService: ConfigService,
    private val sandbox: PathSandbox,
    private val fileLimits: FileLimits = FileLimits.DEFAULT
) : Tool {

    override val name = "llm_call"
    override val description = "Send a prompt to an LLM and get the text response. " +
        "Use 'prompt' for instructions/role, 'data' for inline text, 'file_path' for large content (keeps it out of agent context). " +
        "No tools, no history, no project context — a raw single-turn LLM call. CHEAPER than invoke_subagent."
    override val mode = ToolMode.WRITE
    override val category = ToolCategory.FILE_PRODUCING

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val userPrompt = params["data"]?.toString()?.trim() ?: ""
        val filePath = params["file_path"]?.toString()?.trim()
        val systemPrompt = params["prompt"]?.toString()?.takeIf { it.isNotBlank() }
        val temperature = (params["temperature"] as? Number)?.toDouble()?.coerceIn(0.0, 2.0) ?: 0.7
        val maxTokens = (params["max_tokens"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 8192
        val saveToFile = params["save_to_file"]?.toString()?.trim()
        val taskId = params["_task_id"]?.toString()

        // Build final prompt: data + optional file contents
        val finalPrompt = buildFinalPrompt(userPrompt, filePath)
            ?: return ToolResult.error("Either 'data' or 'file_path' is required")

        // Resolve model
        val (model, provider) = resolveModel(params["model"]?.toString())

        logger.info {
            "[LLM_CALL] provider=$provider model=$model temp=$temperature maxTokens=$maxTokens " +
                "file=${filePath ?: "-"} saveTo=${saveToFile ?: "-"}"
        }

        val response = try {
            llmClient.complete(
                provider = provider,
                model = model,
                messages = listOf(LLMMessage(role = "user", content = finalPrompt)),
                systemPrompt = systemPrompt,
                maxTokens = maxTokens,
                temperature = temperature,
                taskId = taskId,
                source = "LlmCallTool",
                thinking = false,
                stream = false,
            )
        } catch (e: Exception) {
            logger.error(e) { "[LLM_CALL] Failed: ${e.message}" }
            return ToolResult.error("LLM call failed: ${e.message}")
        }

        val costMetadata = mapOf(
            "provider" to provider,
            "model" to model,
            "tokens_in" to response.usage.inputTokens,
            "tokens_out" to response.usage.outputTokens,
            "cost" to response.cost
        )

        val cleanContent = stripThinkingTags(response.content)

        // Optionally save to file
        if (!saveToFile.isNullOrBlank()) {
            return saveResponseToFile(saveToFile, cleanContent, costMetadata)
        }

        return ToolResult.success(output = cleanContent, metadata = costMetadata)
    }

    private fun buildFinalPrompt(userPrompt: String, filePath: String?): String? {
        val fileContent = if (!filePath.isNullOrBlank()) {
            readFileContent(filePath) ?: return null
        } else {
            null
        }

        return when {
            userPrompt.isNotBlank() && fileContent != null -> "$userPrompt\n\n$fileContent"
            userPrompt.isNotBlank() -> userPrompt
            fileContent != null -> fileContent
            else -> null
        }
    }

    private fun readFileContent(filePath: String): String? {
        return try {
            val normalized = normalizePath(filePath)
            val resolved = sandbox.resolve(normalized)

            if (!resolved.isRegularFile()) {
                logger.warn { "[LLM_CALL] Not a regular file: $resolved" }
                return null
            }

            val size = resolved.fileSize()
            if (size > fileLimits.maxFileSize) {
                logger.warn { "[LLM_CALL] File too large: $size bytes (max ${fileLimits.maxFileSize})" }
                return null
            }

            Files.readString(resolved)
        } catch (e: Exception) {
            logger.error(e) { "[LLM_CALL] Failed to read file: $filePath" }
            null
        }
    }

    private fun saveResponseToFile(
        filePath: String,
        content: String,
        metadata: Map<String, Any>
    ): ToolResult {
        return try {
            val normalized = normalizePath(filePath)
            val resolved = sandbox.resolve(normalized)

            resolved.parent?.let { parentDir ->
                if (!Files.exists(parentDir)) {
                    Files.createDirectories(parentDir)
                }
            }

            Files.writeString(resolved, content)

            val sizeBytes = content.toByteArray().size
            val preview = content.take(PREVIEW_CHARS)
            val truncated = content.length > PREVIEW_CHARS

            logger.info { "[LLM_CALL] Saved response to $resolved ($sizeBytes bytes)" }

            val summary = buildString {
                appendLine("LLM response saved to: $resolved")
                appendLine("Size: $sizeBytes bytes (${content.length} chars)")
                appendLine()
                appendLine("Preview:")
                appendLine("---")
                append(preview)
                if (truncated) {
                    appendLine()
                    appendLine("---")
                    appendLine("... (${content.length - PREVIEW_CHARS} more characters in file)")
                }
            }

            ToolResult(
                success = true,
                output = summary,
                bytesWritten = sizeBytes,
                filesChanged = listOf(resolved.toString()),
                metadata = metadata + ("saved_to_file" to resolved.toString())
            )
        } catch (e: Exception) {
            logger.error(e) { "[LLM_CALL] Failed to save response to file: $filePath" }
            ToolResult.error("Failed to save LLM response to file: ${e.message}")
        }
    }

    private fun resolveModel(modelParam: String?): Pair<String, String> {
        if (!modelParam.isNullOrBlank()) {
            if (modelParam.contains("/")) {
                val parts = modelParam.split("/", limit = 2)
                return Pair(parts[1], parts[0])
            }
        }

        return configService.getModel(
            operation = ModelOperation.WEAK,
        )
    }

    override fun getParameterSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "prompt" to mapOf(
                "type" to "string",
                "description" to "System prompt — instructions, role, or task description for the LLM."
            ),
            "data" to mapOf(
                "type" to "string",
                "description" to "Data or text to analyze/transform. Optional if file_path is provided."
            ),
            "file_path" to mapOf(
                "type" to "string",
                "description" to "Path to a file whose contents are used as data. " +
                    "Prefer this over passing large content via 'data' — keeps it out of agent context."
            ),
            "model" to mapOf(
                "type" to "string",
                "description" to "Model to use, e.g. 'ollama/qwen2.5:7b' or 'claude-3-5-haiku'. " +
                    "Defaults to the configured weak model."
            ),
            "temperature" to mapOf(
                "type" to "number",
                "description" to "Sampling temperature 0.0–2.0 (default: 0.7)"
            ),
            "max_tokens" to mapOf(
                "type" to "integer",
                "description" to "Max output tokens (default: 8192)"
            ),
            "save_to_file" to mapOf(
                "type" to "string",
                "description" to "Save LLM response to this file path instead of returning full content. " +
                    "Returns a summary with preview."
            )
        )
    )

    private fun stripThinkingTags(content: String): String {
        var result = content
        for (tag in THINKING_TAGS) {
            val escaped = Regex.escape(tag)
            result = result.replace(Regex("(?is)<$escaped\\b[^>]*>.*?</$escaped>"), "")
        }
        return result.trim()
    }

    companion object {
        private const val PREVIEW_CHARS = 500
        private val THINKING_TAGS = listOf("think", "thinking")
    }
}
