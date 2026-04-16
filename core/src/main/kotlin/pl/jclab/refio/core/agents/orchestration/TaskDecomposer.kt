package pl.jclab.refio.core.agents.orchestration

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import pl.jclab.refio.core.agents.AgentSpec
import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.LLMMessage
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.subagents.SubagentRouter
import pl.jclab.refio.core.subagents.models.SubagentInfo

private val logger = dualLogger("TaskDecomposer")

/**
 * Turns a user prompt into a list of subagent invocations for PARALLEL or PIPELINE strategies.
 *
 * Calls an LLM with the list of available subagents and asks it to produce a JSON plan.
 * For PIPELINE, `dependsOn` chains are linearized; for PARALLEL they remain independent.
 *
 * Returns an empty list when the task clearly does not need multi-agent orchestration
 * (e.g. trivial request) — the dispatcher then falls back to the single-agent path.
 */
class TaskDecomposer(
    private val llmClient: LLMClient,
    private val configService: ConfigService,
    private val subagentRouter: SubagentRouter?,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    enum class Mode { PARALLEL, PIPELINE }

    suspend fun decompose(userInput: String, mode: Mode): List<AgentSpec> {
        val router = subagentRouter ?: run {
            logger.warn { "SubagentRouter unavailable, skipping decomposition" }
            return emptyList()
        }

        val available = router.listSubagents(includeDisabled = false)
        if (available.isEmpty()) {
            logger.warn { "No subagents available — decomposition impossible" }
            return emptyList()
        }

        val (model, provider) = configService.getModel(ModelOperation.PLAN)
        val systemPrompt = buildSystemPrompt(available, mode)

        val response = llmClient.complete(
            provider = provider,
            model = model,
            systemPrompt = systemPrompt,
            messages = listOf(LLMMessage(role = "user", content = userInput)),
            temperature = 0.2,
            kwargs = mapOf("response_format" to mapOf("type" to "json_object")),
            source = "TaskDecomposer",
        )

        return parseResponse(response.content, available, mode)
    }

    private fun buildSystemPrompt(available: List<SubagentInfo>, mode: Mode): String {
        val catalogue = available.joinToString("\n") { "- ${it.name}: ${it.description}" }
        val modeRules = when (mode) {
            Mode.PARALLEL -> """
                Mode: PARALLEL
                - Agents run concurrently with no inter-dependencies.
                - Leave `depends_on` empty for every agent.
                - Pick 2-4 agents that cover complementary angles of the user's request.
            """.trimIndent()
            Mode.PIPELINE -> """
                Mode: PIPELINE
                - Agents run sequentially; each agent consumes the previous agent's output.
                - `depends_on` must be a list with the single previous agent name, or empty for the first.
                - Use the placeholder {{prev.output}} in the `task` field to reference the previous agent's response.
                - Usually 2-3 stages (e.g. analysis → implementation → review).
            """.trimIndent()
        }

        return """
            You are a task decomposition planner. Given a user request and a catalogue of available
            subagents, produce a JSON plan that delegates the work across agents.

            Available subagents:
            $catalogue

            $modeRules

            Respond with **valid JSON only** matching this schema:
            {
              "agents": [
                {
                  "name": "<unique-spec-name>",
                  "subagent": "<subagent name from the catalogue>",
                  "task": "<concrete instruction for that agent>",
                  "depends_on": ["<other spec name>"]
                }
              ]
            }

            If the request is trivial and a single agent would suffice, respond with {"agents": []}.
            Do NOT invent subagents that are not in the catalogue.
        """.trimIndent()
    }

    private fun parseResponse(
        content: String,
        available: List<SubagentInfo>,
        mode: Mode,
    ): List<AgentSpec> {
        val cleaned = extractJson(content) ?: run {
            logger.warn { "Decomposer returned non-JSON content: ${content.take(200)}" }
            return emptyList()
        }

        val plan = try {
            json.decodeFromString(DecompositionPlan.serializer(), cleaned)
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse decomposition JSON: ${cleaned.take(300)}" }
            return emptyList()
        }

        val availableNames = available.map { it.name.lowercase() }.toSet()
        val valid = plan.agents.filter { it.subagent.lowercase() in availableNames }
        if (valid.size < plan.agents.size) {
            val dropped = plan.agents.filter { it.subagent.lowercase() !in availableNames }.map { it.subagent }
            logger.warn { "Dropped unknown subagents from plan: $dropped" }
        }

        // Guard against degenerate plans: 0 or 1 agent → no orchestration needed.
        if (valid.size < 2) {
            logger.info { "Decomposition produced ${valid.size} agents — falling back to single-agent path" }
            return emptyList()
        }

        val specs = valid.map { entry ->
            AgentSpec(
                name = entry.name.ifBlank { entry.subagent },
                profile = entry.subagent,
                task = entry.task,
                dependsOn = entry.dependsOn,
            )
        }

        return if (mode == Mode.PIPELINE) linearizePipeline(specs) else specs.map { it.copy(dependsOn = emptyList()) }
    }

    /**
     * For PIPELINE mode, force a linear chain so each agent depends only on its predecessor —
     * even if the LLM returned a fan-out graph.
     */
    private fun linearizePipeline(specs: List<AgentSpec>): List<AgentSpec> {
        return specs.mapIndexed { index, spec ->
            val deps = if (index == 0) emptyList() else listOf(specs[index - 1].name)
            spec.copy(dependsOn = deps)
        }
    }

    private fun extractJson(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        return if (start in 0 until end) trimmed.substring(start, end + 1) else null
    }

    @Serializable
    private data class DecompositionPlan(
        val agents: List<DecomposedAgent> = emptyList(),
    )

    @Serializable
    private data class DecomposedAgent(
        val name: String,
        val subagent: String,
        val task: String,
        @SerialName("depends_on")
        val dependsOn: List<String> = emptyList(),
    )
}
