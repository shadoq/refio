package pl.jclab.refio.core.services.turn

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.api.TurnRunProfile
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.ConfigService

private val logger = dualLogger("NextSpeakerJudgeGuardian")

/**
 * "Is the agent really done?" judge — Gemini CLI's `checkNextSpeaker` pattern adapted as a
 * [TurnCompletionGuardian]. Skipped in [TaskMode.CHAT] (no tool loop to monitor); runs in
 * both [TaskMode.PLAN] and [TaskMode.AGENT] — both drive a tool loop that weak models can
 * stall in by announcing intent without calling a tool ("Let me read the file…" with no
 * tool_call).
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
 * **Single bounded re-entry (restored 2026-05).** On a MODEL verdict (the judge believes the
 * request is not yet delivered) this guardian re-enters the loop EXACTLY ONCE with a hard
 * SYSTEM nudge that names the failure mode (intent announced, no tool call) and demands the
 * next concrete tool call. The one-shot cap is enforced by gating re-entry on
 * `priorReentries == 0` plus the no-progress short-circuit below. An earlier revision made the
 * judge observability-only (always Pass), trusting `<focus_discipline>` in the system prompts
 * to keep weak models on task — but manual-tests showed qwen3.5:9b still ended turns on bare
 * intent announcements ("Now let me run git branch…", "I'll now produce the analysis…") with
 * no tool call, and nothing pushed it back, so multi-step tasks silently failed. The re-entry
 * adds NO extra LLM call beyond the judge itself: the nudge text is a constant. Focus discipline
 * in the system prompts is the first line of defence (every iteration); this guardian is the
 * single safety net at the terminal point.
 *
 * Cost model: ~150-300 input + ~20-40 output tokens per judge invocation in generic mode;
 * +50-300 input in goal mode (the condition text itself, capped at 2000 chars in the user
 * block). Only triggered at the terminal point of a turn (not every iteration). Worst case
 * is one judge call per terminal point — the no-progress short-circuit keeps repeated
 * judges from firing when other guardians chain re-entries.
 *
 * Safety: a parse failure or LLM exception returns [GuardianDecision.Pass] (treat as "done")
 * — never block a turn on the judge being broken.
 */
class NextSpeakerJudgeGuardian(
    private val llmClient: LLMClient,
    private val configService: ConfigService
) : TurnCompletionGuardian {

    override val name: String = "next_speaker_judge"

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun check(context: GuardianContext): GuardianDecision {
        if (context.mode == TaskMode.CHAT) return GuardianDecision.Pass

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
            if (deliverableLikelyProduced(context)) {
                logger.info {
                    "[JUDGE] taskId=${context.taskId} priorReentries=${context.priorReentries} " +
                        "produced no new tool call, but a deliverable was already produced this turn " +
                        "(writeTools=${context.writeToolsExecutedInTurn}, mode=${context.mode}, " +
                        "respLen=${context.finalResponse.trim().length}) — finalizing SUCCESS, not INCOMPLETE"
                }
                return GuardianDecision.Pass
            }
            // One more nudge, once, while the turn is still early and nothing is delivered: the
            // second reminder escalates to re-include the JSON envelope schema. A single nudge
            // often does not unstick a weak local model that abandoned the task on iteration 1-3
            // with an intent announcement and a malformed/absent tool call; the schema reminder
            // targets exactly that. Bounded by [reentryBudget] so a turn past its early window,
            // or one that already burned the extra re-entry, still stops INCOMPLETE here.
            if (context.priorReentries < reentryBudget(context)) {
                logger.info {
                    "[JUDGE] taskId=${context.taskId} priorReentries=${context.priorReentries} " +
                        "produced no new tool call but turn is early (iteration=${context.iteration}/" +
                        "${context.maxIterations}) and nothing delivered - escalated re-entry with schema nudge"
                }
                return GuardianDecision.Reenter(
                    nudge = buildReentryNudge(context, escalated = true),
                    reason = "escalated re-entry: still no tool call, turn early, nothing delivered"
                )
            }
            logger.info {
                "[JUDGE] taskId=${context.taskId} priorReentries=${context.priorReentries} " +
                    "produced no new tool call (toolsUsed=${context.toolsUsed.size}, " +
                    "snapshot=${context.toolsUsedSizeAtPriorReentry}) — marking turn INCOMPLETE"
            }
            return GuardianDecision.Incomplete(
                reason = "prior re-entry produced no new tool call — request still not delivered"
            )
        }

        val response = context.finalResponse.trim()
        if (response.isEmpty()) return GuardianDecision.Pass

        if (looksClearlyDone(response, hasGoal = context.completionCondition != null)) {
            logger.debug { "[JUDGE] pre-filter says clearly done — skipping LLM call" }
            return GuardianDecision.Pass
        }

        return try {
            val verdict = callJudge(context, response)
            // Bounded re-entry on a MODEL verdict (request not yet delivered). Restored 2026-05
            // after manual-tests showed T4 models (qwen3.5:9b) silently abandon multi-step tasks:
            // they end turns on a bare intent announcement ("Now let me run git branch…", "I'll now
            // produce the analysis…") with no tool call. `<focus_discipline>` in the system prompt
            // did not change that behaviour, so the judge is the only terminal-point safety net.
            //
            // No extra LLM call beyond the judge we already ran - the nudge is a constant. The cap
            // is [reentryBudget]: one re-entry by default, or up to
            // [MAX_EARLY_JUDGE_REENTRIES] while the turn is still early and NOTHING has been
            // delivered (weak models often need a second, schema-carrying nudge). Once the budget
            // is spent a MODEL verdict falls through to INCOMPLETE (request never delivered); the
            // no-progress short-circuit above enforces the same cap. A USER/UNCERTAIN verdict Passes.
            if (verdict == NextSpeakerVerdict.MODEL && context.priorReentries < reentryBudget(context)) {
                val escalated = context.priorReentries > 0
                logger.info {
                    "[JUDGE] taskId=${context.taskId} verdict=MODEL (mid-task pause) — " +
                        "re-entry ${context.priorReentries + 1} (escalated=$escalated) with focus nudge"
                }
                GuardianDecision.Reenter(
                    nudge = buildReentryNudge(context, escalated = escalated),
                    reason = "judge verdict=MODEL: request not yet delivered (re-entry ${context.priorReentries + 1})"
                )
            } else if (verdict == NextSpeakerVerdict.MODEL && deliverableLikelyProduced(context)) {
                logger.info {
                    "[JUDGE] taskId=${context.taskId} verdict=MODEL and re-entry budget spent, but a " +
                        "deliverable was already produced this turn (writeTools=${context.writeToolsExecutedInTurn}, " +
                        "mode=${context.mode}, respLen=${context.finalResponse.trim().length}) — " +
                        "finalizing SUCCESS, not INCOMPLETE"
                }
                GuardianDecision.Pass
            } else if (verdict == NextSpeakerVerdict.MODEL) {
                logger.info {
                    "[JUDGE] taskId=${context.taskId} verdict=MODEL but re-entry budget " +
                        "spent (priorReentries=${context.priorReentries}) — marking turn INCOMPLETE"
                }
                GuardianDecision.Incomplete(
                    reason = "judge verdict=MODEL after re-entry budget spent — request not delivered"
                )
            } else {
                GuardianDecision.Pass
            }
        } catch (e: Exception) {
            logger.warn(e) { "[JUDGE] LLM call failed — defaulting to Pass: ${e.message}" }
            GuardianDecision.Pass
        }
    }

    /**
     * A terminal stall must be reported INCOMPLETE only when the agent ABANDONED the request
     * before producing a deliverable — never when it produced one and merely signed off with
     * forward-looking intent ("…fixed. Now let me compile to verify."), which weak local models do
     * routinely. Without this discriminator a correct edit (or a complete PLAN answer) is reported
     * as a failed turn (status INCOMPLETE, non-zero headless exit) purely because of the sign-off
     * phrasing — the dominant local-model instability observed when running the e2e harness on
     * qwen3.5:4b/9b: a fixed file or a full plan was already on hand, yet the turn returned failure.
     *
     *  - AGENT/SUBAGENT: a write/edit executed this turn → the file deliverable is on disk.
     *  - PLAN: writes are structurally impossible; the deliverable IS the answer text, so a
     *    substantial reply (a real step-by-step plan, not a bare "Let me produce a plan." stub) counts.
     *
     * Q&A AGENT turns that write nothing are still gated by the judge returning USER for a real
     * answer; the guardian only reaches this fallback when the model stalled. There, a no-write turn
     * with a short intent stub stays false → INCOMPLETE exactly as before. The deliberate trade-off:
     * a genuine multi-step task that wrote step A, was nudged once, and still did not do step B is now
     * reported SUCCESS — accepted, because the model failed despite the nudge (rare) and the common
     * false-INCOMPLETE on completed single-deliverable turns is the far more damaging, frequent case.
     */
    private fun deliverableLikelyProduced(context: GuardianContext): Boolean =
        TurnDeliverable.produced(
            context.fileWriteToolsExecutedInTurn,
            context.mode,
            context.finalResponse,
            isSubagent = context.runProfile == TurnRunProfile.SUBAGENT,
        )

    /**
     * How many judge-driven re-entries this turn may spend. One (the strict one-shot) by default;
     * up to [MAX_EARLY_JUDGE_REENTRIES] only while the turn is still early AND nothing has been
     * delivered yet - the exact window where an extra nudge can still recover a stalled weak model
     * without wasting an already-productive or already-late turn. Config-gated so the strict
     * one-shot can be restored. The early window shrinks as iterations are consumed, so a turn that
     * burns its budget naturally drops back to a single re-entry and then stops INCOMPLETE.
     */
    private fun reentryBudget(context: GuardianContext): Int {
        val extended = configService.getTyped<Boolean>(
            ConfigKeys.GENERAL_JUDGE_EXTENDED_REENTRY_ENABLED,
            context.taskId
        )
        if (!extended || deliverableLikelyProduced(context)) {
            return 1
        }
        val earlyLimit = (context.maxIterations * EARLY_REENTRY_ITERATION_FRACTION).toInt()
        return if (context.iteration <= earlyLimit) MAX_EARLY_JUDGE_REENTRIES else 1
    }

    /**
     * Hard SYSTEM nudge injected on the single bounded re-entry. Names the failure mode
     * (intent announced, no tool call) and demands the next concrete tool call. In goal mode
     * it re-injects the completion condition so the model re-anchors on the contract.
     *
     * Profile-aware: the [TurnRunProfile.SUBAGENT] branch drops the "write the file yourself"
     * instruction. A subagent's deliverable is the COMPLETE text it returns to the calling
     * agent — it does not write files (and read-only subagents like business-analyst physically
     * cannot). The DEFAULT (parent) nudge telling a read-only subagent to write the file sent it
     * into a write/verify spin that ended in loop-detection (observed manual run: business-analyst
     * re-reading DatabaseFactory.kt until aborted). The text is a constant (modulo the goal
     * clause) — building it costs no LLM call.
     */
    private fun buildReentryNudge(context: GuardianContext, escalated: Boolean = false): String {
        val goal = context.completionCondition?.takeIf { it.isNotBlank() }?.take(2000)
        val isSubagent = context.runProfile == TurnRunProfile.SUBAGENT
        return buildString {
            append("STOP — the turn is NOT finished. Your last reply announced intent or gave ")
            append("a partial result but issued NO tool call, so no progress was made on the ")
            append("user's request.\n\n")
            if (goal != null) {
                append("Completion condition that must be demonstrably met:\n")
                append(goal)
                append("\n\n")
            }
            if (isSubagent) {
                append("You are a delegated subagent: your deliverable is the COMPLETE result in ")
                append("your final text reply, returned to the agent that invoked you. You do NOT ")
                append("write files — the calling agent does that with your answer. Finish the ")
                append("remaining work with the next concrete tool call (read / search / analyze), ")
                append("then reply with the full result in plain prose — do not restate what you ")
                append("are about to do.")
            } else if (context.mode == TaskMode.PLAN) {
                // PLAN cannot write files — the deliverable is the plan TEXT. The DEFAULT "emit a write
                // tool" steer is nonsensical here and was observed to send PLAN turns into a wasteful
                // re-entry on a contract they cannot satisfy.
                append("You are in PLAN mode: you do NOT edit files — your deliverable is the COMPLETE, ")
                append("concrete plan as text in this reply (what to change, where, and why). Finish any ")
                append("remaining investigation with the next read/search tool call only if you still ")
                append("need it, then write the full plan in plain prose — do not restate what you are ")
                append("about to do. If the plan is already complete, reply with it only, no preamble.")
            } else {
                append("Re-read the original request, identify the ONE deliverable still missing, ")
                append("and emit the tool call that produces it in THIS response — do not restate ")
                append("what you are about to do. Filesystem deliverables require a write tool ")
                append("(create_new_file / advance_code_editing / multi_edit / code_editing); a ")
                append("chat description is not the file. A delegated read-only subagent returns ")
                append("its analysis to YOU — you must then write the file yourself. If every ")
                append("deliverable is genuinely already present, reply with the final result only, ")
                append("no preamble.")
            }
            if (escalated && !isSubagent && context.mode != TaskMode.PLAN) {
                // Second reminder for a model that ignored the first: re-state the exact structured
                // reply contract. The observed stall is an intent sentence with a malformed or
                // absent tool call, so showing the envelope shape is the concrete correction.
                append("\n\nYou already ignored one reminder. If your replies use the JSON envelope, ")
                append("it MUST be exactly this shape with a real tool in `actions`:\n")
                append("{\"actions\":[{\"tool\":\"TOOL_NAME\",\"args\":{...}}],\"response\":\"...\",\"intent\":\"implementation\"}\n")
                append("An empty `actions` array, prose without a tool call, or another intent ")
                append("announcement ends the turn now.")
            } else {
                append("\n\n")
                append("This is your only automatic reminder; if you stop again the turn ends.")
            }
        }
    }

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
            append(renderToolsUsed(context.toolsUsed))
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

    /**
     * Render the per-call tool list with counts so the judge can verify "use tool X N times"
     * requirements. [GuardianContext.toolsUsed] carries one entry per call (repeats preserved);
     * we group by name in first-seen order and annotate counts > 1 as `name ×N`. Without the
     * count the judge could not tell 1 call from 3 and wrongly re-entered completed multi-call
     * tasks (e.g. the "run run_terminal_command three times" git task).
     */
    private fun renderToolsUsed(tools: List<String>): String {
        if (tools.isEmpty()) return "(none)"
        val counts = LinkedHashMap<String, Int>()
        for (t in tools) counts[t] = (counts[t] ?: 0) + 1
        return counts.entries.joinToString(", ") { (name, n) -> if (n > 1) "$name ×$n" else name }
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
     * Strip optional markdown fences and isolate the FIRST balanced JSON object. Weak models
     * sometimes wrap the response in ```json ... ``` despite explicit instructions, and some
     * (observed: minimax-m3 on OpenRouter as the WEAK judge model) emit two concatenated
     * objects — `{"speaker":"model",...}{"speaker":"model",...}`. The previous
     * `indexOf('{')`..`lastIndexOf('}')` slice swallowed BOTH objects, producing invalid JSON
     * → parse failure → UNCERTAIN → the turn passed as SUCCESS even though the judge had
     * twice said "model" (continue). We now brace-count from the first `{`, respecting string
     * literals and escapes, and return the first complete object.
     */
    private fun extractJsonObject(raw: String): String {
        val stripped = raw
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = stripped.indexOf('{')
        if (start < 0) return stripped
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until stripped.length) {
            val c = stripped[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return stripped.substring(start, i + 1)
                }
            }
        }
        // Unbalanced (truncated stream) — fall back to the previous best-effort slice so a
        // single object that merely lost its closing fence still has a chance to parse.
        val end = stripped.lastIndexOf('}')
        return if (end > start) stripped.substring(start, end + 1) else stripped
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

        /**
         * Max re-entries the extended budget grants while a turn is early and undelivered. Two
         * (the strict one-shot plus one schema-carrying escalation) - kept below
         * [MAX_JUDGE_REENTRIES] so the registry cap stays a backstop, never the primary limit.
         */
        const val MAX_EARLY_JUDGE_REENTRIES = 2

        /**
         * The "still early" window as a fraction of the turn's iteration budget. Beyond it the
         * extended budget collapses to a single re-entry: a turn that has already spent most of
         * its iterations without delivering is stuck, not merely slow to start.
         */
        const val EARLY_REENTRY_ITERATION_FRACTION = 0.30

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

        private const val JUDGE_SYSTEM_PROMPT = """
You decide whether a coding agent should continue working or has finished the
user's original request.

The agent runs in a tool-loop: it can call tools (read files, edit files, run
commands) or reply with text. It just produced a text reply with no tool calls.
Your job is to decide if that text fully delivers what the user asked for, or
if the agent stopped before completing the work.

Return JSON only, no prose, no markdown fences:
{"speaker": "user" | "model", "reason": "short explanation"}

DECISION RULES (apply in order):

1. **Open-ended completion check.** Re-read the USER REQUEST. List every
   distinct deliverable it asks for (a file at a path, a count, N bullet
   points, a diff, a report, citations, a yes/no, etc.). For each item, check
   whether the agent's reply or the transcript "Tools used so far" already
   provides it.
   - If ANY deliverable is missing → "model".
   - If ALL deliverables are present in the reply → "user".

2. **Filesystem deliverables are the strictest case.** When the user request
   contains phrases like "save to", "produce a file at", "create … at",
   "output to ./tmp/...md", "write a report to <path>" — the deliverable is
   the FILE itself, not a chat summary. The agent must have called a write
   tool (create_new_file / advance_code_editing / multi_edit / code_editing)
   in this turn. If the transcript shows no write tool was used, return
   "model" — chat text describing the file is not the same as the file
   existing on disk.

3. **Multi-step requests must finish ALL steps.** If the user listed N
   numbered items, N files to read, or N commands to run, and the transcript
   shows fewer than N corresponding tool calls, return "model" — partial
   progress is not completion.

4. **Intent ≠ action.** Sentences like "Let me…", "Now I'll…", "I will run…",
   "Next, I'll examine…" announce intent. If the matching tool call is
   absent from "Tools used so far in this turn", return "model".

5. **Sub-step summaries do not finish the turn.** "Read the three files. I
   found X." is a status update, not a final answer, unless the user's
   request was only to find X.

6. **Self-claims of completion need evidence.** "Done.", "Task complete.",
   "All set." in isolation do not flip the verdict to "user" — verify against
   the deliverable list from rule 1. Only count them if every deliverable is
   already present.

6b. **Justified restraint IS completion.** If the request was to find/fix a bug
   or make a change, and the agent — after actually reading the relevant code —
   concludes WITH justification that no change is needed (the code is already
   correct, no bug exists), that is a COMPLETE answer → "user". Do not demand an
   edit the task does not require; "no change needed" backed by reasoning is the
   deliverable for a restraint task, not a stall.

7. **A clarifying question always wins.** If the reply ends with a genuine
   question to the user (not rhetorical), return "user" — the agent legitimately
   needs input.

8. **Conservative tie-break.** If you genuinely cannot tell, return "user" —
   never block a turn on a guess. Default to letting the user respond.

EXAMPLES:

USER REQUEST: "Save a report to ./tmp/foo.md with 3 bullet citations."
TOOLS USED: read_file
AGENT REPLY: "I have analyzed the files. The 3 key citations are at A:1, B:2, C:3."
→ {"speaker": "model", "reason": "user wanted a saved file, no write tool was called"}

USER REQUEST: "Save a report to ./tmp/foo.md with 3 bullet citations."
TOOLS USED: read_file, create_new_file
AGENT REPLY: "Report saved at ./tmp/foo.md with 3 citations."
→ {"speaker": "user", "reason": "write tool ran in transcript, deliverable met"}

USER REQUEST: "Read 10 files and produce an ASCII diagram."
TOOLS USED: read_file x2
AGENT REPLY: "I have read the first two files. They look related."
→ {"speaker": "model", "reason": "8 of 10 files unread, no diagram yet"}

USER REQUEST: "Find the maxIterations for each mode."
TOOLS USED: read_file
AGENT REPLY: "PLAN: 100 (TurnLoopConfig.kt:94). AGENT: 100 (line 129). CHAT: no loop."
→ {"speaker": "user", "reason": "all three modes addressed with citations"}

USER REQUEST: "Print the current git branch, last 5 commits, and short status."
TOOLS USED: run_terminal_command x1 (git status -s)
AGENT REPLY: "Here is the status. (branch and log not shown)"
→ {"speaker": "model", "reason": "2 of 3 requested git outputs missing"}

USER REQUEST: "Now let me check the file."
TOOLS USED: (none)
AGENT REPLY: "Now let me find the exact line numbers."
→ {"speaker": "model", "reason": "intent announced, no tool call made"}

USER REQUEST: "Which file owns ContextBudget?"
TOOLS USED: grep_search
AGENT REPLY: "ContextBudget is defined in core/services/context/ContextBudget.kt."
→ {"speaker": "user", "reason": "concrete file:answer to a single-fact question"}

USER REQUEST: "Find and fix the bug in gcd()."
TOOLS USED: read_file
AGENT REPLY: "I reviewed gcd(). It implements Euclid's algorithm correctly — the loop terminates and edge cases hold. No bug, no change needed."
→ {"speaker": "user", "reason": "justified restraint: 'no change needed' with reasoning is the complete answer"}
"""

        private const val GOAL_AWARE_JUDGE_PROMPT = """
You decide whether a coding agent has met an explicit user-defined completion
condition (set via "/goal"). The condition is verbatim user input — treat it
as the contract the agent must fulfil before the turn can end.

The agent runs in a tool-loop and can call tools (read files, edit files, run
commands) or reply with text. It just produced a text reply with no tool calls.
Your job: decide if the goal condition is now demonstrably satisfied from
transcript evidence ALONE.

Return JSON only, no prose, no markdown fences:
{"speaker": "user" | "model", "reason": "short explanation"}

DECISION RULES (apply in order):

1. **Decompose the goal.** Read the COMPLETION CONDITION and list every
   atomic sub-clause:
   - actions ("fix the bug", "add a regression test", "rename X to Y")
   - outputs ("file at <path>", "all tests pass", "report with N citations")
   - scope qualifiers ("in src/test/auth", "for every adapter", "without
     touching callers")
   For each sub-clause, check the transcript for direct evidence.

2. **Transcript evidence > agent self-claims.** "Done." or "tests pass" in
   the reply is NOT evidence. Evidence is:
   - A tool call in "Tools used so far" that produced output consistent with
     the sub-clause, OR
   - A tool call output already quoted in the agent reply (verifiable).
   If the agent claims completion of a sub-clause that has no matching tool
   call in the transcript, treat that sub-clause as UNMET.

3. **Filesystem deliverables require a write tool.** "Save to <path>",
   "create <path>", "write a report at <path>" → demands a successful call
   to create_new_file / advance_code_editing / multi_edit / code_editing
   on that path. The agent describing the file in chat is not the file.

4. **Test/run claims require a run tool.** "All tests pass", "build green",
   "lint clean" → demands a run_terminal_command or run_code call whose
   output supports the claim. No run = "model".

5. **Scope matching is verbatim.** If the goal says "in core/services/turn/",
   evidence from "core/services/llm/" does not count. Match the literal
   scope of every clause.

6. **Partial completion = "model".** If 4 of 5 sub-clauses have evidence
   and 1 does not, return "model" with reason naming the unmet clause. The
   goal is the contract, not "most of the contract".

7. **Genuine clarifying question = "user".** If the agent's reply ends with
   a real question that blocks further progress without user input, return
   "user" — the agent cannot proceed alone.

8. **Conservative tie-break.** If you genuinely cannot tell whether
   evidence is present, return "user". Never push the agent into another
   iteration on a guess.

EXAMPLES:

GOAL: "all tests in src/test/auth pass"
TOOLS USED: code_editing
AGENT REPLY: "Fixed the import. Tests should pass now."
→ {"speaker": "model", "reason": "no test runner called, claim has no evidence"}

GOAL: "all tests in src/test/auth pass"
TOOLS USED: code_editing, run_terminal_command
AGENT REPLY: "Migration applied. pytest src/test/auth: 23 passed, 0 failed."
→ {"speaker": "user", "reason": "scoped pytest output matches the goal"}

GOAL: "all tests in src/test/auth pass"
TOOLS USED: code_editing, run_terminal_command
AGENT REPLY: "Ran the full test suite: 47 passed, 0 failed."
→ {"speaker": "model", "reason": "ran full suite but did not show auth scope output specifically"}

GOAL: "fix the off-by-one in config parser and add a regression test"
TOOLS USED: code_editing
AGENT REPLY: "Fixed config_parser.py:88. Done."
→ {"speaker": "model", "reason": "no regression test added — second clause unmet"}

GOAL: "fix the off-by-one in config parser and add a regression test"
TOOLS USED: code_editing, create_new_file, run_terminal_command
AGENT REPLY: "Fixed config_parser.py:88. Added tests/test_config_off_by_one.py — pytest shows 1 passed."
→ {"speaker": "user", "reason": "both clauses have transcript evidence"}

GOAL: "save analysis.md with 5 file:line citations to ./tmp/c9/"
TOOLS USED: read_file x3
AGENT REPLY: "I have collected 5 citations. Here they are: A:1, B:2, C:3, D:4, E:5."
→ {"speaker": "model", "reason": "user wanted a saved file, no write tool called"}

GOAL: "rename taskId to sessionId in TaskRepository.kt and SubtaskRepository.kt only"
TOOLS USED: multi_edit
AGENT REPLY: "Renamed in both files."
→ {"speaker": "user", "reason": "scoped multi_edit covers both target files"}

GOAL: "rename taskId to sessionId in TaskRepository.kt and SubtaskRepository.kt only"
TOOLS USED: multi_edit, grep_search
AGENT REPLY: "Renamed in both files. Also updated 3 callers in CoreApiRouter.kt."
→ {"speaker": "user", "reason": "core scope met; out-of-scope edits are a scope violation but not an unmet goal"}
"""
    }
}
