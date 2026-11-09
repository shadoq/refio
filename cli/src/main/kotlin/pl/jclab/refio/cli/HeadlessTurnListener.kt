package pl.jclab.refio.cli

import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.ToolCallData
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.TurnResult
import pl.jclab.refio.core.services.turn.ToolBatchSummary
import pl.jclab.refio.core.services.turn.TurnEventListener
import pl.jclab.refio.core.services.turn.TurnJsonUtils

private val logger = dualLogger("HeadlessTurn")

/**
 * Progress listener for headless ([--headless]) PLAN/AGENT runs.
 *
 * Without it, `agentRouter.runTurn` runs silently — the console shows nothing until the
 * final result/error, which makes a hang or timeout look like a black box. This mirrors
 * every turn/tool event to BOTH:
 *  - the log file (`~/.refio/refio-cli.log`) via [dualLogger], so a finished run leaves a
 *    full trace to post-mortem, and
 *  - the console (stderr), so progress is visible live.
 *
 * All console output goes to **stderr** — stdout stays reserved for the run.json document
 * (`--output json`) / final text result, so piping/redirection stays clean.
 */
class HeadlessTurnListener : TurnEventListener {

    // Tool-call indices already announced to stderr this turn — so a model that streams a call's
    // arguments across many deltas yields ONE "building" line, not one per fragment.
    private val announcedToolCallProgress = mutableSetOf<Int>()

    // Which subagent a line belongs to. The turn events carry no subagent name, so the name is
    // taken from the delegating call and handed to the next subagent turn that starts. With
    // subagents running one at a time this is exact; with several at once at the same depth a
    // tool line cannot be attributed and is printed without a name.
    private val lock = Any()
    // Delegating call id -> subagent name, until that subagent's turn starts. A call that fails
    // before starting one (an unknown name) is dropped when it completes, so its name does not
    // pass to the next subagent.
    private val pendingSubagentNames = LinkedHashMap<String, String>()
    private val activeSubagents = LinkedHashMap<String, ActiveSubagent>()

    private data class ActiveSubagent(val name: String, val depth: Int)

    private fun err(line: String) = System.err.println(line)

    private fun clip(s: String, max: Int): String {
        val flat = s.replace("\n", " ").replace("\r", "")
        return if (flat.length > max) flat.take(max) + "…" else flat
    }

    override fun onTurnStarted(taskId: String, mode: TaskMode, runId: String, parentRunId: String?, depth: Int) {
        val tag = if (depth > 0) {
            val name = synchronized(lock) {
                val next = pendingSubagentNames.keys.firstOrNull()
                (next?.let { pendingSubagentNames.remove(it) } ?: "subagent").also {
                    activeSubagents[runId] = ActiveSubagent(it, depth)
                }
            }
            "subagent $name (depth=$depth)"
        } else {
            "turn"
        }
        logger.info { "[HEADLESS] $tag started: mode=$mode runId=$runId parent=$parentRunId" }
        announcedToolCallProgress.clear()
        err("▶ $tag started (mode=$mode)")
    }

    override fun onToolExecutionStarted(taskId: String, toolCall: ToolCallData) {
        if (toolCall.name == SUBAGENT_TOOL) {
            val name = runCatching { TurnJsonUtils.parseJsonToMap(toolCall.arguments)["subagent_name"] }
                .getOrNull()?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            synchronized(lock) { pendingSubagentNames[toolCall.id] = name ?: "subagent" }
        }
        val args = clip(toolCall.arguments, 120)
        val owner = currentSubagentTag()
        logger.info { "[HEADLESS] ${owner}tool start: ${toolCall.name} args=$args" }
        err("  $owner→ ${toolCall.name}  $args")
    }

    override fun onToolExecutionCompleted(taskId: String, toolCall: ToolCallData, result: String, success: Boolean) {
        if (toolCall.name == SUBAGENT_TOOL) {
            synchronized(lock) { pendingSubagentNames.remove(toolCall.id) }
        }
        val owner = currentSubagentTag()
        logger.info { "[HEADLESS] ${owner}tool done: ${toolCall.name} success=$success result=${clip(result, 200)}" }
        err("  $owner${if (success) "✓" else "✗ FAILED"} ${toolCall.name}")
    }

    /**
     * "[name] " for a line printed while a subagent works, empty for the main turn. The deepest
     * running subagent is the one working: its caller waits for it. Two at the same depth run in
     * parallel and either could own the line, so no name is given then.
     */
    private fun currentSubagentTag(): String = synchronized(lock) {
        val deepest = activeSubagents.values.maxOfOrNull { it.depth } ?: return ""
        val working = activeSubagents.values.filter { it.depth == deepest }
        if (working.size == 1) "[${working.single().name}] " else ""
    }

    override fun onToolStreamChunk(taskId: String, toolCallId: String, delta: String, accumulated: String) {
        logger.debug { "[HEADLESS] tool chunk: id=$toolCallId +${delta.length} chars (total=${accumulated.length})" }
    }

    override fun onToolBatchCompleted(taskId: String, summary: ToolBatchSummary.BatchSummary) {
        logger.info { "[HEADLESS] batch done: ${summary.label} tools=${summary.toolCount}" }
    }

    override fun onStreamChunk(taskId: String, delta: String, accumulated: String) {
        logger.debug { "[HEADLESS] llm chunk: +${delta.length} chars (total=${accumulated.length})" }
    }

    override fun onLlmToolCallProgress(taskId: String, index: Int, toolName: String?, accumulatedArguments: String) {
        logger.debug { "[HEADLESS] tool-call building: [$index] ${toolName ?: "?"} args=${clip(accumulatedArguments, 120)}" }
        // Announce once per call (the first delta carrying a name) so streaming tool-call assembly is
        // visible without the per-fragment spam; later argument deltas stay at debug in the log file.
        if (toolName != null && announcedToolCallProgress.add(index)) {
            err("  ⚙ building $toolName")
        }
    }

    override fun onTurnCompleted(taskId: String, result: TurnResult, runId: String, parentRunId: String?, depth: Int) {
        if (depth > 0) {
            val name = synchronized(lock) { activeSubagents.remove(runId)?.name } ?: "subagent"
            val stop = result.stopReason.name.lowercase()
            logger.info {
                "[HEADLESS] subagent $name complete (depth=$depth): success=${result.success} " +
                    "iterations=${result.iterations} stop=$stop"
            }
            err(
                "■ subagent $name finished: success=${result.success}, iterations=${result.iterations}, stop=$stop"
            )
            return
        }
        logger.info {
            "[HEADLESS] turn complete: success=${result.success} iterations=${result.iterations} " +
                "tokensIn=${result.tokensIn} tokensOut=${result.tokensOut} cost=${result.cost} " +
                "tools=${result.toolsUsed.size}"
        }
        err(
            "■ turn complete: success=${result.success}, iterations=${result.iterations}, " +
                "tokens=${result.tokensIn + result.tokensOut}, cost=\$${String.format(java.util.Locale.US, "%.4f", result.cost)}"
        )
    }

    private companion object {
        const val SUBAGENT_TOOL = "invoke_subagent"
    }
}
