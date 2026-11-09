package pl.jclab.refio.core.debug

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.api.MultiAgentInstanceResponse
import pl.jclab.refio.core.api.MultiAgentSessionResponse
import pl.jclab.refio.core.db.ApiLog
import pl.jclab.refio.core.db.ApprovalStatus
import pl.jclab.refio.core.db.ChatMessage
import pl.jclab.refio.core.db.ExecutionMode
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.Subtask
import pl.jclab.refio.core.db.SubtaskKind
import pl.jclab.refio.core.db.Task
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.TaskStatus
import pl.jclab.refio.core.db.ToolCallData
import pl.jclab.refio.core.db.repositories.ApiLogRepository
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.db.repositories.TaskStateRepository
import pl.jclab.refio.core.db.repositories.TaskStateRow
import pl.jclab.refio.core.services.longtask.LongTaskSpec
import pl.jclab.refio.core.services.longtask.LongTaskState
import pl.jclab.refio.core.services.longtask.LongTaskStateCodec
import pl.jclab.refio.core.services.longtask.LongTaskStatus
import pl.jclab.refio.core.services.longtask.RoundRecord
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionDebugExporterTest {

    private val taskRepository = mockk<TaskRepository>()
    private val subtaskRepository = mockk<SubtaskRepository>()
    private val apiLogRepository = mockk<ApiLogRepository>()
    private val chatMessageRepository = mockk<ChatMessageRepository>()
    private val exporter = SessionDebugExporter(
        taskRepository, subtaskRepository, apiLogRepository, chatMessageRepository
    )

    private fun task(tokensIn: Int = 100, tokensOut: Int = 20, costUsd: Double = 0.5) = Task(
        id = "t1", name = "bench", mode = TaskMode.AGENT, status = TaskStatus.values().first(),
        readOnly = false, pinned = false, executionMode = ExecutionMode.AUTO,
        requiresPlanApproval = false, planApproved = false, uiState = null, coreApiVersion = null,
        projectId = "p", projectPath = "/p",
        tokensIn = tokensIn, tokensOut = tokensOut, costUsd = costUsd,
        createdAt = 1_000, updatedAt = 5_000,
    )

    private fun subtask(orderIndex: Int = 0, errorMessage: String? = null) = Subtask(
        id = "s$orderIndex", taskId = "t1", orderIndex = orderIndex,
        kind = SubtaskKind.values().first(), status = TaskStatus.values().first(),
        description = "do thing", paramsJson = null, stepPlanJson = null, summary = null,
        requiresApproval = false, approvalStatus = ApprovalStatus.values().first(), approvedAt = null,
        result = null, errorMessage = errorMessage, errorStacktrace = null,
        llmModel = "qwen3.5:9b", llmProvider = "ollama",
        inputTokens = 10, outputTokens = 5, costUsd = 0.1, latencyMs = 200,
        snapshotIdBeforeWrite = null, createdAt = 1_000, updatedAt = 2_000,
        startedAt = 1_000, completedAt = 2_000,
    )

    private fun apiLog(errorMessage: String? = null) = ApiLog(
        id = "a1", taskId = "t1", subtaskId = null, provider = "ollama", model = "qwen3.5:9b",
        endpoint = "http://x", requestSource = "agent", requestPayload = "{}", responsePayload = "{}",
        httpStatus = 200, inputTokens = 50, outputTokens = 10, costUsd = 0.2, latencyMs = 300,
        errorMessage = errorMessage, errorType = errorMessage?.let { "Error" }, createdAt = 1_500,
    )

    private fun msg(
        role: MessageRole,
        content: String,
        toolCalls: List<ToolCallData>? = null,
        metadata: String? = null,
    ) = ChatMessage(
        id = "m-${content.hashCode()}", taskId = "t1", role = role, content = content,
        metadata = metadata, toolCalls = toolCalls, toolCallId = null,
        tokensIn = null, tokensOut = null, cost = null, createdAt = 2_000,
    )

    private fun stub(
        task: Task? = task(),
        subtasks: List<Subtask> = listOf(subtask()),
        apiLogs: List<ApiLog> = listOf(apiLog()),
        messages: List<ChatMessage> = listOf(msg(MessageRole.ASSISTANT, "done")),
    ) {
        every { taskRepository.findById(any()) } returns task
        every { subtaskRepository.findByTaskId(any()) } returns subtasks
        every { apiLogRepository.findByTaskId(any()) } returns apiLogs
        every { chatMessageRepository.findByTaskId(any()) } returns messages
    }

    @Test
    fun `contextOverflow surfaces in run json metrics when a task overflowed the window`() {
        ContextOverflowTracker.reset()
        try {
            stub()
            // Before any overflow: false (silence must never read as a truncated run).
            val clean = exporter.export("t1", SessionDebugOptions.forLevel(DebugLevel.STANDARD))
            assertEquals(false, clean.metrics.contextOverflow)

            // After the adapter/turn-loop records an overflow for this task: true.
            ContextOverflowTracker.markOverflow("t1")
            val overflowed = exporter.export("t1", SessionDebugOptions.forLevel(DebugLevel.STANDARD))
            assertTrue(overflowed.metrics.contextOverflow, "overflow must propagate to run.json")
            assertTrue(
                exporter.toJson(overflowed).contains("\"contextOverflow\": true"),
                "run.json must carry contextOverflow=true for the e2e harness (docs/0061)"
            )
        } finally {
            ContextOverflowTracker.reset()
        }
    }

    @Test
    fun `toJson carries schemaVersion and session metrics`() {
        stub(task = task(tokensIn = 123))
        val json = exporter.toJson(exporter.export("t1", SessionDebugOptions.forLevel(DebugLevel.STANDARD)))
        assertTrue(json.contains("\"schemaVersion\": 1"), "should carry schema version")
        assertTrue(json.contains("\"tokensIn\": 123"), "should carry session token metrics")
    }

    @Test
    fun `minimal level omits subtasks conversation and apiLogs but keeps counts`() {
        stub()
        val snap = exporter.export("t1", SessionDebugOptions.forLevel(DebugLevel.MINIMAL))
        assertTrue(snap.subtasks.isEmpty())
        assertTrue(snap.conversation.isEmpty())
        assertTrue(snap.apiLogs.isEmpty())
        // Counts are still derived from the raw rows even when the detail lists are omitted.
        assertEquals(1, snap.metrics.apiCallCount)
        assertEquals(1, snap.metrics.toolCallCount)
    }

    @Test
    fun `standard level includes subtasks conversation and apiLogs`() {
        stub()
        val snap = exporter.export("t1", SessionDebugOptions.forLevel(DebugLevel.STANDARD))
        assertEquals(1, snap.subtasks.size)
        assertEquals(1, snap.conversation.size)
        assertEquals(1, snap.apiLogs.size)
    }

    @Test
    fun `missing task yields an error not a crash`() {
        stub(task = null, subtasks = emptyList(), apiLogs = emptyList(), messages = emptyList())
        val snap = exporter.export("ghost", SessionDebugOptions.forLevel(DebugLevel.STANDARD))
        assertEquals("ghost", snap.session.id)
        assertEquals("UNKNOWN", snap.session.status)
        assertTrue(snap.errors.any { it.contains("Task not found") })
    }

    @Test
    fun `tool call arguments are exported so an e2e assertion can match which subagent ran`() {
        val withCall = ChatMessage(
            id = "m-call", taskId = "t1", role = MessageRole.ASSISTANT, content = "delegating",
            metadata = null,
            toolCalls = listOf(
                pl.jclab.refio.core.db.ToolCallData(
                    id = "tc1", name = "invoke_subagent",
                    arguments = """{"subagent_name":"code-reviewer","goal":"find the bug"}""",
                )
            ),
            toolCallId = null, tokensIn = null, tokensOut = null, cost = null, createdAt = 2_000,
        )
        stub(messages = listOf(withCall))
        val snap = exporter.export("t1", SessionDebugOptions.forLevel(DebugLevel.STANDARD))
        val detail = snap.conversation.single().toolCallDetails.single()
        assertEquals("invoke_subagent", detail.name)
        // The raw arguments JSON must survive so a needle like subagent_name=code-reviewer can match.
        assertTrue(detail.arguments.contains("code-reviewer"), "arguments JSON must carry the subagent name")
        assertTrue(
            exporter.toJson(snap).contains("code-reviewer"),
            "run.json must expose tool-call arguments for the e2e tool_invoked assertion"
        )
    }

    @Test
    fun `finalOutput is the last assistant message`() {
        stub(messages = listOf(msg(MessageRole.USER, "make it"), msg(MessageRole.ASSISTANT, "created snake.html")))
        val snap = exporter.export("t1", SessionDebugOptions.forLevel(DebugLevel.MINIMAL))
        assertEquals("created snake.html", snap.finalOutput)
    }

    @Test
    fun `a failed agent always leaves a reason in errors, even when it carries none`() {
        // A multi-agent run that ends in failure must be diagnosable from run.json. The error list
        // was built only from agents that carried an error string, so an agent that came back
        // FAILED without one produced an empty list - a failure with no recorded cause anywhere.
        val response = MultiAgentSessionResponse(
            sessionId = "s1", name = "pipeline", status = "COMPLETED",
            agents = listOf(
                agent("analyst", tokensIn = 5000, tokensOut = 300, costUsd = 0.0, started = 1_000),
                MultiAgentInstanceResponse(
                    agentName = "coder", status = "FAILED", success = false, response = "",
                    tokensUsed = 42_000, tokensIn = 41_000, tokensOut = 1_000, costUsd = 0.0,
                    durationMs = 300, startedAt = 2_000, completedAt = 2_300, error = null,
                ),
            ),
            totalTokens = 47_300, totalTokensIn = 46_000, totalTokensOut = 1_300, totalCostUsd = 0.0,
            durationMs = 4_000, createdAt = 1_000, completedAt = 5_000,
        )

        val snap = exporter.exportMultiAgent(response, model = "m", options = SessionDebugOptions.forLevel(DebugLevel.STANDARD))

        val coderErrors = snap.errors.filter { it.contains("coder") }
        assertEquals(1, coderErrors.size, "the failed agent must appear exactly once in errors: ${snap.errors}")
        assertTrue(coderErrors.first().contains("FAILED"), "the entry must name the agent status: ${coderErrors.first()}")
        assertTrue(snap.errors.none { it.contains("analyst") }, "a successful agent must not be reported as an error")
    }

    private fun agent(name: String, tokensIn: Int, tokensOut: Int, costUsd: Double, started: Long) =
        MultiAgentInstanceResponse(
            agentName = name, status = "COMPLETED", success = true, response = "done",
            tokensUsed = (tokensIn + tokensOut).toLong(), tokensIn = tokensIn, tokensOut = tokensOut,
            costUsd = costUsd, durationMs = 100, startedAt = started, completedAt = started + 100,
        )

    @Test
    fun `multi-agent snapshot reports the real aggregate token split, not zero`() {
        // Regression: the multi-agent run.json used to hardcode metrics to 0, so the e2e stats layer
        // (which reads .metrics.tokensOut) undercounted every multi-agent scenario to nothing. The
        // rolled-up figure must be the sum of the per-agent OUTPUT tokens - not a combined in+out
        // number that would inflate tokensOut by the (much larger) prompt tokens.
        val response = MultiAgentSessionResponse(
            sessionId = "s1", name = "pipeline", status = "COMPLETED",
            agents = listOf(
                agent("analyst", tokensIn = 5000, tokensOut = 300, costUsd = 0.01, started = 2_000),
                agent("coder", tokensIn = 6000, tokensOut = 500, costUsd = 0.02, started = 1_000),
            ),
            totalTokens = 11_800, totalTokensIn = 11_000, totalTokensOut = 800, totalCostUsd = 0.03,
            durationMs = 4_000, createdAt = 1_000, completedAt = 5_000,
        )
        val snap = exporter.exportMultiAgent(response, model = "ollama/qwen3.5:122b", options = SessionDebugOptions.forLevel(DebugLevel.STANDARD))

        assertEquals(800, snap.metrics.tokensOut, "tokensOut must be the summed OUTPUT tokens")
        assertEquals(11_000, snap.metrics.tokensIn)
        assertEquals(0.03, snap.metrics.costUsd)
        assertEquals(2, snap.metrics.apiCallCount, "one API call slot per agent")
        assertEquals("MULTI_AGENT", snap.session.mode)
        assertEquals(800, snap.session.tokensOut)
        // Agents ordered by real start time: coder (started 1000) before analyst (started 2000).
        assertEquals(listOf("coder", "analyst"), snap.multiAgent?.agents?.map { it.agentName })
        assertEquals(500, snap.multiAgent?.agents?.first()?.tokensOut, "per-agent split is preserved")
    }

    @Test
    fun `multi agent run json carries each agent's conversation`() {
        // A person or a judge reading run.json must see what each agent did; a multi-agent run
        // exported an empty conversation and no steps at all.
        every { taskRepository.findById(any()) } returns task()
        every { apiLogRepository.findByTaskId(any()) } returns emptyList()
        every { subtaskRepository.findByTaskId("t-analyst") } returns listOf(subtask(0))
        every { subtaskRepository.findByTaskId("t-coder") } returns listOf(subtask(0), subtask(1))
        every { chatMessageRepository.findByTaskId("t-analyst") } returns
            listOf(msg(MessageRole.USER, "find the port"), msg(MessageRole.ASSISTANT, "port 8731"))
        every { chatMessageRepository.findByTaskId("t-coder") } returns listOf(msg(MessageRole.ASSISTANT, "client written"))
        val response = MultiAgentSessionResponse(
            sessionId = "s1", name = "pipeline", status = "COMPLETED",
            agents = listOf(
                agent("coder", tokensIn = 10, tokensOut = 5, costUsd = 0.0, started = 2_000).copy(taskId = "t-coder"),
                agent("analyst", tokensIn = 10, tokensOut = 5, costUsd = 0.0, started = 1_000).copy(taskId = "t-analyst"),
            ),
            createdAt = 1_000, completedAt = 3_000,
        )

        val snap = exporter.exportMultiAgent(response, model = "m", options = SessionDebugOptions.forLevel(DebugLevel.STANDARD))

        assertEquals(listOf("analyst", "analyst", "coder"), snap.conversation.map { it.agentName }, "in the order the agents ran")
        assertEquals("port 8731", snap.conversation[1].contentPreview)
        assertEquals(3, snap.subtasks.size)
        assertEquals("t-coder", snap.multiAgent?.agents?.last()?.taskId)
    }

    @Test
    fun `an incomplete multi agent run is not reported as failed`() {
        val response = MultiAgentSessionResponse(
            sessionId = "s1", name = "pipeline", status = "INCOMPLETE",
            agents = listOf(
                agent("analyst", tokensIn = 10, tokensOut = 5, costUsd = 0.0, started = 1_000),
                agent("coder", tokensIn = 10, tokensOut = 5, costUsd = 0.0, started = 2_000).copy(status = "INCOMPLETE", success = false),
            ),
            createdAt = 1_000, completedAt = 3_000,
        )

        val snap = exporter.exportMultiAgent(response, model = "m", options = SessionDebugOptions.forLevel(DebugLevel.MINIMAL))

        assertEquals("INCOMPLETE", snap.session.status)
    }

    @Test
    fun `iterations report the loop's own count, not the subtask rows`() {
        TurnIterationTracker.reset()
        try {
            // Three subtask rows, but the loop only went round twice.
            stub(subtasks = listOf(subtask(0), subtask(1), subtask(2)))
            TurnIterationTracker.record("t1", IterationSummary(used = 2, limit = 200))

            val snap = exporter.export("t1", SessionDebugOptions.forLevel(DebugLevel.STANDARD))

            assertEquals(2, snap.metrics.iterations, "iterations must be the loop's own count")
            assertEquals(200, snap.metrics.maxIterations)
            assertEquals(3, snap.metrics.toolCallCount, "toolCallCount stays the subtask row count")
        } finally {
            TurnIterationTracker.reset()
        }
    }

    @Test
    fun `a turn that named no exit still reports a countable stop reason`() {
        TurnStopReasonTracker.reset()
        try {
            stub()
            assertEquals(
                TurnStopReason.UNKNOWN.name,
                exporter.export("t1", SessionDebugOptions.forLevel(DebugLevel.STANDARD)).metrics.stopReason,
            )

            TurnStopReasonTracker.record("t1", TurnStopReason.MAX_ITERATIONS)
            assertEquals(
                "MAX_ITERATIONS",
                exporter.export("t1", SessionDebugOptions.forLevel(DebugLevel.STANDARD)).metrics.stopReason,
            )
        } finally {
            TurnStopReasonTracker.reset()
        }
    }

    @Test
    fun `a failed tool call is distinguishable from a successful one, with its error truncated`() {
        val longError = "boom ".repeat(500)
        stub(
            messages = listOf(
                msg(
                    MessageRole.ASSISTANT, "working",
                    toolCalls = listOf(
                        ToolCallData(id = "c1", name = "read_file", arguments = "{}"),
                        ToolCallData(id = "c2", name = "code_editing", arguments = "{}", error = longError),
                    ),
                )
            )
        )
        val options = SessionDebugOptions.forLevel(DebugLevel.STANDARD)
        val details = exporter.export("t1", options).conversation.first().toolCallDetails

        assertEquals(listOf(true, false), details.map { it.ok })
        assertEquals(null, details[0].error)
        assertTrue(details[1].error!!.isNotBlank(), "a failed call must carry its reason")
        assertTrue(
            details[1].error!!.length < longError.length,
            "the error text must be truncated like every other preview",
        )
    }

    @Test
    fun `a steering nudge is recognisable without matching its English text`() {
        stub(
            messages = listOf(
                msg(MessageRole.SYSTEM, "you have read a lot", metadata = """{"type":"guardian_nudge"}"""),
                msg(MessageRole.SYSTEM, "an ordinary system message"),
            )
        )
        val conversation = exporter.export("t1", SessionDebugOptions.forLevel(DebugLevel.STANDARD)).conversation

        assertTrue(conversation[0].metadata!!.contains("guardian_nudge"))
        assertEquals(null, conversation[1].metadata)
    }

    @Test
    fun `guardrail counters and context losses reach the run document`() {
        TurnGuardrailStatsTracker.reset()
        TurnContextTracker.reset()
        try {
            stub()
            TurnGuardrailStatsTracker.record(
                "t1",
                GuardrailStats(consolidationNudges = 1, guardianReentries = 2, maxRepeatedCall = 7, noopWrites = 3),
            )
            TurnContextTracker.recordIteration(
                "t1", budgetTokens = 64_000, usedTokens = 61_000, droppedSections = listOf("CONVERSATION"),
            )
            TurnContextTracker.recordIteration(
                "t1", budgetTokens = 64_000, usedTokens = 63_000,
                droppedSections = listOf("CONVERSATION", "USER_CONTEXT"),
            )
            TurnContextTracker.recordTrim("t1", droppedMessages = 4, droppedSteps = 2)

            val metrics = exporter.export("t1", SessionDebugOptions.forLevel(DebugLevel.STANDARD)).metrics

            assertEquals(1, metrics.guardrails.consolidationNudges)
            assertEquals(2, metrics.guardrails.guardianReentries)
            assertEquals(7, metrics.guardrails.maxRepeatedCall)
            assertEquals(3, metrics.guardrails.noopWrites)
            assertEquals(64_000, metrics.context.budgetTokens)
            assertEquals(4, metrics.context.droppedMessages)
            assertEquals(2, metrics.context.droppedSteps)
            assertEquals(
                mapOf("CONVERSATION" to 2, "USER_CONTEXT" to 1),
                metrics.context.drops,
                "a section that fell out on every iteration must not look like one that fell out once",
            )
        } finally {
            TurnGuardrailStatsTracker.reset()
            TurnContextTracker.reset()
        }
    }

    @Test
    fun `a verification that ran red on an already red project is exported as executed and unattributable`() {
        TurnVerificationTracker.reset()
        try {
            stub()
            TurnVerificationTracker.record(
                "t1",
                VerificationSummary(
                    ran = true, attempts = 1, result = VerificationSummary.RESULT_FAILED, exitCode = 1,
                    baseline = VerificationSummary.RESULT_FAILED, attributionUncertain = true,
                ),
            )

            val snap = exporter.export("t1", SessionDebugOptions.forLevel(DebugLevel.STANDARD))
            val v = snap.metrics.verification

            // The original three fields keep their meaning for existing readers.
            assertEquals(true, v.ran)
            assertEquals(1, v.attempts)
            assertEquals("FAILED", v.result)
            // The additive ones separate the outcome from whether it can be blamed on the turn.
            assertEquals(1, v.exitCode)
            assertEquals("FAILED", v.baseline)
            assertEquals(true, v.attributionUncertain)
            assertTrue(exporter.toJson(snap).contains("\"attributionUncertain\": true"))
        } finally {
            TurnVerificationTracker.reset()
        }
    }

    @Test
    fun `a verification that never started carries the reason in the run document`() {
        TurnVerificationTracker.reset()
        try {
            stub()
            TurnVerificationTracker.record(
                "t1", VerificationSummary.NOT_RUN.copy(notRunReason = "verification command 'npm test' could not be started"),
            )

            val v = exporter.export("t1", SessionDebugOptions.forLevel(DebugLevel.STANDARD)).metrics.verification

            assertEquals(false, v.ran)
            assertEquals(null, v.result)
            assertTrue(v.notRunReason!!.contains("could not be started"))
        } finally {
            TurnVerificationTracker.reset()
        }
    }

    @Test
    fun `a file edited and put back is reported as written twice and unchanged`() {
        TurnFileWriteTracker.reset()
        try {
            stub()
            TurnFileWriteTracker.recordWrite("t1", "src/app.js", hashBefore = "aaa", hashAfter = "bbb")
            TurnFileWriteTracker.recordWrite("t1", "src/app.js", hashBefore = "bbb", hashAfter = "aaa")
            TurnFileWriteTracker.recordWrite("t1", "src/new.js", hashBefore = null, hashAfter = "ccc")

            val written = exporter.export("t1", SessionDebugOptions.forLevel(DebugLevel.STANDARD)).metrics.filesWritten

            assertEquals(listOf("src/app.js", "src/new.js"), written.map { it.path })
            assertEquals(2, written[0].writes)
            assertEquals(false, written[0].netChanged, "edited then restored is a net no-change")
            assertEquals(true, written[1].netChanged, "a created file is a change")
        } finally {
            TurnFileWriteTracker.reset()
        }
    }

    @Test
    fun `losing the native tool channel mid-turn is visible in the run document`() {
        TurnNativeToolsTracker.reset()
        try {
            stub()
            assertEquals(
                null,
                exporter.export("t1", SessionDebugOptions.forLevel(DebugLevel.STANDARD)).metrics.nativeToolsDegraded,
            )

            TurnNativeToolsTracker.recordDegradation("t1", TurnNativeToolsTracker.REASON_TEMPLATE_PARSE_ERROR)
            // First reason wins: what knocked the turn off the channel, not what followed it.
            TurnNativeToolsTracker.recordDegradation("t1", TurnNativeToolsTracker.REASON_GUARDIAN_REENTRY)

            assertEquals(
                "TEMPLATE_PARSE_ERROR",
                exporter.export("t1", SessionDebugOptions.forLevel(DebugLevel.STANDARD)).metrics.nativeToolsDegraded,
            )
        } finally {
            TurnNativeToolsTracker.reset()
        }
    }
    // A CI step or the e2e harness reading run.json must see how a loop of rounds ended and what
    // each round did, not only the last turn.
    @Test
    fun `run json lists rounds`() {
        stub()
        val spec = LongTaskSpec(untilCommand = "npm test", goal = null, maxRounds = 2, maxMinutes = 240, maxNoProgressRounds = 3, checkTimeoutSeconds = 600)
        val rounds = listOf(
            RoundRecord(1, 540_000, 18, "COMPLETED", checkPassed = false, exitCode = 1, errorLines = 5, filesWritten = 4, planStepsDone = 2, progress = true),
            RoundRecord(2, 300_000, 25, "MAX_ITERATIONS", checkPassed = false, exitCode = 1, errorLines = 5, filesWritten = 0, planStepsDone = 2, progress = false),
        )
        val state = LongTaskState(
            taskId = "t1", spec = spec, status = LongTaskStatus.INCOMPLETE, roundsDone = 2, totalIterations = 43,
            activeMs = 840_000, rounds = rounds, stopReason = "round limit of 2 reached", lastCheckTail = "FAILED: 5 tests",
        )
        val taskState = mockk<TaskStateRepository>()
        every { taskState.get("t1") } returns LongTaskStateCodec.applyTo(TaskStateRow(taskId = "t1", untilCommand = "npm test"), state)
        val exporter = SessionDebugExporter(taskRepository, subtaskRepository, apiLogRepository, chatMessageRepository, taskState)

        val longTask = exporter.export("t1", SessionDebugOptions.forLevel(DebugLevel.STANDARD)).longTask!!

        assertEquals("INCOMPLETE", longTask.status)
        assertEquals("round limit of 2 reached", longTask.stopReason)
        assertEquals("npm test", longTask.untilCommand)
        assertEquals(listOf(1, 2), longTask.rounds.map { it.round })
        assertEquals(listOf(true, false), longTask.rounds.map { it.progress })
        assertEquals("MAX_ITERATIONS", longTask.rounds[1].turnStopReason)
        assertEquals(1, longTask.rounds[1].exitCode)
        assertEquals("FAILED: 5 tests", longTask.lastCheck)
    }

    @Test
    fun `run json lists every subagent with iterations, stop reason and files`() {
        // The task's own counters cover the parent only; without this list a run that spent most
        // of its time in subagents shows nothing of what they did or why they stopped.
        val coder = msg(
            MessageRole.TOOL, "[subagent coder · status: done]",
            metadata = """{"subagent_name":"coder","depth":1,"iterations":4,"delegated_tokens_in":900,"delegated_tokens_out":200,"status":"done","stop_reason":"completed","files_changed":["src/a.js"],"duration_ms":42000}"""
        )
        val coderAgain = msg(
            MessageRole.TOOL, "[subagent coder · status: partial]",
            metadata = """{"subagent_name":"coder","depth":1,"iterations":10,"delegated_tokens_in":1200,"delegated_tokens_out":340,"status":"partial","stop_reason":"max_iterations","files_changed":["src/b.js","src/c.js"],"duration_ms":125000}"""
        )
        val reviewer = msg(
            MessageRole.TOOL, "[subagent code-reviewer · status: failed]",
            metadata = """{"subagent_name":"code-reviewer","depth":1,"iterations":2,"delegated_tokens_in":300,"delegated_tokens_out":80,"status":"failed","stop_reason":"exception","files_changed":[],"duration_ms":5000}"""
        )
        val readFile = msg(MessageRole.TOOL, "fun a() = 1", metadata = """{"path":"src/a.js","line_count":1}""")
        stub(messages = listOf(msg(MessageRole.ASSISTANT, "delegating"), coder, readFile, coderAgain, reviewer, msg(MessageRole.ASSISTANT, "done")))

        val snap = exporter.export("t1", SessionDebugOptions.forLevel(DebugLevel.MINIMAL))

        assertEquals(listOf("coder", "coder", "code-reviewer"), snap.agents.map { it.name })
        val partial = snap.agents[1]
        assertEquals("partial", partial.status)
        assertEquals("max_iterations", partial.stopReason)
        assertEquals(10, partial.iterations)
        assertEquals(1200, partial.tokensIn)
        assertEquals(340, partial.tokensOut)
        assertEquals(125_000L, partial.durationMs)
        assertEquals(listOf("src/b.js", "src/c.js"), partial.filesChanged)
        assertEquals("failed", snap.agents[2].status)
        assertTrue(exporter.toJson(snap).contains("\"stopReason\": \"max_iterations\""), "the run document carries it")
    }

    @Test
    fun `single turn has no long task section`() {
        stub()
        val taskState = mockk<TaskStateRepository>()
        every { taskState.get("t1") } returns TaskStateRow(taskId = "t1", planJson = "[]")
        val exporter = SessionDebugExporter(taskRepository, subtaskRepository, apiLogRepository, chatMessageRepository, taskState)

        assertNull(exporter.export("t1", SessionDebugOptions.forLevel(DebugLevel.STANDARD)).longTask)
    }
}
