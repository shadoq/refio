package pl.jclab.refio.core.tools.implementations

import pl.jclab.refio.core.services.turn.UserQuestionService
import pl.jclab.refio.core.tools.base.Tool
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolInternalParams
import pl.jclab.refio.core.tools.base.ToolMode
import pl.jclab.refio.core.tools.base.ToolResult

class AskUserTool(
    private val questionService: UserQuestionService
) : Tool {
    override val name = "ask_user"
    override val description = "Ask the user a question and wait for their response. " +
        "Use when you need clarification, a choice, or confirmation before proceeding. " +
        "Optionally provide predefined options to make it easier to respond. " +
        "The agent loop is paused until the user answers."
    override val mode = ToolMode.READ_ONLY
    override val category = ToolCategory.SYSTEM

    override fun validateParams(params: Map<String, Any>) {
        val q = params["question"] as? String
        if (q.isNullOrBlank()) throw IllegalArgumentException("Parameter 'question' is required")
    }

    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val question = params["question"] as? String
            ?: return ToolResult.error("Missing required parameter: 'question'")

        @Suppress("UNCHECKED_CAST")
        val options = params["options"] as? List<String>

        val taskId = (params[ToolInternalParams.TASK_ID] as? String) ?: "unknown"

        val result = questionService.ask(taskId, question, options)

        return result.fold(
            onSuccess = { answer ->
                ToolResult(
                    success = true,
                    output = "User answered: $answer",
                    durationMs = 0,
                    metadata = mapOf("answer" to answer)
                )
            },
            onFailure = { error ->
                ToolResult.error("ask_user failed: ${error.message}")
            }
        )
    }

    override fun getParameterSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "question" to mapOf(
                "type" to "string",
                "description" to "The question to ask the user. Be specific and concise."
            ),
            "options" to mapOf(
                "type" to "array",
                "items" to mapOf("type" to "string"),
                "description" to "Optional predefined choices. If provided, user picks from this list."
            )
        ),
        "required" to listOf("question")
    )
}
