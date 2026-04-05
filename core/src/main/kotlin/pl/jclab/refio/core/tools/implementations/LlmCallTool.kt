package pl.jclab.refio.core.tools.implementations

import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMContentPart
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.llm.ModelDefinitions
import pl.jclab.refio.core.llm.inferProvider
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.ImagePreparationService
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
 * - image_path: Optional image file path to include in the prompt (requires vision-capable model)
 * - image_base64: Optional base64-encoded image data (requires vision-capable model, use with image_media_type)
 * - image_media_type: Media type for image_base64 (e.g. "image/png"). Defaults to "image/png".
 * - model: Optional model override, e.g. "ollama/qwen2.5:7b". Defaults to weak model.
 * - temperature: 0.0–2.0 (default: 0.7)
 * - max_tokens: Max output tokens (default: 2048)
 * - save_to_file: Optional path to save the LLM response to disk
 */
class LlmCallTool(
    private val llmClient: LLMClient,
    private val configService: ConfigService,
    private val sandbox: PathSandbox,
    private val fileLimits: FileLimits = FileLimits.DEFAULT,
    private val imagePreparationService: ImagePreparationService = ImagePreparationService()
) : Tool {

    override val name = "llm_call"
    override val description = "Send a prompt to an LLM and get the text response. " +
        "Use 'prompt' for instructions/role, 'data' for inline text, 'file_path' for large content (keeps it out of agent context). " +
        "Supports vision: use 'image_path' or 'image_base64' with a vision-capable model (e.g. openai/gpt-4o). " +
        "No tools, no history, no project context — a raw single-turn LLM call. CHEAPER than invoke_subagent."
    override val mode = ToolMode.WRITE
    override val category = ToolCategory.FILE_PRODUCING

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val userPrompt = params["data"]?.toString()?.trim() ?: ""
        val filePath = params["file_path"]?.toString()?.trim()
        val imagePath = params["image_path"]?.toString()?.trim()
        val imageBase64 = params["image_base64"]?.toString()?.trim()
        val imageMediaType = params["image_media_type"]?.toString()?.trim() ?: "image/png"
        val systemPrompt = params["prompt"]?.toString()?.takeIf { it.isNotBlank() }
        val temperature = (params["temperature"] as? Number)?.toDouble()?.coerceIn(0.0, 2.0) ?: 0.7
        val maxTokens = (params["max_tokens"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 8192
        val saveToFile = params["save_to_file"]?.toString()?.trim()
        val taskId = params["_task_id"]?.toString()

        // Build final prompt: data + optional file contents
        val hasImage = !imagePath.isNullOrBlank() || !imageBase64.isNullOrBlank()
        val finalPrompt = buildFinalPrompt(userPrompt, filePath)
            ?: if (hasImage) "" else return ToolResult.error("Either 'data', 'file_path', or 'image_path'/'image_base64' is required")

        // Resolve model
        val (model, provider) = resolveModel(params["model"]?.toString())

        // Prepare image if provided (image_path takes priority over image_base64)
        val imagePart = if (hasImage) {
            val visionCheck = checkVisionSupport(provider, model)
            if (visionCheck != null) return visionCheck

            if (!imagePath.isNullOrBlank()) {
                prepareImage(imagePath) ?: return ToolResult.error("Failed to read or prepare image: $imagePath")
            } else {
                prepareBase64Image(imageBase64!!, imageMediaType)
                    ?: return ToolResult.error("Failed to process base64 image. Check image_base64 and image_media_type.")
            }
        } else {
            null
        }

        // Build message parts
        val message = if (imagePart != null) {
            val parts = mutableListOf<LLMContentPart>()
            if (finalPrompt.isNotBlank()) {
                parts.add(LLMContentPart.Text(finalPrompt))
            }
            parts.add(imagePart)
            LLMMessage(role = "user", content = finalPrompt, parts = parts)
        } else {
            LLMMessage(role = "user", content = finalPrompt)
        }

        val imageSource = when {
            !imagePath.isNullOrBlank() -> "file:$imagePath"
            !imageBase64.isNullOrBlank() -> "base64:${imageMediaType}"
            else -> "-"
        }
        logger.info {
            "[LLM_CALL] provider=$provider model=$model temp=$temperature maxTokens=$maxTokens " +
                "file=${filePath ?: "-"} image=$imageSource saveTo=${saveToFile ?: "-"}"
        }

        val response = try {
            llmClient.complete(
                provider = provider,
                model = model,
                messages = listOf(message),
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

    private fun checkVisionSupport(provider: String, model: String): ToolResult? {
        val definition = ModelDefinitions.getDefinition(provider, model)
        if (definition != null && !definition.supportsVision) {
            return ToolResult.error(
                "Model '$provider/$model' does not support vision. " +
                    "Use a vision-capable model (e.g. openai/gpt-4o, anthropic/claude-sonnet-4-5, gemini/gemini-2.5-pro)."
            )
        }
        // definition == null means unknown model — allow attempt (e.g. OpenRouter, custom models)
        if (definition == null) {
            logger.warn { "[LLM_CALL] Model '$provider/$model' not in registry, cannot verify vision support. Proceeding anyway." }
        }
        return null
    }

    private fun prepareImage(imagePath: String): LLMContentPart.Image? {
        return try {
            val normalized = normalizePath(imagePath)
            val resolved = sandbox.resolve(normalized)

            if (!resolved.isRegularFile()) {
                logger.warn { "[LLM_CALL] Image not a regular file: $resolved" }
                return null
            }

            val bytes = Files.readAllBytes(resolved)
            val mediaType = Files.probeContentType(resolved)
                ?: detectMediaTypeByExtension(resolved.toString())

            if (mediaType == null || !mediaType.startsWith("image/")) {
                logger.warn { "[LLM_CALL] Not an image file: $resolved (mediaType=$mediaType)" }
                return null
            }

            val prepared = imagePreparationService.prepare(bytes, mediaType)
            logger.info { "[LLM_CALL] Image prepared: ${prepared.originalSizeBytes} -> ${prepared.preparedSizeBytes} bytes" }

            LLMContentPart.Image(
                mediaType = prepared.mediaType,
                base64Data = prepared.base64Data,
                detail = "high"
            )
        } catch (e: Exception) {
            logger.error(e) { "[LLM_CALL] Failed to prepare image: $imagePath" }
            null
        }
    }

    private fun prepareBase64Image(base64Data: String, mediaType: String): LLMContentPart.Image? {
        return try {
            if (mediaType !in ImagePreparationService.SUPPORTED_TYPES) {
                logger.warn { "[LLM_CALL] Unsupported image media type: $mediaType" }
                return null
            }

            val bytes = java.util.Base64.getDecoder().decode(base64Data)
            val prepared = imagePreparationService.prepare(bytes, mediaType)
            logger.info { "[LLM_CALL] Base64 image prepared: ${prepared.originalSizeBytes} -> ${prepared.preparedSizeBytes} bytes" }

            LLMContentPart.Image(
                mediaType = prepared.mediaType,
                base64Data = prepared.base64Data,
                detail = "high"
            )
        } catch (e: IllegalArgumentException) {
            logger.error(e) { "[LLM_CALL] Invalid base64 image data" }
            null
        } catch (e: Exception) {
            logger.error(e) { "[LLM_CALL] Failed to prepare base64 image" }
            null
        }
    }

    private fun detectMediaTypeByExtension(path: String): String? {
        return when (path.substringAfterLast('.').lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> null
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
                "description" to "Path to a text file whose contents are used as data. " +
                    "Prefer this over passing large content via 'data' — keeps it out of agent context."
            ),
            "image_path" to mapOf(
                "type" to "string",
                "description" to "Path to an image file (PNG, JPEG, GIF, WebP) to include in the prompt. " +
                    "Requires a vision-capable model (e.g. openai/gpt-4o, anthropic/claude-sonnet-4-5). " +
                    "Returns error if the selected model does not support vision."
            ),
            "image_base64" to mapOf(
                "type" to "string",
                "description" to "Base64-encoded image data to include in the prompt. " +
                    "Alternative to image_path — use when you already have image bytes (e.g. from http_request). " +
                    "Requires a vision-capable model. Use image_media_type to specify the format."
            ),
            "image_media_type" to mapOf(
                "type" to "string",
                "description" to "Media type for image_base64 (e.g. 'image/png', 'image/jpeg'). Defaults to 'image/png'. " +
                    "Only used with image_base64."
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
