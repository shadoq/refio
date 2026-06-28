package pl.jclab.refio.core.services.turn

import pl.jclab.refio.core.db.ApprovalStatus
import pl.jclab.refio.core.db.Subtask
import pl.jclab.refio.core.db.SubtaskKind
import pl.jclab.refio.core.db.TaskStatus
import pl.jclab.refio.core.tools.base.ToolCategory
import pl.jclab.refio.core.tools.base.ToolMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks the tool classification behind the "read forever, never deliver" consolidation nudge.
 *
 * WHY this matters: a top-level AGENT run that keeps reading/searching without ever writing,
 * persisting, or delivering loses its own evidence — older tool outputs are compressed out of
 * RECENT_WORK before the model assembles a result (observed: session e05ede28 read 25+ adapter
 * files over 38 min, wrote nothing, user cancelled). The nudge only helps if "still just reading"
 * is told apart from "made progress worth keeping" — that boundary is exactly these two functions.
 */
class TurnToolExecutorClassificationTest {

    // ---- isGatheringCall: what accumulates the read-only spree counter ----

    @Test
    fun `reads and searches are gathering calls`() {
        // These are the calls whose unbounded repetition is the pathology — they must accumulate.
        assertTrue(TurnToolExecutor.isGatheringCall("read_file", ToolMode.READ_ONLY, ToolCategory.DATA_PRODUCING))
        assertTrue(TurnToolExecutor.isGatheringCall("grep_search", ToolMode.READ_ONLY, ToolCategory.DATA_PRODUCING))
        assertTrue(TurnToolExecutor.isGatheringCall("file_search", ToolMode.READ_ONLY, ToolCategory.DATA_PRODUCING))
    }

    @Test
    fun `write tools are not gathering`() {
        // Writing the deliverable is the opposite of gathering — it must never inflate the spree.
        assertFalse(TurnToolExecutor.isGatheringCall("create_new_file", ToolMode.WRITE, ToolCategory.FILE_PRODUCING))
    }

    @Test
    fun `system state tools are not gathering`() {
        // memory/tasks/messages are agent-state plumbing, not "reading the codebase". A model that
        // persists to memory between reads is consolidating, not gathering — must not accumulate.
        assertFalse(TurnToolExecutor.isGatheringCall("memory", ToolMode.READ_ONLY, ToolCategory.SYSTEM))
        assertFalse(TurnToolExecutor.isGatheringCall("tasks", ToolMode.READ_ONLY, ToolCategory.SYSTEM))
    }

    @Test
    fun `think is never gathering regardless of its category`() {
        // `think` is reflection between actions. It must neither inflate the spree nor (below)
        // reset it — it carries no information about read-vs-deliver progress.
        assertFalse(TurnToolExecutor.isGatheringCall("think", ToolMode.READ_ONLY, ToolCategory.SYSTEM))
        assertFalse(TurnToolExecutor.isGatheringCall("think", ToolMode.READ_ONLY, ToolCategory.DATA_PRODUCING))
    }

    // ---- isConsolidationProgressCall: what RESETS the read-only spree counter ----

    @Test
    fun `writing a file is progress`() {
        // The whole point: producing the deliverable resets the "never delivered" streak.
        assertTrue(TurnToolExecutor.isConsolidationProgressCall("create_new_file", ToolMode.WRITE, ToolCategory.FILE_PRODUCING))
        assertTrue(TurnToolExecutor.isConsolidationProgressCall("advance_code_editing", ToolMode.WRITE, ToolCategory.FILE_PRODUCING))
    }

    @Test
    fun `persisting or delivering via system tools is progress`() {
        // Persisting findings (memory) IS the consolidation the nudge asks for — calling it must
        // reset the streak so the model is not nagged after it complied. Same for delivering/asking.
        assertTrue(TurnToolExecutor.isConsolidationProgressCall("memory", ToolMode.READ_ONLY, ToolCategory.SYSTEM))
        assertTrue(TurnToolExecutor.isConsolidationProgressCall("tasks", ToolMode.READ_ONLY, ToolCategory.SYSTEM))
        assertTrue(TurnToolExecutor.isConsolidationProgressCall("answer_message", ToolMode.READ_ONLY, ToolCategory.SYSTEM))
    }

    @Test
    fun `delegating the work is progress`() {
        // Offloading to a stronger model / subagent is a legitimate escape from the read spree.
        assertTrue(TurnToolExecutor.isConsolidationProgressCall("delegate_to_strong_model", ToolMode.WRITE, ToolCategory.SYSTEM))
        assertTrue(TurnToolExecutor.isConsolidationProgressCall("invoke_subagent", ToolMode.WRITE, ToolCategory.SYSTEM))
    }

    @Test
    fun `reads and think do not count as progress`() {
        // A batch of pure reads (or only `think`) made no consolidation progress — the streak must
        // keep climbing toward the nudge, never silently reset.
        assertFalse(TurnToolExecutor.isConsolidationProgressCall("read_file", ToolMode.READ_ONLY, ToolCategory.DATA_PRODUCING))
        assertFalse(TurnToolExecutor.isConsolidationProgressCall("grep_search", ToolMode.READ_ONLY, ToolCategory.DATA_PRODUCING))
        assertFalse(TurnToolExecutor.isConsolidationProgressCall("think", ToolMode.READ_ONLY, ToolCategory.SYSTEM))
    }

    @Test
    fun `read and progress classifications are mutually exclusive for the same call`() {
        // Sanity: no single call should both accumulate and reset — that would make the counter
        // ill-defined. (Holds because gathering excludes SYSTEM/WRITE, progress requires them.)
        val cases = listOf(
            Triple("read_file", ToolMode.READ_ONLY, ToolCategory.DATA_PRODUCING),
            Triple("create_new_file", ToolMode.WRITE, ToolCategory.FILE_PRODUCING),
            Triple("memory", ToolMode.READ_ONLY, ToolCategory.SYSTEM),
            Triple("think", ToolMode.READ_ONLY, ToolCategory.SYSTEM),
            Triple("invoke_subagent", ToolMode.WRITE, ToolCategory.SYSTEM),
        )
        for ((name, mode, category) in cases) {
            assertFalse(
                TurnToolExecutor.isGatheringCall(name, mode, category) &&
                    TurnToolExecutor.isConsolidationProgressCall(name, mode, category),
                "$name must not be both gathering and progress"
            )
        }
    }

    // ---- isConsolidationProgressCall: a no-op write is NOT progress (P1) ----

    @Test
    fun `a no-op write does not count as consolidation progress`() {
        // WHY: session f998771b / c19. advance_code_editing returned content identical to the file
        // (the editing model could not act on the description), yet it is mode=WRITE so it reset the
        // read-only spree counter — masking an ongoing read-forever loop that then ran to maxIterations.
        // A write that changed nothing is the OPPOSITE of progress; it must NOT reset the streak.
        assertFalse(
            TurnToolExecutor.isConsolidationProgressCall(
                "advance_code_editing", ToolMode.WRITE, ToolCategory.FILE_PRODUCING, isNoopWrite = true
            )
        )
        assertFalse(
            TurnToolExecutor.isConsolidationProgressCall(
                "create_new_file", ToolMode.WRITE, ToolCategory.FILE_PRODUCING, isNoopWrite = true
            )
        )
    }

    @Test
    fun `a real (non-no-op) write is still progress`() {
        // The default path is unchanged: a write that actually changed bytes resets the streak.
        assertTrue(
            TurnToolExecutor.isConsolidationProgressCall(
                "advance_code_editing", ToolMode.WRITE, ToolCategory.FILE_PRODUCING, isNoopWrite = false
            )
        )
    }

    // ---- repeated failed-edit nudge: change approach after thrashing one file ----

    @Test
    fun `edit path is extracted from path, file_path or file keys`() {
        assertEquals("src/A.kt", TurnToolExecutor.extractEditPath("""{"path":"src/A.kt","content":"x"}"""))
        assertEquals("src/B.kt", TurnToolExecutor.extractEditPath("""{"file_path":"src/B.kt"}"""))
        assertEquals("src/C.kt", TurnToolExecutor.extractEditPath("""{"file":"src/C.kt"}"""))
    }

    @Test
    fun `edit path extraction returns null for blank or pathless args`() {
        assertEquals(null, TurnToolExecutor.extractEditPath(""))
        assertEquals(null, TurnToolExecutor.extractEditPath("""{"content":"no path here"}"""))
    }

    @Test
    fun `no nudge below the failed-edit threshold`() {
        // One prior failure is normal iteration, not thrashing - stay silent.
        assertEquals(null, TurnToolExecutor.repeatedFailedEditNudgeText("src/A.kt", priorFailedEdits = 0))
        assertEquals(null, TurnToolExecutor.repeatedFailedEditNudgeText("src/A.kt", priorFailedEdits = 1))
    }

    @Test
    fun `nudge fires at the threshold and names the file and attempt count`() {
        // 2 prior failures => this is the 3rd attempt; the model should change tactics.
        val nudge = TurnToolExecutor.repeatedFailedEditNudgeText("src/A.kt", priorFailedEdits = 2)
        assertTrue(nudge != null && nudge.contains("change approach"))
        assertTrue(nudge!!.contains("src/A.kt"))
        assertTrue(nudge.contains("attempt 3"))
    }

    // ---- repeated execution-failure nudge: change approach after run_code/run_terminal thrashing ----

    @Test
    fun `no exec-failure nudge below the consecutive-failure threshold`() {
        // One failed run is normal trial-and-error - stay silent.
        assertEquals(null, TurnToolExecutor.repeatedExecFailureNudgeText("run_code", priorConsecutiveFailures = 0))
        assertEquals(null, TurnToolExecutor.repeatedExecFailureNudgeText("run_code", priorConsecutiveFailures = 1))
    }

    @Test
    fun `exec-failure nudge fires at the threshold and names the tool and attempt count`() {
        // 2 prior consecutive failures => this is the 3rd run in a row; isolate the failure.
        val nudge = TurnToolExecutor.repeatedExecFailureNudgeText("run_code", priorConsecutiveFailures = 2)
        assertTrue(nudge != null && nudge.contains("change approach"))
        assertTrue(nudge!!.contains("run_code"))
        assertTrue(nudge.contains("attempt 3"))
    }

    @Test
    fun `only run_code and run_terminal_command are execution tools`() {
        // The DB-querying nudge only triggers for these; guard the set so reads/edits never qualify.
        assertTrue("run_code" in TurnToolExecutor.EXECUTION_TOOL_NAMES)
        assertTrue("run_terminal_command" in TurnToolExecutor.EXECUTION_TOOL_NAMES)
        assertFalse("read_file" in TurnToolExecutor.EXECUTION_TOOL_NAMES)
        assertFalse("advance_code_editing" in TurnToolExecutor.EXECUTION_TOOL_NAMES)
    }

    // ---- re-read-after-write suppression (A) ----

    private fun sub(
        id: String,
        kind: SubtaskKind,
        status: TaskStatus,
        orderIndex: Int,
        paramsJson: String? = null
    ) = Subtask(
        id = id, taskId = "t1", orderIndex = orderIndex, kind = kind, status = status,
        description = "", paramsJson = paramsJson, stepPlanJson = null, summary = null,
        requiresApproval = false, approvalStatus = ApprovalStatus.NOT_REQUIRED, approvedAt = null,
        result = null, errorMessage = null, errorStacktrace = null, llmModel = null,
        llmProvider = null, inputTokens = 0, outputTokens = 0, costUsd = 0.0, latencyMs = 0,
        snapshotIdBeforeWrite = null, createdAt = 0, updatedAt = 0, startedAt = null, completedAt = null
    )

    @Test
    fun `suppresses a re-read of a file written this turn with no failure since`() {
        // The qwen3.5:122b pattern: create_new_file(success) then read_file the same path repeatedly.
        val subs = listOf(
            sub("w", SubtaskKind.CREATE_NEW_FILE, TaskStatus.SUCCESS, 1, """{"path":"game.html","content":"x"}"""),
            sub("r", SubtaskKind.READ_FILE, TaskStatus.PENDING, 2, """{"path":"game.html"}""")
        )
        assertTrue(TurnToolExecutor.shouldSuppressReadAfterWrite("game.html", subs, currentSubtaskId = "r"))
    }

    @Test
    fun `does NOT suppress when a tool FAILED after the last write (model may be debugging)`() {
        // A failed run/edit after the write re-enables reads — the model legitimately needs fresh
        // content (e.g. to fix a build error or match an exact string for code_editing). This also
        // prevents a suppression loop: a failed code_editing → read → re-suppress → stuck.
        val subs = listOf(
            sub("w", SubtaskKind.ADVANCE_CODE_EDITING, TaskStatus.SUCCESS, 1, """{"path":"game.html"}"""),
            sub("f", SubtaskKind.RUN_CODE, TaskStatus.FAILED, 2, """{"language":"javascript"}"""),
            sub("r", SubtaskKind.READ_FILE, TaskStatus.PENDING, 3, """{"path":"game.html"}""")
        )
        assertFalse(TurnToolExecutor.shouldSuppressReadAfterWrite("game.html", subs, currentSubtaskId = "r"))
    }

    @Test
    fun `does NOT suppress when the path was never written`() {
        val subs = listOf(
            sub("w", SubtaskKind.CREATE_NEW_FILE, TaskStatus.SUCCESS, 1, """{"path":"other.html"}"""),
            sub("r", SubtaskKind.READ_FILE, TaskStatus.PENDING, 2, """{"path":"game.html"}""")
        )
        assertFalse(TurnToolExecutor.shouldSuppressReadAfterWrite("game.html", subs, currentSubtaskId = "r"))
    }

    @Test
    fun `does NOT suppress when the write of that path FAILED`() {
        // Only a SUCCESSFUL write makes the content authoritative; a failed write means nothing landed.
        val subs = listOf(
            sub("w", SubtaskKind.ADVANCE_CODE_EDITING, TaskStatus.FAILED, 1, """{"path":"game.html"}"""),
            sub("r", SubtaskKind.READ_FILE, TaskStatus.PENDING, 2, """{"path":"game.html"}""")
        )
        assertFalse(TurnToolExecutor.shouldSuppressReadAfterWrite("game.html", subs, currentSubtaskId = "r"))
    }

    @Test
    fun `a failure BEFORE the write does not block suppression`() {
        // Only failures AFTER the last successful write matter — an earlier failed attempt that the
        // write then resolved must not keep reads open forever.
        val subs = listOf(
            sub("f", SubtaskKind.RUN_CODE, TaskStatus.FAILED, 1, """{"language":"javascript"}"""),
            sub("w", SubtaskKind.CREATE_NEW_FILE, TaskStatus.SUCCESS, 2, """{"path":"game.html"}"""),
            sub("r", SubtaskKind.READ_FILE, TaskStatus.PENDING, 3, """{"path":"game.html"}""")
        )
        assertTrue(TurnToolExecutor.shouldSuppressReadAfterWrite("game.html", subs, currentSubtaskId = "r"))
    }

    @Test
    fun `blank read path is never suppressed`() {
        val subs = listOf(sub("w", SubtaskKind.CREATE_NEW_FILE, TaskStatus.SUCCESS, 1, """{"path":""}"""))
        assertFalse(TurnToolExecutor.shouldSuppressReadAfterWrite("", subs, currentSubtaskId = "r"))
    }

    @Test
    fun `skip notice names the path and points to the diff`() {
        val notice = TurnToolExecutor.readAfterWriteSkipNotice("game.html")
        assertTrue(notice.contains("game.html"))
        assertTrue(notice.contains("changeSummary"))
        assertTrue(notice.contains("re-enables reads"), "must tell the model how a re-read becomes allowed")
    }

    @Test
    fun `the no-op flag only demotes writes, never system or delegation progress`() {
        // isNoopWrite only ever describes a WRITE diff. Defensive: even if passed true, persisting
        // (memory) / delegating remain progress — their "progress" is not a byte-diff. Guards against
        // gating the whole expression on the flag instead of just the WRITE branch.
        assertTrue(
            TurnToolExecutor.isConsolidationProgressCall(
                "memory", ToolMode.READ_ONLY, ToolCategory.SYSTEM, isNoopWrite = true
            )
        )
        assertTrue(
            TurnToolExecutor.isConsolidationProgressCall(
                "delegate_to_strong_model", ToolMode.WRITE, ToolCategory.SYSTEM, isNoopWrite = true
            )
        )
    }
}
