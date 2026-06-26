package pl.jclab.refio.core.services

import pl.jclab.refio.core.config.ConfigKeys

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.config.HierarchicalConfigLoader
import pl.jclab.refio.core.db.Config
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.db.repositories.ConfigRepository
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigServiceTest {

    private lateinit var configRepository: ConfigRepository
    private lateinit var configService: ConfigService
    private lateinit var isolatedUserHome: String
    private var originalUserHome: String? = null

    @BeforeEach
    fun setup() {
        originalUserHome = System.getProperty("user.home")
        isolatedUserHome = Files.createTempDirectory("refio-config-service-test-home").toString()
        System.setProperty("user.home", isolatedUserHome)
        HierarchicalConfigLoader.clearInstances()

        configRepository = mockk(relaxed = true)
        // Create ConfigService without projectRoot to avoid YAML loading
        configService = ConfigService(configRepository, defaultProjectId = "test-project")
    }

    @AfterEach
    fun teardown() {
        originalUserHome?.let { System.setProperty("user.home", it) }
        HierarchicalConfigLoader.clearInstances()
        Path.of(isolatedUserHome).toFile().deleteRecursively()
    }

    private fun createConfig(
        key: String,
        value: String,
        scope: ConfigScope = ConfigScope.APP,
        taskId: String? = null,
        projectId: String? = null
    ) = Config(
        key = key,
        value = value,
        scope = scope,
        projectId = projectId,
        taskId = taskId,
        description = null,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis()
    )

    @Nested
    inner class GetConfigTests {

        @Test
        fun `should return value from database when present`() {
            // Given
            every { configRepository.get("test.key", ConfigScope.APP) } returns
                createConfig("test.key", "db-value")

            // When
            val result = configService.get("test.key")

            // Then
            assertEquals("db-value", result)
        }

        @Test
        fun `should return null when key not found in db or yaml`() {
            // Given
            every { configRepository.get(any(), any(), any(), any()) } returns null
            every { configRepository.getWithPrecedence(any(), any(), any()) } returns null

            // When
            val result = configService.get("nonexistent.key")

            // Then
            assertNull(result)
        }

        @Test
        fun `should use precedence when taskId provided`() {
            // Given - TASK scope config exists
            val taskId = "task-123"
            every {
                configRepository.getWithPrecedence("test.key", taskId, "test-project")
            } returns createConfig("test.key", "task-value", ConfigScope.TASK, taskId = taskId)

            // When
            val result = configService.get("test.key", taskId = taskId)

            // Then
            assertEquals("task-value", result)
        }
    }

    @Nested
    inner class ScopePrecedenceTests {

        @Test
        fun `task scope should override app scope`() {
            // Given - Both APP and TASK configs exist, but getWithPrecedence returns TASK
            val taskId = "task-456"
            every {
                configRepository.getWithPrecedence("some.key", taskId, "test-project")
            } returns createConfig("some.key", "task-override", ConfigScope.TASK, taskId = taskId)

            // When
            val result = configService.get("some.key", taskId = taskId)

            // Then
            assertEquals("task-override", result)
        }

        @Test
        fun `project scope should be checked when specified`() {
            // Given
            every {
                configRepository.get("project.key", ConfigScope.PROJECT, projectId = "test-project")
            } returns createConfig("project.key", "project-value", ConfigScope.PROJECT, projectId = "test-project")

            // When
            val result = configService.get("project.key", scope = ConfigScope.PROJECT)

            // Then
            assertEquals("project-value", result)
        }

        @Test
        fun `should return null when project scope queried but no project id`() {
            // Given - no defaultProjectId set
            val svc = ConfigService(configRepository, defaultProjectId = null)
            every { configRepository.get(any(), any(), any(), any()) } returns null

            // When
            val result = svc.get("some.key", scope = ConfigScope.PROJECT)

            // Then
            assertNull(result)
        }
    }

    @Nested
    inner class BooleanConfigTests {

        @Test
        fun `TASK_VERIFICATION_ENABLED should return false by default`() {
            every { configRepository.getWithPrecedence(any(), any(), any()) } returns null
            val result: Boolean = configService.getTyped(pl.jclab.refio.core.config.ConfigKeys.TASK_VERIFICATION_ENABLED)
            assertEquals(false, result)
        }
    }

    @Nested
    inner class IntConfigTests {

        @Test
        fun `MAX_CONSECUTIVE_TOOL_ERRORS should return default when no config`() {
            every { configRepository.getWithPrecedence(any(), any(), any()) } returns null
            val result: Int = configService.getTyped(pl.jclab.refio.core.config.ConfigKeys.MAX_CONSECUTIVE_TOOL_ERRORS)
            assertEquals(ConfigKeys.MAX_CONSECUTIVE_TOOL_ERRORS.default, result)
        }

        @Test
        fun `MAX_CONSECUTIVE_TOOL_ERRORS should parse integer from config`() {
            every {
                configRepository.getWithPrecedence(ConfigKeys.MAX_CONSECUTIVE_TOOL_ERRORS.key, any(), any())
            } returns createConfig(ConfigKeys.MAX_CONSECUTIVE_TOOL_ERRORS.key, "7")
            val result: Int = configService.getTyped(pl.jclab.refio.core.config.ConfigKeys.MAX_CONSECUTIVE_TOOL_ERRORS)
            assertEquals(7, result)
        }

        @Test
        fun `MAX_CONSECUTIVE_TOOL_ERRORS should use default for invalid values`() {
            every {
                configRepository.getWithPrecedence(ConfigKeys.MAX_CONSECUTIVE_TOOL_ERRORS.key, any(), any())
            } returns createConfig(ConfigKeys.MAX_CONSECUTIVE_TOOL_ERRORS.key, "not-a-number")
            val result: Int = configService.getTyped(pl.jclab.refio.core.config.ConfigKeys.MAX_CONSECUTIVE_TOOL_ERRORS)
            assertEquals(ConfigKeys.MAX_CONSECUTIVE_TOOL_ERRORS.default, result)
        }

        @Test
        fun `MAX_CONSECUTIVE_TOOL_ERRORS should parse zero from config`() {
            every {
                configRepository.getWithPrecedence(ConfigKeys.MAX_CONSECUTIVE_TOOL_ERRORS.key, any(), any())
            } returns createConfig(ConfigKeys.MAX_CONSECUTIVE_TOOL_ERRORS.key, "0")
            val result: Int = configService.getTyped(pl.jclab.refio.core.config.ConfigKeys.MAX_CONSECUTIVE_TOOL_ERRORS)
            assertEquals(0, result)
        }

        @Test
        fun `MAX_ITERATIONS should return default when no config`() {
            every { configRepository.getWithPrecedence(any(), any(), any()) } returns null
            val result: Int = configService.getTyped(pl.jclab.refio.core.config.ConfigKeys.MAX_ITERATIONS)
            assertEquals(ConfigKeys.MAX_ITERATIONS.default, result)
        }
    }

    @Nested
    inner class TaskVerificationTests {

        @Test
        fun `shouldVerifyTask should respect explicit true setting`() {
            // Given
            every {
                configRepository.getWithPrecedence(ConfigKeys.TASK_VERIFICATION_ENABLED.key, any(), any())
            } returns createConfig(ConfigKeys.TASK_VERIFICATION_ENABLED.key, "true")

            // When
            val result = configService.shouldVerifyTask(iterationCount = 1)

            // Then
            assertTrue(result)
        }

        @Test
        fun `shouldVerifyTask should respect explicit false setting`() {
            // Given
            every {
                configRepository.getWithPrecedence(ConfigKeys.TASK_VERIFICATION_ENABLED.key, any(), any())
            } returns createConfig(ConfigKeys.TASK_VERIFICATION_ENABLED.key, "false")

            // When
            val result = configService.shouldVerifyTask(iterationCount = 100)

            // Then
            assertEquals(false, result)
        }

        @Test
        fun `shouldVerifyTask should auto-enable for long turns`() {
            // Given - no explicit setting
            every { configRepository.getWithPrecedence(any(), any(), any()) } returns null

            // When
            val result = configService.shouldVerifyTask(iterationCount = 6)

            // Then - auto-enabled because iterationCount >= 5
            assertTrue(result)
        }

        @Test
        fun `shouldVerifyTask should auto-enable when write tools executed`() {
            // Given - no explicit setting
            every { configRepository.getWithPrecedence(any(), any(), any()) } returns null

            // When
            val result = configService.shouldVerifyTask(iterationCount = 1, writeToolsExecutedInTurn = 2)

            // Then - auto-enabled because write tools were used
            assertTrue(result)
        }

        @Test
        fun `shouldVerifyTask should not auto-enable for short turns without writes`() {
            // Given - no explicit setting
            every { configRepository.getWithPrecedence(any(), any(), any()) } returns null

            // When
            val result = configService.shouldVerifyTask(iterationCount = 2, writeToolsExecutedInTurn = 0)

            // Then - not auto-enabled
            assertEquals(false, result)
        }
    }

    @Nested
    inner class DefaultModelTests {

        @Test
        fun `getDefaultModel should return fallback when no config exists`() {
            // Given
            every { configRepository.getWithPrecedence(any(), any(), any()) } returns null
            every { configRepository.get(any(), any(), any(), any()) } returns null

            // When
            val (model, provider) = configService.getDefaultModel(ModelOperation.DEFAULT)

            // Then - should return fallback
            assertEquals(ConfigService.FALLBACK_MODEL, model)
            assertEquals(ConfigService.FALLBACK_PROVIDER, provider)
        }

        @Test
        fun `getDefaultModel for WEAK should return weak fallback`() {
            // Given
            every { configRepository.getWithPrecedence(any(), any(), any()) } returns null
            every { configRepository.get(any(), any(), any(), any()) } returns null

            // When
            val (model, provider) = configService.getDefaultModel(ModelOperation.WEAK)

            // Then
            assertEquals(ConfigService.FALLBACK_WEAK_MODEL, model)
            assertEquals(ConfigService.FALLBACK_WEAK_PROVIDER, provider)
        }

        @Test
        fun `getDefaultModel for EMBEDDING should return embedding fallback`() {
            // Given
            every { configRepository.getWithPrecedence(any(), any(), any()) } returns null
            every { configRepository.get(any(), any(), any(), any()) } returns null

            // When
            val (model, provider) = configService.getDefaultModel(ModelOperation.EMBEDDING)

            // Then
            assertEquals(ConfigService.FALLBACK_EMBEDDING_MODEL, model)
            assertEquals(ConfigService.FALLBACK_EMBEDDING_PROVIDER, provider)
        }

        @Test
        fun `getDefaultModel should use DB value when present for CHAT`() {
            // Given
            val json = """{"modelId":"gpt-4.1","provider":"openai"}"""
            every {
                configRepository.getWithPrecedence(ConfigKeys.DEFAULT_MODEL_CHAT.key, any(), any())
            } returns createConfig(ConfigKeys.DEFAULT_MODEL_CHAT.key, json)

            // When
            val (model, provider) = configService.getDefaultModel(ModelOperation.DEFAULT)

            // Then
            assertEquals("gpt-4.1", model)
            assertEquals("openai", provider)
        }

        @Test
        fun `getDefaultModel for WEAK should inherit DEFAULT when configured`() {
            // Given
            val defaultJson = """{"modelId":"gpt-4.1","provider":"openai"}"""
            val inheritJson = """{"modelId":"inherit","provider":"inherit"}"""
            every {
                configRepository.getWithPrecedence(ConfigKeys.WEAK_MODEL.key, any(), any())
            } returns createConfig(ConfigKeys.WEAK_MODEL.key, inheritJson)
            every {
                configRepository.getWithPrecedence(ConfigKeys.DEFAULT_MODEL_CHAT.key, any(), any())
            } returns createConfig(ConfigKeys.DEFAULT_MODEL_CHAT.key, defaultJson)

            // When
            val (model, provider) = configService.getDefaultModel(ModelOperation.WEAK)

            // Then
            assertEquals("gpt-4.1", model)
            assertEquals("openai", provider)
        }

        @Test
        fun `getStrongModel should inherit DEFAULT when configured`() {
            // Given
            val defaultJson = """{"modelId":"gpt-4.1","provider":"openai"}"""
            val inheritJson = """{"modelId":"inherit","provider":"inherit"}"""
            every {
                configRepository.getWithPrecedence(ConfigKeys.STRONG_MODEL.key, any(), any())
            } returns createConfig(ConfigKeys.STRONG_MODEL.key, inheritJson)
            every {
                configRepository.getWithPrecedence(ConfigKeys.DEFAULT_MODEL_CHAT.key, any(), any())
            } returns createConfig(ConfigKeys.DEFAULT_MODEL_CHAT.key, defaultJson)

            // When
            val result = configService.getStrongModel()

            // Then
            assertEquals(Pair("gpt-4.1", "openai"), result)
        }
    }

    @Nested
    inner class BuiltinSubagentOverridesTests {

        @Test
        fun `getBuiltinSubagentEnabledOverrides should return empty map when no config`() {
            // Given
            every { configRepository.get(ConfigKeys.SUBAGENTS_BUILTIN_ENABLED.key, ConfigScope.APP) } returns null

            // When
            val overrides = configService.getBuiltinSubagentEnabledOverrides()

            // Then
            assertTrue(overrides.isEmpty())
        }

        @Test
        fun `getBuiltinSubagentEnabledOverrides should parse JSON correctly`() {
            // Given
            val json = """{"security-reviewer":true,"code-analyzer":false}"""
            every {
                configRepository.get(ConfigKeys.SUBAGENTS_BUILTIN_ENABLED.key, ConfigScope.APP)
            } returns createConfig(ConfigKeys.SUBAGENTS_BUILTIN_ENABLED.key, json)

            // When
            val overrides = configService.getBuiltinSubagentEnabledOverrides()

            // Then
            assertEquals(2, overrides.size)
            assertEquals(true, overrides["security-reviewer"])
            assertEquals(false, overrides["code-analyzer"])
        }
    }

    /**
     * Run-scope config overrides (docs/0063): values injected at process start (CLI --config /
     * --config-file) that must win over DB/YAML/default WITHOUT being persisted back to the
     * shared database. Powers headless e2e/benchmark of different settings.
     */
    @Nested
    inner class RunOverrideTests {

        @Test
        fun `run override takes precedence over database value`() {
            // Given - DB has a value, but a run-scope override is also present
            every {
                configRepository.getWithPrecedence(ConfigKeys.MAX_CONSECUTIVE_TOOL_ERRORS.key, any(), any())
            } returns createConfig(ConfigKeys.MAX_CONSECUTIVE_TOOL_ERRORS.key, "7")
            val svc = ConfigService(
                configRepository,
                defaultProjectId = "test-project",
                runConfigOverrides = mapOf(ConfigKeys.MAX_CONSECUTIVE_TOOL_ERRORS.key to "9")
            )

            // When
            val result: Int = svc.getTyped(ConfigKeys.MAX_CONSECUTIVE_TOOL_ERRORS)

            // Then - the override wins over the DB value
            assertEquals(9, result)
        }

        @Test
        fun `run override takes precedence over built-in default`() {
            // Given - no DB value, only a run-scope override
            every { configRepository.getWithPrecedence(any(), any(), any()) } returns null
            val svc = ConfigService(
                configRepository,
                defaultProjectId = "test-project",
                runConfigOverrides = mapOf(ConfigKeys.MAX_ITERATIONS.key to "80")
            )

            // When
            val result: Int = svc.getTyped(ConfigKeys.MAX_ITERATIONS)

            // Then
            assertEquals(80, result)
        }

        @Test
        fun `raw get returns run override over database`() {
            // Given - DB has a different value for the same key
            every { configRepository.get("some.key", ConfigScope.APP) } returns
                createConfig("some.key", "db-value")
            val svc = ConfigService(
                configRepository,
                defaultProjectId = "test-project",
                runConfigOverrides = mapOf("some.key" to "override-value")
            )

            // When
            val result = svc.get("some.key")

            // Then
            assertEquals("override-value", result)
        }

        @Test
        fun `run override read does not propagate to database`() {
            // Given - override present, no DB value
            every { configRepository.getWithPrecedence(any(), any(), any()) } returns null
            val svc = ConfigService(
                configRepository,
                defaultProjectId = "test-project",
                runConfigOverrides = mapOf(ConfigKeys.MAX_ITERATIONS.key to "80")
            )

            // When - reading the overridden key
            svc.getTyped(ConfigKeys.MAX_ITERATIONS)

            // Then - the override is read-only; it never writes back to the shared DB
            verify(exactly = 0) { configRepository.set(any(), any(), any(), any(), any(), any()) }
        }

        @Test
        fun `unparseable run override falls through to default`() {
            // Given - override is garbage for an int key, no DB value
            every { configRepository.getWithPrecedence(any(), any(), any()) } returns null
            val svc = ConfigService(
                configRepository,
                defaultProjectId = "test-project",
                runConfigOverrides = mapOf(ConfigKeys.MAX_ITERATIONS.key to "not-a-number")
            )

            // When
            val result: Int = svc.getTyped(ConfigKeys.MAX_ITERATIONS)

            // Then - falls through to the key's default (CLI layer rejects bad values loudly upstream)
            assertEquals(ConfigKeys.MAX_ITERATIONS.default, result)
        }
    }
}
