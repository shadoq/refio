package pl.jclab.refio.cli.ui

import androidx.compose.ui.graphics.Color

data class UIChatMessage(
    val id: String,
    val timestamp: Long,
    val role: String,
    val content: String,
    val agentId: String? = null,
    val agentName: String? = null,
    val agentColor: Color? = null,
    val isStreaming: Boolean = false,
    val messageType: MessageType = MessageType.TEXT
)

enum class MessageType {
    TEXT,
    AGENT_STARTED,
    AGENT_COMPLETED,
    AGENT_FAILED,
    DATA_EXCHANGE,
    APPROVAL_REQUEST,
    ARTIFACT
}
