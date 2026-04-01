package pl.jclab.refio.core.subagents.models

import java.util.UUID

/**
 * Status wywołania subagenta.
 */
enum class InvocationStatus {
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELLED
}

/**
 * Reprezentuje wywołanie subagenta.
 */
data class SubagentInvocation(
    val id: String = UUID.randomUUID().toString(),
    val taskId: String,
    val subagentName: String,
    val userPrompt: String,
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val status: InvocationStatus = InvocationStatus.RUNNING,
    val result: SubagentResult? = null
) {
    /**
     * Czas trwania w milisekundach (null jeśli jeszcze trwa).
     */
    val durationMs: Long?
        get() = completedAt?.let { it - startedAt }

    /**
     * Oznacza wywołanie jako zakończone sukcesem.
     */
    fun complete(result: SubagentResult): SubagentInvocation = copy(
        completedAt = System.currentTimeMillis(),
        status = InvocationStatus.SUCCESS,
        result = result
    )

    /**
     * Oznacza wywołanie jako zakończone błędem.
     */
    fun fail(error: String): SubagentInvocation = copy(
        completedAt = System.currentTimeMillis(),
        status = InvocationStatus.FAILED,
        result = SubagentResult(
            success = false,
            response = error,
            toolsUsed = emptyList(),
            tokensUsed = 0,
            durationMs = System.currentTimeMillis() - startedAt
        )
    )

    /**
     * Oznacza wywołanie jako anulowane.
     */
    fun cancel(): SubagentInvocation = copy(
        completedAt = System.currentTimeMillis(),
        status = InvocationStatus.CANCELLED
    )
}

/**
 * Wynik wykonania subagenta.
 */
data class SubagentResult(
    /**
     * Czy wykonanie zakończyło się sukcesem.
     */
    val success: Boolean,

    /**
     * Odpowiedź subagenta (treść dla użytkownika).
     */
    val response: String,

    /**
     * Lista użytych narzędzi.
     */
    val toolsUsed: List<String>,

    /**
     * Liczba użytych tokenów (input + output).
     */
    val tokensUsed: Int,

    /**
     * Czas wykonania w milisekundach.
     */
    val durationMs: Long,

    /**
     * ID zagnieżdżonego taska (tylko dla MULTI_STEP).
     */
    val nestedTaskId: String? = null,

    /**
     * Dodatkowe metadane.
     */
    val metadata: Map<String, Any> = emptyMap()
) {
    companion object {
        /**
         * Tworzy wynik sukcesu.
         */
        fun success(
            response: String,
            toolsUsed: List<String> = emptyList(),
            tokensUsed: Int = 0,
            durationMs: Long = 0,
            metadata: Map<String, Any> = emptyMap()
        ) = SubagentResult(
            success = true,
            response = response,
            toolsUsed = toolsUsed,
            tokensUsed = tokensUsed,
            durationMs = durationMs,
            metadata = metadata
        )

        /**
         * Tworzy wynik błędu.
         */
        fun error(
            message: String,
            durationMs: Long = 0
        ) = SubagentResult(
            success = false,
            response = message,
            toolsUsed = emptyList(),
            tokensUsed = 0,
            durationMs = durationMs
        )
    }
}

/**
 * Informacja o subagent do wyświetlenia w UI.
 */
data class SubagentInfo(
    val name: String,
    val description: String,
    val tools: List<String>?,
    val model: String,
    val enabled: Boolean,
    val scope: String,
    val priority: Int = 0
)
