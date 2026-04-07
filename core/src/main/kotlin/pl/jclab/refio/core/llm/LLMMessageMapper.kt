package pl.jclab.refio.core.llm

import pl.jclab.refio.core.db.ChatMessage
import pl.jclab.refio.core.utils.GsonInstance.gson

object LLMMessageMapper {
    fun fromToolResult(
        msg: ChatMessage,
        summarizedContent: String,
        toolName: String? = null
    ): LLMMessage {
        // Prefer subtaskId — it is the canonical id used by RECENT_WORK and WORKING_MEMORY,
        // so the model sees one stable identifier per tool execution. Fall back to the older
        // toolCallId for legacy rows that predate the subtaskId column.
        val displayId = msg.subtaskId ?: msg.toolCallId
        val toolHeader = buildToolHeader(displayId, toolName)
        val metadata = parseMetadata(msg.metadata)
        val path = metadata["path"]?.toString()
        val mediaType = metadata["media_type"]?.toString()
        val base64Data = metadata["base64"]?.toString()
        val isImage = metadata["type"] == "image" && !mediaType.isNullOrBlank() && !base64Data.isNullOrBlank()

        val textContent = buildString {
            append(toolHeader)
            if (summarizedContent.isNotBlank()) {
                append("\n")
                append(summarizedContent)
            }
            if (isImage) {
                append("\n\nAttached image")
                if (!path.isNullOrBlank()) {
                    append(" from ")
                    append(path)
                }
                append(".")
            }
        }

        // Text tool results use the canonical "tool" role. Ollama supports it
        // natively in its chat templates; adapters for providers that don't
        // accept "tool" in the messages array (Anthropic, OpenAI-compatible)
        // remap it to "assistant" before sending. Mapping to "assistant" at
        // this layer broke Ollama: the Qwen chat template treated a trailing
        // assistant message as "model already finished", so the next turn
        // returned 1 EOS token and empty content.
        // Image tool results stay on the user role for provider vision compat.
        if (!isImage) {
            return LLMMessage(role = "tool", content = textContent)
        }

        return LLMMessage(
            role = "user",
            content = textContent,
            parts = listOf(
                LLMContentPart.Text(textContent),
                LLMContentPart.Image(
                    mediaType = mediaType!!,
                    base64Data = base64Data!!,
                    detail = "auto"
                )
            )
        )
    }

    private fun buildToolHeader(displayId: String?, toolName: String?): String {
        val namePart = toolName?.takeIf { it.isNotBlank() }
        val idPart = displayId?.takeIf { it.isNotBlank() }
        return when {
            namePart != null && idPart != null -> "[Tool result: $namePart id: $idPart]"
            namePart != null -> "[Tool result: $namePart]"
            idPart != null -> "[Tool result id: $idPart]"
            else -> "[Tool result]"
        }
    }

    private fun parseMetadata(metadataJson: String?): Map<String, Any?> {
        if (metadataJson.isNullOrBlank()) return emptyMap()

        return runCatching {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(metadataJson, Map::class.java) as? Map<String, Any?> ?: emptyMap()
        }.getOrDefault(emptyMap())
    }
}
