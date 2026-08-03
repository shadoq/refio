package pl.jclab.refio.core.services.turn

import pl.jclab.refio.core.api.TurnProfileOverrides
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.llm.LLMResponse

/**
 * Weak-model recovery for an empty-content response in JSON-in-text mode.
 *
 * Pure classification — no persistence, no finalization, no loop control. [AgentTurnLoop] owns the
 * side effects; this only decides WHICH path applies so the rescue logic (qwen-style "the JSON
 * envelope landed in `thinking`") and the bounded nudge policy are testable in isolation.
 *
 * Scope is deliberately the empty-content branch only. The separate broken-format branch (non-empty
 * prose / empty envelope / text-embedded tool call) stays in the loop because its detection is tied
 * to JSON-envelope inspection there; both branches share the same nudge budget via [RecoveryState].
 */
class LLMResponseRecovery(private val toolCallParser: ToolCallParser) {

    sealed interface Decision {
        /** `thinking` carried the envelope; re-bind it as content and fall through to extraction. */
        data class RecoverFromThinking(val newContent: String) : Decision

        /** Empty content but budget remains: ask the model to regenerate the JSON envelope. */
        object Nudge : Decision

        /** Empty content, unrecoverable (nudges/iterations exhausted, or non-AGENT mode). */
        data class GiveUp(val reason: String) : Decision

        /** This response is not an empty-content JSON-mode case — let the loop proceed normally. */
        object NotApplicable : Decision
    }

    /**
     * Classify an LLM response that may have come back with empty content in JSON-in-text mode.
     *
     * @param jsonMode `true` when no native tool schemas are active (the JSON-envelope contract);
     *   the symmetric native-tools empty branch is handled elsewhere in the loop.
     * @param state shared nudge budget (also incremented by the loop's broken-format branch).
     * @param hasRestorableAnswer true when a completion-guardian re-entry already stashed the
     *   terminal answer and has added no tool work since — see [Decision.GiveUp] policy below.
     */
    fun classifyEmptyContent(
        response: LLMResponse,
        mode: TaskMode,
        jsonMode: Boolean,
        iteration: Int,
        maxIterations: Int,
        state: RecoveryState,
        profileOverrides: TurnProfileOverrides? = null,
        hasRestorableAnswer: Boolean = false,
    ): Decision {
        val applies = mode != TaskMode.CHAT &&
            response.content.isBlank() &&
            response.nativeToolCalls.isNullOrEmpty() &&
            jsonMode
        if (!applies) {
            return Decision.NotApplicable
        }

        // Fallback 1: recover the JSON envelope from `thinking`. Accept it if it parses to tool
        // calls OR its trimmed form starts with `{` (a final-response envelope without `actions`).
        val thinking = response.thinking
        val looksLikeEnvelope = thinking?.trim().orEmpty().startsWith("{")
        val recoveredFromThinking = if (!thinking.isNullOrBlank()) {
            runCatching { toolCallParser.extractToolCalls(thinking, mode, profileOverrides) }
                .getOrDefault(emptyList())
        } else {
            emptyList()
        }
        if (recoveredFromThinking.isNotEmpty() || looksLikeEnvelope) {
            return Decision.RecoverFromThinking(thinking ?: "")
        }

        // A guardian re-entry already stashed the answer the user saw and the model answered that
        // re-entry with nothing at all. The re-entry WAS the last safety net, so a format nudge has
        // nothing left to rescue - it only replays a full-context prompt for another empty reply
        // before the loop restores the same stashed answer anyway (observed on qwen3.6:35b: two
        // nudges, ~30s and 65K input tokens burned, identical outcome).
        if (hasRestorableAnswer) {
            return Decision.GiveUp("empty-content-after-guardian-reentry")
        }

        // No envelope to recover: nudge the model to regenerate, bounded to 2 (AGENT only). A weak
        // model that can't recover after two reminders won't recover at all — fail loud instead.
        val canNudge = mode == TaskMode.AGENT &&
            state.nudgeCount < 2 &&
            iteration < maxIterations
        return if (canNudge) {
            Decision.Nudge
        } else {
            Decision.GiveUp("empty-content-unrecoverable")
        }
    }
}

/**
 * Shared recovery budget across the turn loop's two nudge sites (empty-content + broken-format).
 * One counter so the combined "max 2 nudges then hard-fail" policy holds across both. `lastPlainText`
 * backs the broken-format branch's repeated-output guard.
 */
data class RecoveryState(var nudgeCount: Int = 0, var lastPlainText: String? = null)
