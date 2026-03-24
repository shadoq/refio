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
                dependsOn = agent.dependsOn
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
    val scoring: List<ScoringMetric> = emptyList()
)

@Serializable
data class AgentDefinition(
    val name: String,
    val profile: String? = null,
    val task: String,
    val mode: String? = null,
    val model: String? = null,
    @SerialName("depends_on")
    val dependsOn: List<String> = emptyList()
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
