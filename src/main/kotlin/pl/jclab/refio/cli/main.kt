package pl.jclab.refio.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.path
import kotlinx.coroutines.runBlocking
import pl.jclab.refio.api.models.ExecutionMode
import pl.jclab.refio.api.models.TaskMode
import pl.jclab.refio.cli.ui.launchComposeApp
import pl.jclab.refio.core.api.CreateTaskRequest
import pl.jclab.refio.core.api.MultiAgentSessionRequest
import pl.jclab.refio.core.workflow.WorkflowEventListener
import pl.jclab.refio.core.workflow.models.IntentResult
import pl.jclab.refio.core.workflow.models.UIState
import pl.jclab.refio.core.workflow.models.WorkflowRequest
import java.nio.file.Path
import kotlin.io.path.readText

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
        .default(TaskMode.CHAT)
    val model by option("--model", help = "LLM model override")
    val headless by option("--headless", help = "Run without GUI").flag()
    val prompt by option("--prompt", help = "Prompt for headless mode")
    val multiAgent by option("--multi-agent", help = "Multi-agent YAML definition file")
        .path(mustExist = true, canBeDir = false)
    val noEgress by option("--no-egress", help = "Block cloud LLM providers").flag()

    override fun run() {
        if (headless && multiAgent != null) {
            runMultiAgent()
        } else if (headless && prompt != null) {
            runHeadless()
        } else if (headless) {
            echo("Error: --headless requires --prompt or --multi-agent <file>", err = true)
        } else {
            launchComposeApp(project, mode, model, noEgress)
        }
    }

    private fun runMultiAgent() {
        runBlocking {
            val bootstrap = StandaloneCoreBootstrap(project)
            try {
                val router = bootstrap.initialize()
                val yamlContent = multiAgent!!.readText()
                val definition = pl.jclab.refio.core.agents.MultiAgentTaskParser.parse(yamlContent)

                echo("Refio Multi-Agent — project: ${project.toAbsolutePath()}", err = true)
                echo("Session: ${definition.name} (${definition.agents.size} agents)", err = true)

                val result = router.launchMultiAgentSession(
                    MultiAgentSessionRequest(
                        name = definition.name,
                        yamlDefinition = yamlContent,
                        model = model
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
            } catch (e: Exception) {
                echo("Error: ${e.message}", err = true)
            } finally {
                bootstrap.shutdown()
            }
        }
    }

    private fun runHeadless() {
        runBlocking {
            val bootstrap = StandaloneCoreBootstrap(project)
            try {
                val router = bootstrap.initialize()
                echo("Refio CLI — project: ${project.toAbsolutePath()}, mode: $mode", err = true)

                // Create task
                val coreMode = when (mode) {
                    TaskMode.CHAT -> pl.jclab.refio.core.db.TaskMode.CHAT
                    TaskMode.PLAN -> pl.jclab.refio.core.db.TaskMode.PLAN
                    TaskMode.AGENT -> pl.jclab.refio.core.db.TaskMode.AGENT
                }
                val task = router.createTask(CreateTaskRequest(
                    name = "headless-${System.currentTimeMillis()}",
                    mode = coreMode
                ))

                // Build workflow request
                val uiState = UIState(
                    taskId = task.id,
                    mode = mode,
                    executionMode = ExecutionMode.AUTO,
                    input = prompt!!,
                    model = model,
                    streamingEnabled = false,
                    noEgressEnabled = noEgress
                )

                // Streaming listener that prints chunks to stdout
                val listener = object : WorkflowEventListener {
                    override fun onStreamChunk(chunk: String) {
                        print(chunk)
                        System.out.flush()
                    }
                    override fun onStreamComplete(content: String) {
                        // Final content already printed via chunks
                    }
                    override fun onWorkflowError(error: Exception) {
                        System.err.println("\nError: ${error.message}")
                    }
                }

                val result = router.workflowOrchestrator.execute(
                    WorkflowRequest(uiState = uiState),
                    listener
                )

                // Print result (in case streaming didn't cover it)
                val output = when (result) {
                    is IntentResult.ChatResult -> result.response.output
                    is IntentResult.PlanResult -> result.response.plan
                    is IntentResult.StepResult -> result.response.summary
                    is IntentResult.SubagentResult -> result.response.response
                    is IntentResult.AnswerResult -> ""
                }
                if (output.isNotBlank()) {
                    println(output)
                }
            } catch (e: Exception) {
                echo("Error: ${e.message}", err = true)
            } finally {
                bootstrap.shutdown()
            }
        }
    }
}

fun main(args: Array<String>) = RefioCommand().main(args)
