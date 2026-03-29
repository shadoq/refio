package pl.jclab.refio.core.agents

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.db.TaskMode
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MultiAgentTaskParserTest {

    @Nested
    inner class ValidParsing {

    @Test
    fun `should parse basic task definition`() {
        val yaml = """
            name: "Test Task"
            description: "A test multi-agent task"
            project: "."
            agents:
              - name: analyst
                task: "Analyze the codebase"
              - name: coder
                task: "Implement changes"
                depends_on: [analyst]
        """.trimIndent()

        val definition = MultiAgentTaskParser.parse(yaml)

        assertEquals("Test Task", definition.name)
        assertEquals("A test multi-agent task", definition.description)
        assertEquals(2, definition.agents.size)
        assertEquals("analyst", definition.agents[0].name)
        assertEquals("coder", definition.agents[1].name)
        assertEquals(listOf("analyst"), definition.agents[1].dependsOn)
    }

    @Test
    fun `should parse agent with all fields`() {
        val yaml = """
            name: "Full spec"
            agents:
              - name: reviewer
                profile: code-reviewer
                task: "Review the code"
                mode: agent
                model: anthropic/claude-sonnet-4-6
                depends_on: [analyst, coder]
        """.trimIndent()

        val definition = MultiAgentTaskParser.parse(yaml)
        val agent = definition.agents[0]

        assertEquals("reviewer", agent.name)
        assertEquals("code-reviewer", agent.profile)
        assertEquals("Review the code", agent.task)
        assertEquals("agent", agent.mode)
        assertEquals("anthropic/claude-sonnet-4-6", agent.model)
        assertEquals(listOf("analyst", "coder"), agent.dependsOn)
    }

    @Test
    fun `should parse validation and scoring`() {
        val yaml = """
            name: "With validation"
            agents:
              - name: coder
                task: "Code"
            validation:
              - command: "./gradlew build"
                description: "Compilation"
              - command: "./gradlew test"
                description: "Tests"
            scoring:
              - metric: tests_passed
                weight: 0.5
              - metric: cost
                weight: 0.1
                lower_is_better: true
        """.trimIndent()

        val definition = MultiAgentTaskParser.parse(yaml)

        assertEquals(2, definition.validation.size)
        assertEquals("./gradlew build", definition.validation[0].command)

        assertEquals(2, definition.scoring.size)
        assertEquals(0.5, definition.scoring[0].weight)
        assertTrue(definition.scoring[1].lowerIsBetter)
    }

    @Test
    fun `should convert to AgentSpecs`() {
        val yaml = """
            name: "Convert test"
            agents:
              - name: analyst
                task: "Analyze"
                mode: plan
                model: gpt-4o
              - name: coder
                task: "Code"
                mode: agent
                depends_on: [analyst]
        """.trimIndent()

        val definition = MultiAgentTaskParser.parse(yaml)
        val specs = MultiAgentTaskParser.toAgentSpecs(definition)

        assertEquals(2, specs.size)

        assertEquals("analyst", specs[0].name)
        assertEquals(TaskMode.PLAN, specs[0].mode)
        assertEquals("gpt-4o", specs[0].model)

        assertEquals("coder", specs[1].name)
        assertEquals(TaskMode.AGENT, specs[1].mode)
        assertEquals(listOf("analyst"), specs[1].dependsOn)
    }

    @Test
    fun `should handle minimal YAML`() {
        val yaml = """
            name: "Minimal"
            agents:
              - name: solo
                task: "Do everything"
        """.trimIndent()

        val definition = MultiAgentTaskParser.parse(yaml)
        val specs = MultiAgentTaskParser.toAgentSpecs(definition)

        assertEquals(1, specs.size)
        assertEquals(TaskMode.AGENT, specs[0].mode) // Default
        assertEquals(emptyList(), specs[0].dependsOn)
    }

    } // end ValidParsing

    @Nested
    inner class InvalidInputs {

        @Test
        fun `should throw on completely invalid YAML`() {
            assertFailsWith<Exception> {
                MultiAgentTaskParser.parse("not: [valid yaml: {{broken")
            }
        }

        @Test
        fun `should handle empty agents list`() {
            val yaml = """
                name: "Empty"
                agents: []
            """.trimIndent()

            val definition = MultiAgentTaskParser.parse(yaml)
            assertEquals(0, definition.agents.size)
            assertEquals(0, MultiAgentTaskParser.toAgentSpecs(definition).size)
        }

        @Test
        fun `should use defaults for missing optional fields`() {
            val yaml = """
                name: "Defaults"
                agents:
                  - name: minimal
                    task: "Just a task"
            """.trimIndent()

            val definition = MultiAgentTaskParser.parse(yaml)
            val agent = definition.agents[0]
            assertEquals(null, agent.profile)
            assertEquals(null, agent.mode)
            assertEquals(null, agent.model)
            assertEquals(emptyList(), agent.dependsOn)
        }

        @Test
        fun `should handle unknown mode gracefully`() {
            val yaml = """
                name: "Unknown mode"
                agents:
                  - name: test
                    task: "Test"
                    mode: unknown_mode_xyz
            """.trimIndent()

            val specs = MultiAgentTaskParser.toAgentSpecs(MultiAgentTaskParser.parse(yaml))
            // Unknown mode defaults to AGENT
            assertEquals(TaskMode.AGENT, specs[0].mode)
        }

        @Test
        fun `should handle mode case insensitivity`() {
            val yaml = """
                name: "Case test"
                agents:
                  - name: a1
                    task: "T"
                    mode: PLAN
                  - name: a2
                    task: "T"
                    mode: Plan
                  - name: a3
                    task: "T"
                    mode: plan
            """.trimIndent()

            val specs = MultiAgentTaskParser.toAgentSpecs(MultiAgentTaskParser.parse(yaml))
            assertEquals(TaskMode.PLAN, specs[0].mode)
            assertEquals(TaskMode.PLAN, specs[1].mode)
            assertEquals(TaskMode.PLAN, specs[2].mode)
        }

        @Test
        fun `should use default description when not provided`() {
            val yaml = """
                name: "No desc"
                agents:
                  - name: test
                    task: "Test"
            """.trimIndent()

            val definition = MultiAgentTaskParser.parse(yaml)
            assertEquals("", definition.description)
        }

        @Test
        fun `should use default project when not provided`() {
            val yaml = """
                name: "No project"
                agents:
                  - name: test
                    task: "Test"
            """.trimIndent()

            val definition = MultiAgentTaskParser.parse(yaml)
            assertEquals(".", definition.project)
        }
    }
}
