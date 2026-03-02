package pl.jclab.refio.core.services

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMMessage

interface TaskVerifier {
    suspend fun verifyCompletion(taskId: String, userRequest: String, response: String): VerificationResult
}

data class VerificationResult(
    val isComplete: Boolean,
    val reason: String,
    val suggestedActions: List<String> = emptyList()
)

class NoopTaskVerifier : TaskVerifier {
    override suspend fun verifyCompletion(taskId: String, userRequest: String, response: String): VerificationResult {
        return VerificationResult(isComplete = true, reason = "Task verification disabled")
    }
}

class LlmTaskVerifier(
    private val llmClient: LLMClient,
    private val configService: ConfigService
) : TaskVerifier {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun verifyCompletion(taskId: String, userRequest: String, response: String): VerificationResult {
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

    companion object {
        private const val TASK_VERIFICATION_SYSTEM_PROMPT = """
You are a task completion verifier for a coding assistant.
Decide if the assistant response indicates the user's request is fully completed.

Return JSON only, no extra text:
{
  "is_complete": true|false,
  "reason": "short explanation",
  "suggested_actions": ["action 1", "action 2"]
}

Rules:
- Be strict. If completion is uncertain, return false.
- Do not assume changes were made unless explicitly stated.
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
