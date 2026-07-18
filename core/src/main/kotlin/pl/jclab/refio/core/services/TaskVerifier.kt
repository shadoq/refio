package pl.jclab.refio.core.services

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMMessage

interface TaskVerifier {
    suspend fun verifyCompletion(
        taskId: String,
        userRequest: String,
        response: String,
        agentInstanceId: String? = null
    ): VerificationResult
}

data class VerificationResult(
    val isComplete: Boolean,
    val reason: String,
    val suggestedActions: List<String> = emptyList()
)

class NoopTaskVerifier : TaskVerifier {
    override suspend fun verifyCompletion(
        taskId: String,
        userRequest: String,
        response: String,
        agentInstanceId: String?
    ): VerificationResult {
        return VerificationResult(isComplete = true, reason = "Task verification disabled")
    }
}

class LlmTaskVerifier(
    private val llmClient: LLMClient,
    private val configService: ConfigService,
    private val chatMessageRepository: ChatMessageRepository
) : TaskVerifier {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun verifyCompletion(
        taskId: String,
        userRequest: String,
        response: String,
        agentInstanceId: String?
    ): VerificationResult {
        val recentEvidence = buildRecentEvidence(taskId, agentInstanceId)
        val (modelId, providerName) = configService.getModel(ModelOperation.WEAK, taskId)
        val llmResponse = llmClient.complete(
            provider = providerName,
            model = modelId,
            messages = listOf(
                LLMMessage(
                    role = "user",
                    content = """
User request:
$userRequest

Assistant response:
$response

Recent execution evidence:
$recentEvidence
                    """.trimIndent()
                )
            ),
            systemPrompt = TASK_VERIFICATION_SYSTEM_PROMPT,
            taskId = taskId,
            source = "TaskVerifier",
            stream = false,
            onChunk = null
        )

        return parseVerification(llmResponse.content)
    }

    private fun parseVerification(content: String): VerificationResult {
        val payload = try {
            json.decodeFromString(TaskVerificationPayload.serializer(), content.trim())
        } catch (e: Exception) {
            throw IllegalStateException("Invalid task verification response: ${e.message}")
        }

        return VerificationResult(
            isComplete = payload.isComplete,
            reason = payload.reason,
            suggestedActions = payload.suggestedActions ?: emptyList()
        )
    }

    private fun buildRecentEvidence(taskId: String, agentInstanceId: String?): String {
        // Judge the thread being completed: the parent run (null id) excludes subagent steps, and a
        // subagent's evidence excludes the parent conversation.
        val messages = chatMessageRepository.findHistoryForInvocation(taskId, agentInstanceId)
            .takeLast(8)

        if (messages.isEmpty()) {
            return "(no recent evidence)"
        }

        return buildString {
            messages.forEach { message ->
                val line = when (message.role) {
                    MessageRole.USER -> "USER: ${message.content}"
                    MessageRole.ASSISTANT -> {
                        val toolInfo = message.toolCalls
                            ?.joinToString(", ") { it.name }
                            ?.takeIf { it.isNotBlank() }
                        if (toolInfo != null) {
                            "ASSISTANT: ${message.content}\nTOOLS: $toolInfo"
                        } else {
                            "ASSISTANT: ${message.content}"
                        }
                    }
                    MessageRole.TOOL -> "TOOL_RESULT: ${message.rawOutput ?: message.content}"
                    MessageRole.SYSTEM -> "SYSTEM: ${message.content}"
                }
                appendLine(line.take(1200))
                appendLine()
            }
        }.trim().ifBlank { "(no recent evidence)" }
    }

    companion object {
        private const val TASK_VERIFICATION_SYSTEM_PROMPT = """
You are a task completion verifier for a coding assistant.
Decide if the assistant response indicates the user's request is fully completed.
Use the recent execution evidence to verify the claim.

Return JSON only, no extra text:
{
  "is_complete": true|false,
  "reason": "short explanation",
  "suggested_actions": ["action 1", "action 2"]
}

Rules:
- Be strict. If completion is uncertain, return false.
- Do not assume changes were made unless the evidence supports that claim.
- If the assistant claims files were fixed, updated, or verified, the evidence must support it.
- If recent evidence shows reads/searches that still suggest unresolved issues, return false.
- Suggested actions should be concrete next steps for the assistant.
"""
    }
}

@Serializable
private data class TaskVerificationPayload(
    @SerialName("is_complete")
    val isComplete: Boolean,
    val reason: String,
    @SerialName("suggested_actions")
    val suggestedActions: List<String>? = null
)
