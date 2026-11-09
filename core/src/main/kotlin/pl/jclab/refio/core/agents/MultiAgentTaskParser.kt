package pl.jclab.refio.core.agents

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import pl.jclab.refio.core.db.TaskMode

/**
 * Parser for multi-agent task definition YAML files.
 *
 * Example YAML:
 * ```yaml
 * name: "Implement REST API"
 * description: "Build CRUD API for User entity"
 * project: "."
 * agents:
 *   - name: analyst
 *     profile: business-analyst
 *     task: "Analyze requirements"
 *     model: anthropic/claude-sonnet-4-6
 *   - name: coder
 *     task: "Implement the API"
 *     depends_on: [analyst]
 *     mode: agent
 * ```
 */
object MultiAgentTaskParser {

    private val yaml = Yaml(
        configuration = YamlConfiguration(
            strictMode = false
        )
    )

    fun parse(yamlContent: String): MultiAgentTaskDefinition {
        return yaml.decodeFromString(MultiAgentTaskDefinition.serializer(), yamlContent)
    }

    /** The run's shared limits and failure rule. */
    fun toLimits(definition: MultiAgentTaskDefinition): WorkflowLimits {
        val onFailure = when (definition.onFailure?.trim()?.lowercase()) {
            null, "", "skip" -> OnFailure.SKIP
            "stop" -> OnFailure.STOP
            "continue" -> OnFailure.CONTINUE
            else -> throw IllegalArgumentException(
                "on_failure must be stop, skip or continue, not '${definition.onFailure}'"
            )
        }
        definition.maxParallel?.let { require(it >= 1) { "max_parallel must be at least 1, not $it" } }
        definition.maxMinutes?.let { require(it >= 1) { "max_minutes must be at least 1, not $it" } }
        definition.maxCost?.let { require(it > 0.0) { "max_cost must be more than 0, not $it" } }
        return WorkflowLimits(
            onFailure = onFailure,
            maxParallel = definition.maxParallel,
            maxMinutes = definition.maxMinutes,
            maxCostUsd = definition.maxCost,
        )
    }

    fun toAgentSpecs(definition: MultiAgentTaskDefinition): List<AgentSpec> {
        return definition.agents.map { agent ->
            AgentSpec(
                name = agent.name,
                profile = agent.profile,
                task = agent.task,
                mode = when (agent.mode?.lowercase()) {
                    "agent" -> TaskMode.AGENT
                    "plan" -> TaskMode.PLAN
                    "chat" -> TaskMode.CHAT
                    else -> TaskMode.AGENT
                },
                model = agent.model,
                dependsOn = agent.dependsOn,
                maxIterations = agent.maxIterations?.also {
                    require(it >= 1) { "Agent '${agent.name}': max_iterations must be at least 1, not $it" }
                },
                maxMinutes = agent.maxMinutes?.also {
                    require(it >= 1) { "Agent '${agent.name}': max_minutes must be at least 1, not $it" }
                },
            )
        }
    }
}

@Serializable
data class MultiAgentTaskDefinition(
    val name: String,
    val description: String = "",
    val project: String = ".",
    val agents: List<AgentDefinition> = emptyList(),
    val validation: List<ValidationStep> = emptyList(),
    val scoring: List<ScoringMetric> = emptyList(),
    /** stop, skip or continue; skip when absent. */
    @SerialName("on_failure")
    val onFailure: String? = null,
    /** Wall-clock limit of the whole run in minutes. */
    @SerialName("max_minutes")
    val maxMinutes: Int? = null,
    /** Spending limit of the whole run in US dollars. */
    @SerialName("max_cost")
    val maxCost: Double? = null,
    /** How many agents may run at the same time. */
    @SerialName("max_parallel")
    val maxParallel: Int? = null,
)

@Serializable
data class AgentDefinition(
    val name: String,
    val profile: String? = null,
    val task: String,
    val mode: String? = null,
    val model: String? = null,
    @SerialName("depends_on")
    val dependsOn: List<String> = emptyList(),
    @SerialName("max_iterations")
    val maxIterations: Int? = null,
    @SerialName("max_minutes")
    val maxMinutes: Int? = null,
)

@Serializable
data class ValidationStep(
    val command: String,
    val description: String = ""
)

@Serializable
data class ScoringMetric(
    val metric: String,
    val weight: Double = 1.0,
    @SerialName("lower_is_better")
    val lowerIsBetter: Boolean = false
)
