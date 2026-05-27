package pl.jclab.refio.core.services.turn

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.ConfigService

private val logger = dualLogger("NextSpeakerJudgeGuardian")

/**
 * "Is the agent really done?" judge — Gemini CLI's `checkNextSpeaker` pattern adapted as a
 * [TurnCompletionGuardian]. Runs only in [TaskMode.AGENT] (PLAN is read-only so an early
 * stop is cheap; CHAT has no tool loop to re-enter).
 *
 * Operates in two modes depending on [GuardianContext.completionCondition]:
 *
 *  - **Generic mode** (condition = null): asks "is the agent's last reply a finished answer,
 *    or a mid-task pause?". Catches weak models that drift into prose announcements without
 *    completing the underlying work.
 *  - **Goal-aware mode** (condition non-null): asks "has THIS specific user-defined condition
 *    been met based on the transcript?". This is the Claude Code `/goal` flavour — the same
 *    LLM call, an enriched prompt, and a stricter pre-filter (textual "Done." hints no longer
 *    short-circuit because they don't prove the goal was met).
 *
 * Cost model: ~150-300 input + ~20-40 output tokens per judge invocation in generic mode;
 * +50-300 input in goal mode (the condition text itself, capped at 2000 chars in the user
 * block). Only triggered at the terminal point of a turn (not every iteration). With the
 * existing [GuardianRegistry] cap ([maxReentries]) the worst case is `1 + maxReentries`
 * judge calls per user message regardless of mode.
 *
 * Safety: a parse failure or LLM exception returns [GuardianDecision.Pass] (treat as "done")
 * — never block a turn on the judge being broken. The guardian also self-skips after the
 * configured per-turn re-entry cap to avoid runaway loops on a goal that never resolves.
 */
class NextSpeakerJudgeGuardian(
    private val llmClient: LLMClient,
    private val configService: ConfigService
) : TurnCompletionGuardian {

    override val name: String = "next_speaker_judge"

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun check(context: GuardianContext): GuardianDecision {
        if (context.mode != TaskMode.AGENT) return GuardianDecision.Pass

        val enabled = configService.getTyped<Boolean>(
            ConfigKeys.GENERAL_NEXT_SPEAKER_JUDGE_ENABLED,
            context.taskId
        )
        if (!enabled) return GuardianDecision.Pass

        // Hard cap on judge-driven re-entries per turn (defense in depth: the registry
        // already enforces [GuardianRegistry.maxReentries] but we duplicate the check
        // here so this guardian's behaviour stays correct even if it ever shares a
        // registry with other guardians that consume the same budget).
        if (context.priorReentries >= MAX_JUDGE_REENTRIES) return GuardianDecision.Pass

        // No-progress short-circuit: if a prior re-entry didn't yield a single new tool
        // call, the nudge isn't working and another iteration will just produce the same
        // stuck pattern. Observed with weak local models (e.g. qwen3 on Ollama) that
        // repeatedly emit "Now let me find X" without ever calling the tool — burning a
        // judge LLM call + a full prompt iteration per loop. Skipping here saves both.
        if (context.priorReentries > 0 &&
            context.toolsUsed.size <= context.toolsUsedSizeAtPriorReentry) {
            logger.info {
                "[JUDGE] taskId=${context.taskId} priorReentries=${context.priorReentries} " +
                    "produced no new tool call (toolsUsed=${context.toolsUsed.size}, " +
                    "snapshot=${context.toolsUsedSizeAtPriorReentry}) — short-circuiting to Pass"
            }
            return GuardianDecision.Pass
        }

        val response = context.finalResponse.trim()
        if (response.isEmpty()) return GuardianDecision.Pass

        if (looksClearlyDone(response, hasGoal = context.completionCondition != null)) {
            logger.debug { "[JUDGE] pre-filter says clearly done — skipping LLM call" }
            return GuardianDecision.Pass
        }

        return try {
            val verdict = callJudge(context, response)
            when (verdict) {
                NextSpeakerVerdict.MODEL -> {
                    val isGoalMode = context.completionCondition != null
                    GuardianDecision.Reenter(
                        nudge = if (isGoalMode) buildGoalContinueNudge(context.completionCondition!!) else CONTINUE_NUDGE,
                        reason = if (isGoalMode) "judge: goal not yet met" else "judge: agent stopped mid-task"
                    )
                }
                NextSpeakerVerdict.USER, NextSpeakerVerdict.UNCERTAIN -> GuardianDecision.Pass
            }
        } catch (e: Exception) {
            logger.warn(e) { "[JUDGE] LLM call failed — defaulting to Pass: ${e.message}" }
            GuardianDecision.Pass
        }
    }

    /**
     * Build a nudge that re-injects the user's completion condition. Capping the condition
     * length keeps a runaway goal text (theoretical 4000 chars) from dominating the prompt.
     */
    private fun buildGoalContinueNudge(condition: String): String =
        "Your previous reply ended without calling any tool and the user-defined completion " +
            "condition is not yet demonstrably met. Continue working toward the goal — emit " +
            "the next tool call to make verifiable progress.\n\nGoal: ${condition.take(500)}"

    private suspend fun callJudge(context: GuardianContext, response: String): NextSpeakerVerdict {
        val (modelId, providerName) = configService.getModel(ModelOperation.WEAK, context.taskId)

        val condition = context.completionCondition
        val isGoalMode = condition != null
        val systemPrompt = if (isGoalMode) GOAL_AWARE_JUDGE_PROMPT else JUDGE_SYSTEM_PROMPT

        val userBlock = buildString {
            if (isGoalMode) {
                append("User-defined completion condition (must be demonstrably met):\n")
                append(condition!!.take(2000))
                append("\n\n")
            }
            append("User request:\n")
            append(context.userRequest?.take(800) ?: "(unknown)")
            append("\n\nAgent's last reply (no tool calls were issued):\n")
            append(response.take(2000))
            append("\n\nTools used so far in this turn: ")
            append(if (context.toolsUsed.isEmpty()) "(none)" else context.toolsUsed.joinToString(", "))
            append("\nIteration: ${context.iteration}/${context.maxIterations}")
        }

        val llmResponse = llmClient.complete(
            provider = providerName,
            model = modelId,
            messages = listOf(LLMMessage(role = "user", content = userBlock)),
            systemPrompt = systemPrompt,
            taskId = context.taskId,
            source = "NextSpeakerJudge",
            stream = false,
            onChunk = null
        )

        val content = llmResponse.content.trim()
        val verdict = parseVerdict(content)
        logger.info {
            "[JUDGE] taskId=${context.taskId} mode=${if (isGoalMode) "goal" else "generic"} verdict=$verdict " +
                "(model=$providerName/$modelId, tokens=${llmResponse.usage.inputTokens}/${llmResponse.usage.outputTokens})"
        }
        return verdict
    }

    private fun parseVerdict(content: String): NextSpeakerVerdict {
        if (content.isBlank()) return NextSpeakerVerdict.UNCERTAIN
        val payload = try {
            json.decodeFromString(JudgePayload.serializer(), extractJsonObject(content))
        } catch (e: Exception) {
            logger.warn { "[JUDGE] failed to parse JSON response: ${e.message} - content=${content.take(200)}" }
            return NextSpeakerVerdict.UNCERTAIN
        }
        return when (payload.speaker.trim().lowercase()) {
            "model" -> NextSpeakerVerdict.MODEL
            "user" -> NextSpeakerVerdict.USER
            else -> NextSpeakerVerdict.UNCERTAIN
        }
    }

    /**
     * Strip optional markdown fences and isolate the outermost JSON object. Weak models
     * sometimes wrap the response in ```json ... ``` despite explicit instructions.
     */
    private fun extractJsonObject(raw: String): String {
        val stripped = raw
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = stripped.indexOf('{')
        val end = stripped.lastIndexOf('}')
        return if (start >= 0 && end > start) stripped.substring(start, end + 1) else stripped
    }

    /**
     * Conservative pre-filter: only short-circuits the LLM call for cases where the answer
     * is unambiguously "user takes over next". Anything else falls through to the judge.
     *
     * Catches the hot paths: explicit completion markers and clarifying questions back to
     * the user. Does NOT try to detect "model paused mid-task" patterns — that's exactly
     * what we want the LLM judge to handle.
     *
     * When the user set a `/goal` completion condition ([hasGoal] = true), the textual
     * "Done."/"Task complete." markers no longer short-circuit: an agent claiming completion
     * is exactly the case the goal-aware judge must verify against transcript evidence. The
     * trailing-`?` check still applies because a clarifying question always needs user input
     * regardless of any active goal.
     */
    private fun looksClearlyDone(text: String, hasGoal: Boolean): Boolean {
        val trimmed = text.trimEnd { it.isWhitespace() || it == '"' || it == '\'' || it == ')' || it == ']' }
        if (trimmed.endsWith("?")) return true
        if (hasGoal) return false
        if (trimmed.length < 30) return true
        val tail = trimmed.takeLast(80).lowercase()
        return EXPLICIT_DONE_MARKERS.any { tail.contains(it) }
    }

    private enum class NextSpeakerVerdict { USER, MODEL, UNCERTAIN }

    @Serializable
    private data class JudgePayload(
        val speaker: String,
        @SerialName("reason")
        val reason: String? = null
    )

    companion object {
        /** Max judge-driven re-entries per turn (cap on consecutive "continue" verdicts). */
        const val MAX_JUDGE_REENTRIES = 3

        private val EXPLICIT_DONE_MARKERS = listOf(
            "task complete",
            "task completed",
            "task is complete",
            "task is done",
            "all done",
            "i'm done",
            "i am done",
            "</final_answer>",
            "</answer>"
        )

        private const val CONTINUE_NUDGE =
            "Your previous reply ended without calling any tool and the task does not appear " +
                "to be finished. Continue working — emit the next tool call (or, if you really " +
                "are done, respond with a clear final statement that completes the task)."

        private const val JUDGE_SYSTEM_PROMPT = """
You decide whether a coding agent should continue working or has finished.

The agent runs in a tool-loop: it can call tools (read files, edit files, run commands)
or reply with text. It just produced a text reply with no tool calls. Your job is to
decide if that text is a finished answer or if the agent stopped mid-task.

Return JSON only, no prose, no markdown fences:
{"speaker": "user" | "model", "reason": "short explanation"}

Rules:
- "user" = the agent delivered a complete answer. The user should respond next.
- "model" = the agent paused mid-task. It announced intent ("Let me…", "Next I'll…",
  "I will now…") but did not act, OR it summarized a sub-step without completing the
  overall request. The agent should continue.
- Be conservative: when truly uncertain, return "user". Only return "model" when the
  reply clearly stops without finishing what was announced or requested.
- A clarifying question to the user counts as "user" (the agent needs input to proceed).

Examples:

Agent reply: "Now let me find the exact line numbers for maxIterations."
→ {"speaker": "model", "reason": "intent announced, no tool called"}

Agent reply: "I will run pytest to verify."
→ {"speaker": "model", "reason": "declared tool use without calling it"}

Agent reply: "PLAN mode: maxIterations=100 (TurnLoopConfig.kt:93). AGENT mode: maxIterations=100 (line 116). CHAT: no loop. Snapshots only in AGENT (line 123)."
→ {"speaker": "user", "reason": "delivered the full answer with citations"}

Agent reply: "The bug is on line 42 of foo.py; x is read before initialisation. Fix: initialise x before line 42."
→ {"speaker": "user", "reason": "concrete answer to the question"}

Agent reply: "Read the three files. Next I'll grep for usages."
→ {"speaker": "model", "reason": "sub-step summary, work still outstanding"}
"""

        private const val GOAL_AWARE_JUDGE_PROMPT = """
You decide whether a coding agent has met an explicit user-defined completion condition.

The agent runs in a tool-loop and can call tools (read files, edit files, run commands)
or reply with text. It just produced a text reply with no tool calls. The user set a
"/goal" condition before this turn started. Your job is to decide if that condition is
now demonstrably satisfied from what the transcript shows so far.

Return JSON only, no prose, no markdown fences:
{"speaker": "user" | "model", "reason": "short explanation"}

Rules:
- "user" = the goal is demonstrably met (the transcript shows evidence supporting every
  part of the condition), OR the agent is asking the user a clarifying question that
  needs input before the goal can proceed.
- "model" = the goal is NOT yet demonstrably met. The agent paused but the transcript
  does not yet show the required evidence. The agent should continue working.
- Be strict on evidence: do not assume work happened that the transcript does not show.
  If the agent claims "tests pass" but no test command was actually run in the transcript,
  return "model". Claims without execution evidence are not enough.
- Match the condition verbatim: if it says "all tests in test/auth pass", a generic
  "tests pass" claim without coverage of test/auth specifically returns "model".
- A clarifying question to the user counts as "user" regardless of goal status.

Examples:

Goal: "all tests in src/test pass"
Tools used so far: edit_file
Agent reply: "Migrated 5 files. The migration should work now."
→ {"speaker": "model", "reason": "edited files but no test command in transcript"}

Goal: "all tests in src/test pass"
Tools used so far: edit_file, run_terminal_command
Agent reply: "Migration applied. pytest src/test reported 47 passed, 0 failed."
→ {"speaker": "user", "reason": "explicit pytest output covers the goal scope"}

Goal: "fix bug in config parser and add a regression test"
Tools used so far: edit_file
Agent reply: "Fixed the off-by-one error in config_parser.py:88. Done."
→ {"speaker": "model", "reason": "regression test part of the goal not yet demonstrated"}

Goal: "find which file imports OldApi and replace with NewApi"
Tools used so far: grep_search, edit_file
Agent reply: "grep_search found 3 files importing OldApi (a.py, b.py, c.py). I edited all three to import NewApi instead."
→ {"speaker": "user", "reason": "transcript shows both grep and the three edits covering the goal"}
"""
    }
}
