package pl.jclab.refio.core.llm

import pl.jclab.refio.core.db.ChatMessage
import pl.jclab.refio.core.utils.GsonInstance.gson

object LLMMessageMapper {
    fun fromToolResult(
        msg: ChatMessage,
        summarizedContent: String
    ): LLMMessage {
        val toolHeader = "[Tool Result for ${msg.toolCallId}]"
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

        if (!isImage) {
            return LLMMessage(role = "user", content = textContent)
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

    private fun parseMetadata(metadataJson: String?): Map<String, Any?> {
        if (metadataJson.isNullOrBlank()) return emptyMap()

        return runCatching {
            @Suppress("UNCHECKED_CAST")
            gson.fromJson(metadataJson, Map::class.java) as? Map<String, Any?> ?: emptyMap()
        }.getOrDefault(emptyMap())
    }
}
