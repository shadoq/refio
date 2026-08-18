package pl.jclab.refio.core.services

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.config.HierarchicalConfigLoader
import pl.jclab.refio.core.db.Config
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.db.repositories.ConfigRepository
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Run-scope overrides (`--config key=value`) must win on EVERY read path.
 *
 * Some settings are never read through the typed getter: task verification, model selection and
 * anything else that inspects the raw config row goes through `getConfigWithPrecedence`. That
 * path used to skip the overrides entirely, which made `--config agent.task_verification_enabled`
 * a silent no-op with no other way to turn the feature on.
 */
class RunOverrideReadPathTest {

    private lateinit var configRepository: ConfigRepository
    private lateinit var isolatedUserHome: String
    private var originalUserHome: String? = null

    @BeforeEach
    fun setup() {
        originalUserHome = System.getProperty("user.home")
        isolatedUserHome = Files.createTempDirectory("refio-run-override-test-home").toString()
        System.setProperty("user.home", isolatedUserHome)
        HierarchicalConfigLoader.clearInstances()

        configRepository = mockk(relaxed = true)
        every { configRepository.getWithPrecedence(any(), any(), any()) } returns null
        every { configRepository.get(any(), any(), any(), any()) } returns null
    }

    @AfterEach
    fun teardown() {
        originalUserHome?.let { System.setProperty("user.home", it) }
        HierarchicalConfigLoader.clearInstances()
        Path.of(isolatedUserHome).toFile().deleteRecursively()
    }

    private fun serviceWith(overrides: Map<String, String>) = ConfigService(
        configRepository,
        defaultProjectId = "test-project",
        runConfigOverrides = overrides,
    )

    @Test
    fun `task verification can be switched on with a run override alone`() {
        val service = serviceWith(mapOf(ConfigKeys.TASK_VERIFICATION_ENABLED.key to "true"))

        // Short turn with no write tools: only an explicit setting can enable verification here.
        assertTrue(service.shouldVerifyTask(iterationCount = 0, writeToolsExecutedInTurn = 0))
    }

    @Test
    fun `an explicit run override switching verification off beats the long-turn auto-enable`() {
        val service = serviceWith(mapOf(ConfigKeys.TASK_VERIFICATION_ENABLED.key to "false"))

        assertFalse(service.shouldVerifyTask(iterationCount = 20, writeToolsExecutedInTurn = 3))
    }

    @Test
    fun `a run override beats a stored database row on the raw-row read path`() {
        every {
            configRepository.getWithPrecedence(ConfigKeys.TASK_VERIFICATION_ENABLED.key, any(), any())
        } returns Config(
            key = ConfigKeys.TASK_VERIFICATION_ENABLED.key,
            value = "false",
            scope = ConfigScope.APP,
            projectId = null,
            taskId = null,
            description = null,
            createdAt = 0,
            updatedAt = 0,
        )
        val service = serviceWith(mapOf(ConfigKeys.TASK_VERIFICATION_ENABLED.key to "true"))

        assertTrue(service.shouldVerifyTask(iterationCount = 0))
    }

    @Test
    fun `model selection honours a run override for the default chat model`() {
        val service = serviceWith(
            mapOf(ConfigKeys.DEFAULT_MODEL_CHAT.key to """{"modelId":"gpt-4.1","provider":"openai"}""")
        )

        val (model, provider) = service.getDefaultModel(pl.jclab.refio.core.api.ModelOperation.DEFAULT)

        assertEquals("gpt-4.1", model)
        assertEquals("openai", provider)
    }

    @Test
    fun `a malformed model override falls back instead of crashing model selection`() {
        val service = serviceWith(mapOf(ConfigKeys.DEFAULT_MODEL_CHAT.key to "ollama/not-json"))

        val (model, provider) = service.getDefaultModel(pl.jclab.refio.core.api.ModelOperation.DEFAULT)

        assertEquals(ConfigService.FALLBACK_MODEL, model)
        assertEquals(ConfigService.FALLBACK_PROVIDER, provider)
    }
}
