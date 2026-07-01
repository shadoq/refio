package pl.jclab.refio.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.coroutines.runBlocking
import pl.jclab.refio.api.models.TaskMode
import pl.jclab.refio.cli.tui.launchTuiApp
import pl.jclab.refio.core.api.CreateTaskRequest
import pl.jclab.refio.core.api.MultiAgentSessionRequest
import pl.jclab.refio.core.api.TurnRequest
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.config.ConfigPrintView
import pl.jclab.refio.core.config.RunConfigOverrides
import pl.jclab.refio.core.debug.SESSION_DEBUG_SCHEMA_VERSION
import pl.jclab.refio.core.debug.SessionDebugOptions
import pl.jclab.refio.core.debug.SessionDebugSnapshot
import pl.jclab.refio.core.debug.StabilizationGate
import pl.jclab.refio.core.models.api.ChatRequest
import pl.jclab.refio.core.models.api.LLMParams
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Refio CLI entry point.
 *
 * Usage:
 *   refio --project /path/to/project
 *   refio -p . --mode plan
 *   refio -p . --headless --prompt "Explain the architecture"
 *   refio -p . --headless --multi-agent agents.yaml
 */
class RefioCommand : CliktCommand(name = "refio") {
    val project by option("--project", "-p", help = "Path to project directory")
        .path(mustExist = true, canBeFile = false)
        .default(Path.of("."))
    val mode by option("--mode", "-m", help = "Task mode: CHAT, PLAN, AGENT")
        .enum<TaskMode>()
    val model by option("--model", help = "LLM model override")
    val headless by option("--headless", help = "Run without GUI").flag()
    val prompt by option("--prompt", help = "Prompt for headless mode")
    val multiAgent by option("--multi-agent", help = "Multi-agent YAML definition file")
        .path(mustExist = true, canBeDir = false)
    val noEgress by option("--no-egress", help = "Block cloud LLM providers").flag()
    val config by option(
        "--config",
        help = "Run-scope config override key=value (repeatable). Works in both headless and interactive TUI. E.g. --config agent.max_iterations=80 or --config providers.ollama.ollama_endpoint=http://127.0.0.1:11434"
    ).multiple()
    val configFile by option(
        "--config-file",
        help = "File of key=value config overrides, one per line (# comments allowed). Works in both headless and interactive TUI."
    ).path(mustExist = true, canBeDir = false)
    val printConfig by option(
        "--print-config",
        help = "Print the resolved config (with overrides applied) and exit. No LLM call."
    ).flag()
    val promptFile by option(
        "--prompt-file",
        help = "Read the headless prompt from a file (avoids shell quoting issues)"
    ).path(mustExist = true, canBeDir = false)
    val outputFormat by option(
        "--output",
        help = "Headless output format: text (default) or json"
    ).choice("text", "json").default("text")
    val outputFile by option(
        "--output-file",
        help = "Write structured output (with --output json) to this file instead of stdout"
    ).path(canBeDir = false)
    val debugLevel by option(
        "--debug-level",
        help = "JSON detail level: minimal|standard|full|judge (default standard)"
    ).choice("minimal", "standard", "full", "judge").default("standard")
    val maxCost by option(
        "--max-cost",
        help = "Hard per-session cost ceiling in USD (headless). Aborts the turn once reached. 0 disables."
    ).double()
    val autoApprove by option(
        "--auto-approve",
        help = "Headless only: auto-approve tool calls whose command matches this regex; others are rejected. E.g. --auto-approve \"^git \""
    )
    val verbose by option(
        "--verbose", "-v",
        help = "Headless: stream live LLM tokens to stderr (on top of the always-on turn/tool progress). Helps tell whether a model is producing output or hanging."
    ).flag()
    val gateDir by option(
        "--gate",
        help = "Aggregate an e2e gate out-dir (its results.jsonl) into a pass-rate verdict and exit 0 (green) / 1 (red). No LLM call, writes nothing."
    ).path(mustExist = true, canBeFile = false)
    val gateBaseline by option(
        "--gate-baseline",
        help = "Baseline pass-rate [0..1] the gate must not regress below (used with --gate)."
    ).double()
    val gateMinPassRate by option(
        "--gate-min-pass-rate",
        help = "Absolute pass-rate floor [0..1] every scenario must clear. Default 1.0 for the single-gate path; with --gate-baseline-file the floor is off (0.0) by default so the per-scenario baseline governs."
    ).double()
    val gateTolerance by option(
        "--gate-tolerance",
        help = "Allowed pass-rate drop vs --gate-baseline before the gate turns red (default 0.0)."
    ).double().default(0.0)
    val gateBaselineFile by option(
        "--gate-baseline-file",
        help = "Per-scenario baseline JSON ({\"scenario\": passRate}). When set, each scenario is checked against its own baseline instead of the single --gate-baseline."
    ).path(mustExist = true, canBeDir = false)
    val gateWriteBaseline by option(
        "--gate-write-baseline",
        help = "Write the current per-scenario pass-rates to this file as a baseline snapshot and exit (used with --gate). No red/green check."
    ).path()
    val gateHistory by option(
        "--gate-history",
        help = "Append this gate run's summary (per-scenario pass-rates) as one line to an e2e-history.jsonl, attributed to --gate-commit. Used with --gate."
    ).path()
    val gateCommit by option(
        "--gate-commit",
        help = "Commit SHA to attribute a --gate-history entry to (default: env GATE_COMMIT, else 'unknown')."
    )
    val gateTrend by option(
        "--gate-trend",
        help = "Read an e2e-history.jsonl and print a per-scenario pass-rate trend to stdout, then exit. No results dir or LLM needed."
    ).path(mustExist = true, canBeDir = false)

    override fun run() {
        // Parse run-scope config overrides up front so a bad key/value fails loud (non-zero exit)
        // before any heavy initialization. docs/0063.
        val overrides = try {
            if (config.isEmpty() && configFile == null) emptyMap()
            else RunConfigOverrides.parse(config, configFile?.readText())
        } catch (e: IllegalArgumentException) {
            throw UsageError(e.message ?: "Invalid config override")
        }

        // --max-cost is sugar over a run-scope override of agent.max_cost_usd (docs/0063 §6.1).
        maxCost?.let { if (it < 0) throw UsageError("--max-cost must be >= 0") }
        val effectiveOverrides = maxCost
            ?.let { overrides + (ConfigKeys.AGENT_MAX_COST_USD.key to it.toString()) }
            ?: overrides

        if (printConfig) {
            runPrintConfig(effectiveOverrides)
            return
        }

        gateTrend?.let { history ->
            println(StabilizationGate.renderTrend(StabilizationGate.parseHistory(history.readText())))
            return
        }

        gateDir?.let { dir ->
            runGate(dir, gateBaseline, gateMinPassRate, gateTolerance, gateBaselineFile, gateWriteBaseline, gateHistory, gateCommit)
            return
        }

        val autoApproveRegex = autoApprove?.let {
            try { Regex(it) } catch (e: Exception) { throw UsageError("--auto-approve is not a valid regex: ${e.message}") }
        }

        val effectivePrompt = prompt ?: promptFile?.readText()

        // --no-egress is a hard switch. The interactive TUI applies it via its own runtime
        // toggle (and must stay user-toggleable), so we only fold it into the overrides for the
        // headless / multi-agent paths, where it must reach the egress gate via config.
        if (headless && multiAgent != null) {
            runMultiAgent(withSelectedModel(withNoEgress(effectiveOverrides, noEgress), model))
        } else if (headless && effectivePrompt != null) {
            runHeadless(withSelectedModel(withNoEgress(effectiveOverrides, noEgress), model), effectivePrompt, autoApproveRegex)
        } else if (headless) {
            echo("Error: --headless requires --prompt, --prompt-file, or --multi-agent <file>", err = true)
            throw ProgramResult(HeadlessExit.FAILURE)
        } else {
            // Run-scope overrides apply to the interactive TUI too, so a user can point Ollama /
            // LM Studio at a different endpoint (or tweak any config key) for one session without
            // editing config.yaml or building a synthetic config. Same validated map as headless.
            launchTuiApp(project, mode, model, noEgress, effectiveOverrides)
        }
    }

    private fun runMultiAgent(runConfigOverrides: Map<String, String>) {
        var exitCode = HeadlessExit.SUCCESS
        runBlocking {
            val bootstrap = StandaloneCoreBootstrap(project, runConfigOverrides)
            try {
                val router = bootstrap.initialize()
                val yamlContent = multiAgent!!.readText()
                val definition = pl.jclab.refio.core.agents.MultiAgentTaskParser.parse(yamlContent)

                echo("Refio Multi-Agent — project: ${project.toAbsolutePath()}", err = true)
                echo("Session: ${definition.name} (${definition.agents.size} agents)", err = true)

                // Split the combined "provider/model" (e.g. "ollama/qwen3.5:9b") like the single-turn
                // headless path does; passing it whole left the provider unset, so the turn resolved
                // it as "openrouter/ollama/qwen3.5:9b" — an invalid model id that failed every agent.
                val (maProvider, maModel) = splitProviderModel(model)
                val result = router.multiAgentRouter.launchMultiAgentSession(
                    MultiAgentSessionRequest(
                        name = definition.name,
                        yamlDefinition = yamlContent,
                        model = maModel,
                        provider = maProvider
                    )
                )

                // Print results per agent
                for (agent in result.agents) {
                    val status = if (agent.success == true) "OK" else "FAIL"
                    echo("[$status] ${agent.agentName}: tokens=${agent.tokensUsed}, cost=$${String.format("%.4f", agent.costUsd)}", err = true)
                    if (agent.response != null) {
                        println("--- ${agent.agentName} ---")
                        println(agent.response)
                        println()
                    }
                    if (agent.error != null) {
                        System.err.println("  Error: ${agent.error}")
                    }
                }

                // Summary
                echo("Total: tokens=${result.totalTokens}, cost=$${String.format("%.4f", result.totalCostUsd)}, duration=${result.durationMs}ms", err = true)

                // Emit a run.json so the e2e harness can assert on a multi-agent run the same way it
                // does a single turn (status gate, file needles, build) plus agent execution order.
                // Unlike runHeadless there is no single task to export; the snapshot is synthesized
                // from the per-agent session result, with agents sorted into real execution order.
                if (outputFormat == "json") {
                    val orderedAgents = result.agents.sortedBy { it.startedAt ?: Long.MAX_VALUE }
                    val allOk = result.agents.all { it.success == true }
                    val snapshot = SessionDebugSnapshot(
                        schemaVersion = SESSION_DEBUG_SCHEMA_VERSION,
                        run = SessionDebugSnapshot.RunInfo(
                            debugLevel = debugLevel.uppercase(),
                            durationMs = result.durationMs,
                            startedAt = result.createdAt,
                            endedAt = result.completedAt,
                        ),
                        session = SessionDebugSnapshot.SessionInfo(
                            id = result.sessionId, name = result.name,
                            mode = "MULTI_AGENT", executionMode = "AUTO",
                            model = model, provider = null,
                            status = if (allOk) "SUCCESS" else "FAILED",
                            tokensIn = 0, tokensOut = 0, costUsd = result.totalCostUsd,
                        ),
                        metrics = SessionDebugSnapshot.Metrics(
                            durationMs = result.durationMs, tokensIn = 0, tokensOut = 0,
                            costUsd = result.totalCostUsd, apiCallCount = 0, toolCallCount = 0,
                            contextOverflow = false,
                        ),
                        finalOutput = orderedAgents.joinToString("\n\n") {
                            "--- ${it.agentName} ---\n${it.response ?: ""}"
                        }.take(8000),
                        subtasks = emptyList(), conversation = emptyList(), apiLogs = emptyList(),
                        errors = orderedAgents.mapNotNull { a -> a.error?.let { "agent ${a.agentName}: $it" } },
                        warnings = emptyList(),
                        multiAgent = SessionDebugSnapshot.MultiAgentInfo(
                            agents = orderedAgents.map {
                                SessionDebugSnapshot.AgentRunInfo(
                                    agentName = it.agentName, status = it.status,
                                    success = it.success == true,
                                    startedAt = it.startedAt, completedAt = it.completedAt,
                                )
                            }
                        ),
                    )
                    val json = router.sessionDebugExporter.toJson(snapshot)
                    val target = outputFile
                    if (target != null) {
                        target.toFile().writeText(json)
                        echo("Wrote run.json -> ${target.toAbsolutePath()} (${json.length} bytes)", err = true)
                    } else {
                        println(json)
                    }
                }

                // Any agent that did not succeed makes the run a failure for exit-code purposes.
                if (result.agents.any { it.success != true }) exitCode = HeadlessExit.FAILURE
            } catch (e: Exception) {
                echo("Error: ${e.message}", err = true)
                exitCode = HeadlessExit.FAILURE
            } finally {
                bootstrap.shutdown()
            }
        }
        if (exitCode != HeadlessExit.SUCCESS) throw ProgramResult(exitCode)
    }

    private fun runPrintConfig(runConfigOverrides: Map<String, String>) {
        runBlocking {
            val bootstrap = StandaloneCoreBootstrap(project, runConfigOverrides)
            try {
                val router = bootstrap.initialize()
                val entries = ConfigKeys.allKeys()
                    .sortedBy { it.key }
                    .map { ck ->
                        ConfigPrintView.Entry(
                            key = ck.key,
                            value = router.configService.getTyped(ck)?.toString() ?: "",
                            isOverride = ck.key in runConfigOverrides
                        )
                    }
                println(ConfigPrintView.render(entries))
            } catch (e: Exception) {
                echo("Error: ${e.message}", err = true)
            } finally {
                bootstrap.shutdown()
            }
        }
    }

    /**
     * Aggregate an e2e gate out-dir into a pass-rate verdict and exit. Reads the runner's
     * results.jsonl (written when E2E_OUT_DIR is set), prints a human summary to stderr and the
     * machine-readable report to stdout, and exits red (FAILURE) when the pass-rate misses the floor
     * or regresses below the baseline. No LLM call.
     */
    private fun runGate(
        dir: Path,
        baseline: Double?,
        minPassRate: Double?,
        tolerance: Double,
        baselineFile: Path?,
        writeBaseline: Path?,
        history: Path?,
        commit: String?,
    ) {
        val resultsFile = dir.resolve("results.jsonl")
        if (!resultsFile.exists()) {
            echo("Error: $resultsFile not found (run e2e-run.sh with E2E_OUT_DIR=$dir first)", err = true)
            throw ProgramResult(HeadlessExit.FAILURE)
        }
        val report = StabilizationGate.aggregate(StabilizationGate.parseResults(resultsFile.readText()))

        // Snapshot the current per-scenario pass-rates as a baseline and exit (no verdict).
        if (writeBaseline != null) {
            writeBaseline.writeText(StabilizationGate.baselineJson(StabilizationGate.baselineFrom(report)))
            echo("gate: wrote per-scenario baseline (${report.byScenario.size} scenarios) to $writeBaseline", err = true)
            return
        }

        // Record this run in the commit-attributed history (every verdict run, green or red).
        if (history != null) {
            val attributedCommit = commit ?: System.getenv("GATE_COMMIT") ?: "unknown"
            val entry = StabilizationGate.historyEntry(report, attributedCommit, System.currentTimeMillis())
            history.toFile().appendText(StabilizationGate.historyEntryJson(entry) + "\n")
            echo("gate: appended history entry for $attributedCommit to $history", err = true)
        }

        echo("gate: ${report.passed}/${report.total} passed (${"%.1f".format(report.passRate * 100)}%)", err = true)
        if (report.byFailureMode.isNotEmpty()) {
            echo("  failures by mode: ${report.byFailureMode}", err = true)
        }

        // Per-scenario baseline: hold each scenario to its own entry, red if any scenario regresses.
        if (baselineFile != null) {
            val baselines = StabilizationGate.parseBaseline(baselineFile.readText())
            // Per-scenario mode: the baseline governs, so the absolute floor is off (0.0) unless set.
            val decision = StabilizationGate.decidePerScenario(report, baselines, minPassRate ?: 0.0, tolerance)
            decision.scenarios.forEach { s ->
                val deltaStr = s.delta?.let { " (${"%+.1f".format(it * 100)} pts vs baseline)" } ?: ""
                echo(
                    "  ${if (s.green) "GREEN" else "RED"} ${s.scenario}: ${"%.1f".format(s.passRate * 100)}%$deltaStr - ${s.reason}",
                    err = true
                )
            }
            echo("  ${if (decision.green) "GREEN" else "RED"} overall", err = true)
            println(StabilizationGate.reportJson(report, decision))
            if (!decision.green) {
                throw ProgramResult(HeadlessExit.FAILURE)
            }
            return
        }

        // Single global baseline (original behaviour): the floor defaults to 1.0.
        val decision = StabilizationGate.decide(report, baseline, minPassRate ?: 1.0, tolerance)
        if (report.byScenario.size > 1) {
            report.byScenario.forEach { (s, rate) -> echo("  scenario $s: ${"%.1f".format(rate * 100)}%", err = true) }
        }
        decision.delta?.let { echo("  delta vs baseline: ${"%+.1f".format(it * 100)} pts", err = true) }
        echo("  ${if (decision.green) "GREEN" else "RED"} - ${decision.reason}", err = true)
        println(StabilizationGate.reportJson(report, decision))
        if (!decision.green) {
            throw ProgramResult(HeadlessExit.FAILURE)
        }
    }

    /**
     * Split a combined "provider/model" string (e.g. "ollama/qwen3.5:122b") into
     * (provider, model). Mirrors the interactive TUI so headless resolves the model the
     * same way. Null/blank → (null, null) so runTurn/chat fall back to config.
     */
    private fun splitProviderModel(combined: String?): Pair<String?, String?> {
        if (combined.isNullOrBlank()) return Pair(null, null)
        val slashIdx = combined.indexOf('/')
        return if (slashIdx > 0) {
            Pair(combined.substring(0, slashIdx), combined.substring(slashIdx + 1))
        } else {
            Pair(null, combined)
        }
    }

    private fun runHeadless(runConfigOverrides: Map<String, String>, promptText: String, autoApproveRegex: Regex?) {
        var exitCode = HeadlessExit.SUCCESS
        runBlocking {
            val cliScope = this
            val bootstrap = StandaloneCoreBootstrap(project, runConfigOverrides)
            var autoApproveListener: AutoApproveListener? = null
            try {
                val router = bootstrap.initialize()
                if (autoApproveRegex != null) {
                    // Headless auto-approval so terminal-ASK tools don't hang on the timeout (docs/0063 §6.2).
                    autoApproveListener = AutoApproveListener(router.toolApprovalService, autoApproveRegex, cliScope)
                }
                val headlessMode = mode ?: TaskMode.AGENT
                echo("Refio CLI — project: ${project.toAbsolutePath()}, mode: $headlessMode", err = true)

                // Create task
                val coreMode = when (headlessMode) {
                    TaskMode.CHAT -> pl.jclab.refio.core.db.TaskMode.CHAT
                    TaskMode.PLAN -> pl.jclab.refio.core.db.TaskMode.PLAN
                    TaskMode.AGENT -> pl.jclab.refio.core.db.TaskMode.AGENT
                }
                val task = router.taskRouter.createTask(CreateTaskRequest(
                    name = "headless-${System.currentTimeMillis()}",
                    mode = coreMode
                ))

                // Route exactly like the interactive TUI / IntelliJ plugin: PLAN/AGENT go through
                // the AgentTurnLoop (agentRouter.runTurn) — the full agentic loop that actually
                // runs tools and writes files. CHAT goes through ChatService. The previous headless
                // path used WorkflowOrchestrator, whose AGENT branch only produced a plan and stopped
                // (one createPlan call, no execution), so headless runs never did the work.
                val (selectedProvider, selectedModel) = splitProviderModel(model)

                // Progress visibility: turn/tool events are mirrored to refio-cli.log AND printed
                // to stderr so a headless run isn't a black box.
                //
                // Always pass a stream callback so the turn LLM calls (and CHAT) stream from the
                // provider — matching the IntelliJ plugin, which always streams for its live UI.
                // Streaming keeps the socket alive token-by-token (Ktor socketTimeoutMillis resets on
                // every chunk), so it never trips the STREAM_IDLE_CEILING (300s socket-idle) timeout.
                // A single NON-streaming request, by contrast, sends no bytes until the whole response
                // is ready, so a slow/cold model that takes >300s to produce the full body looks like a
                // dead connection and is killed — which is exactly why qwen3.5 timed out headless but
                // worked in the plugin (same core, same num_ctx). --verbose only controls whether the
                // streamed deltas are ECHOED to stderr; streaming happens either way.
                // stdout stays reserved for the run.json document / final text result.
                val turnListener = HeadlessTurnListener()
                val tokenStream: (pl.jclab.refio.core.api.StreamChunk) -> Unit = { chunk ->
                    if (verbose) { System.err.print(chunk.delta); System.err.flush() }
                }

                val output: String = when (coreMode) {
                    pl.jclab.refio.core.db.TaskMode.CHAT -> {
                        val response = router.chatRouter.chat(
                            ChatRequest(
                                taskId = task.id,
                                mode = coreMode,
                                input = promptText,
                                params = LLMParams(model = selectedModel, provider = selectedProvider)
                            ),
                            stream = true,
                            onChunk = tokenStream
                        )
                        response.output
                    }
                    pl.jclab.refio.core.db.TaskMode.PLAN,
                    pl.jclab.refio.core.db.TaskMode.AGENT -> {
                        val result = router.agentRouter.runTurn(
                            TurnRequest(
                                taskId = task.id,
                                userInput = promptText,
                                mode = coreMode,
                                executionMode = pl.jclab.refio.core.db.ExecutionMode.AUTO,
                                model = selectedModel,
                                provider = selectedProvider
                            ),
                            streamCallback = tokenStream,
                            listener = turnListener
                        )
                        // Headless calls runTurn directly, bypassing CoreSessionService, which is what
                        // normally promotes the task off NEW. Mirror its mapping here so the exported
                        // run.json reports the real outcome (SUCCESS / INCOMPLETE / FAILED) instead of a
                        // permanent NEW that hides whether the agent actually delivered.
                        val finalStatus = when {
                            result.incomplete -> pl.jclab.refio.core.db.TaskStatus.INCOMPLETE
                            result.success -> pl.jclab.refio.core.db.TaskStatus.SUCCESS
                            else -> pl.jclab.refio.core.db.TaskStatus.FAILED
                        }
                        runCatching { router.taskRepository.update(id = task.id, status = finalStatus) }
                        // Exit code mirrors the turn outcome so a CI step gating on $? sees a
                        // FAILED / INCOMPLETE run as a failure instead of a silent success.
                        exitCode = HeadlessExit.forStatus(finalStatus)
                        result.response
                    }
                }

                val jsonMode = outputFormat == "json"
                if (jsonMode) {
                    val level = SessionDebugOptions.levelFromString(debugLevel)
                    val snapshot = router.sessionDebugExporter.export(task.id, SessionDebugOptions.forLevel(level))
                    val json = router.sessionDebugExporter.toJson(snapshot)
                    val target = outputFile
                    if (target != null) {
                        target.toFile().writeText(json)
                        echo("Wrote run.json -> ${target.toAbsolutePath()} (${json.length} bytes)", err = true)
                    } else {
                        println(json)
                    }
                } else if (output.isNotBlank()) {
                    println(output)
                }
            } catch (e: Exception) {
                echo("Error: ${e.message}", err = true)
                exitCode = HeadlessExit.FAILURE
            } finally {
                autoApproveListener?.job?.cancel()
                bootstrap.shutdown()
            }
        }
        if (exitCode != HeadlessExit.SUCCESS) throw ProgramResult(exitCode)
    }
}

fun main(args: Array<String>) = RefioCommand().main(args)
