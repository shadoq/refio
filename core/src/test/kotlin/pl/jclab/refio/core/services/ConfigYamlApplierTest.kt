package pl.jclab.refio.core.services

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.config.ConfigYaml
import pl.jclab.refio.core.config.ContextConfig
import pl.jclab.refio.core.config.ToolsConfig
import pl.jclab.refio.core.db.Config
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.db.repositories.ConfigRepository
import pl.jclab.refio.core.utils.GsonInstance.gson
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Export config, edit the YAML, load it back" has to be a closed loop.
 *
 * `tools.permissions` and the `context` block were emitted on export but had no import branch,
 * so editing them in the YAML changed nothing - including via the Settings "Reload from YAML"
 * button.
 */
class ConfigYamlApplierTest {

    private lateinit var configRepository: ConfigRepository
    private val written = mutableMapOf<String, String>()

    @BeforeEach
    fun setup() {
        written.clear()
        configRepository = mockk(relaxed = true)
        every { configRepository.get(any(), any(), any(), any()) } returns null
    }

    private fun applier() = ConfigYamlApplier(
        configRepository = configRepository,
        setter = { key, value -> written[key] = value },
        modelsVisibilityGetter = { emptyMap() },
        defaultModelSetter = { _, _, _ -> },
        modelStringParser = { model -> "ollama" to model },
    )

    private fun storedRow(key: String, value: String) = Config(
        key = key,
        value = value,
        scope = ConfigScope.APP,
        projectId = null,
        taskId = null,
        description = null,
        createdAt = 0,
        updatedAt = 0,
    )

    private fun permissions(vararg entries: Pair<String, Pair<String, String>>) = ToolsConfig(
        permissions = entries.associate { (tool, modes) ->
            tool to pl.jclab.refio.core.config.ToolPermissionConfig(planMode = modes.first, agentMode = modes.second)
        }
    )

    private fun writtenPermissions(): Map<String, ToolPermissionConfig>? =
        written[ConfigKeys.TOOLS_PERMISSIONS.key]
            ?.let { gson.fromJson(it, ToolPermissions::class.java) }
            ?.tools

    @Test
    fun `tool permissions from YAML reach the stored permissions row`() {
        val yaml = ConfigYaml(tools = permissions("read_file" to ("ON" to "ON"), "run_code" to ("OFF" to "ASK")))

        applier().apply(yaml, overwrite = true)

        val stored = writtenPermissions()
        assertEquals(PermissionLevel.ON, stored?.get("read_file")?.planMode)
        assertEquals(PermissionLevel.ASK, stored?.get("run_code")?.agentMode)
    }

    @Test
    fun `reloading keeps stored permissions for tools the YAML does not mention`() {
        every { configRepository.get(ConfigKeys.TOOLS_PERMISSIONS.key, ConfigScope.APP) } returns storedRow(
            ConfigKeys.TOOLS_PERMISSIONS.key,
            gson.toJson(
                ToolPermissions(
                    mapOf("grep_search" to ToolPermissionConfig(PermissionLevel.ON, PermissionLevel.ON))
                )
            )
        )
        val yaml = ConfigYaml(tools = permissions("read_file" to ("ON" to "ON")))

        applier().apply(yaml, overwrite = true)

        val stored = writtenPermissions()
        assertEquals(PermissionLevel.ON, stored?.get("grep_search")?.planMode)
        assertEquals(PermissionLevel.ON, stored?.get("read_file")?.planMode)
    }

    @Test
    fun `load-if-missing never downgrades an already stored tool permission`() {
        every { configRepository.get(ConfigKeys.TOOLS_PERMISSIONS.key, ConfigScope.APP) } returns storedRow(
            ConfigKeys.TOOLS_PERMISSIONS.key,
            gson.toJson(
                ToolPermissions(
                    mapOf("run_terminal_command" to ToolPermissionConfig(PermissionLevel.OFF, PermissionLevel.OFF))
                )
            )
        )
        val yaml = ConfigYaml(
            tools = permissions(
                "run_terminal_command" to ("ON" to "ON"),
                "read_file" to ("ON" to "ON"),
            )
        )

        applier().apply(yaml, overwrite = false)

        val stored = writtenPermissions()
        assertEquals(PermissionLevel.OFF, stored?.get("run_terminal_command")?.agentMode)
        assertEquals(PermissionLevel.ON, stored?.get("read_file")?.agentMode)
    }

    @Test
    fun `an unknown permission level is rejected instead of silently opening a tool`() {
        val yaml = ConfigYaml(
            tools = permissions(
                "run_terminal_command" to ("yes" to "always"),
                "read_file" to ("ON" to "ON"),
            )
        )

        applier().apply(yaml, overwrite = true)

        val stored = writtenPermissions()
        assertFalse(stored.orEmpty().containsKey("run_terminal_command"))
        assertTrue(stored.orEmpty().containsKey("read_file"))
    }

    @Test
    fun `an entry missing one of the two modes is rejected`() {
        val yaml = ConfigYaml(
            tools = ToolsConfig(
                permissions = mapOf(
                    "read_file" to pl.jclab.refio.core.config.ToolPermissionConfig(planMode = "ON", agentMode = null)
                )
            )
        )

        applier().apply(yaml, overwrite = true)

        assertNull(written[ConfigKeys.TOOLS_PERMISSIONS.key])
    }

    @Test
    fun `context block from YAML reaches the config store`() {
        val yaml = ConfigYaml(
            context = ContextConfig(
                recentWorkFullDataLimit = 3,
                budgetTotalTokens = 40_000,
                budgetInputRatio = 0.7,
                workingMemoryMaxFacts = 7,
                budgetSections = mapOf("recent_work" to 1_500),
            )
        )

        applier().apply(yaml, overwrite = true)

        assertEquals("7", written[ConfigKeys.WORKING_MEMORY_MAX_FACTS.key])
        assertEquals("3", written[ConfigKeys.RECENT_WORK_FULL_DATA_LIMIT.key])
        assertEquals("40000", written[ConfigKeys.CONTEXT_BUDGET_TOTAL_TOKENS.key])
        assertEquals("0.7", written[ConfigKeys.CONTEXT_BUDGET_INPUT_RATIO.key])
        assertEquals("1500", written["${ConfigService.KEY_CONTEXT_BUDGET_SECTION_PREFIX}recent_work"])
    }
}
