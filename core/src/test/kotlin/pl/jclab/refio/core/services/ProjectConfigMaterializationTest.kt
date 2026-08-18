package pl.jclab.refio.core.services

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.config.HierarchicalConfigLoader
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.db.repositories.ConfigRepository
import pl.jclab.refio.testutil.TestDatabase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `<project>/.refio/config.yaml` has to reach the running agent.
 *
 * Built-in defaults are seeded as APP rows on first start and the database is checked before the
 * YAML hierarchy, so a project file used to lose to a default it never chose. The project file is
 * therefore materialized into PROJECT-scoped rows, which sit above APP in the precedence chain.
 */
class ProjectConfigMaterializationTest {

    private lateinit var db: TestDatabase.SharedInMemoryDb
    private lateinit var projectRoot: Path
    private lateinit var isolatedUserHome: String
    private var originalUserHome: String? = null

    private val projectId = "project-under-test"

    @BeforeEach
    fun setup() {
        originalUserHome = System.getProperty("user.home")
        isolatedUserHome = Files.createTempDirectory("refio-project-config-home").toString()
        System.setProperty("user.home", isolatedUserHome)
        HierarchicalConfigLoader.clearInstances()

        db = TestDatabase.createSharedInMemory()
        projectRoot = Files.createTempDirectory("refio-project-config-root")
    }

    @AfterEach
    fun teardown() {
        db.keepAlive.close()
        originalUserHome?.let { System.setProperty("user.home", it) }
        HierarchicalConfigLoader.clearInstances()
        projectRoot.toFile().deleteRecursively()
        Path.of(isolatedUserHome).toFile().deleteRecursively()
    }

    private fun writeProjectConfig(content: String) {
        val dir = projectRoot.resolve(".refio")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("config.yaml"), content)
    }

    private fun deleteProjectConfig() {
        Files.deleteIfExists(projectRoot.resolve(".refio").resolve("config.yaml"))
    }

    /** A fresh service stands in for a process restart: no in-memory cache carried over. */
    private fun newService() = ConfigService(
        configRepository = ConfigRepository(),
        defaultProjectId = projectId,
        projectRoot = projectRoot,
    )

    @Test
    fun `project file wins over the seeded built-in default`() {
        writeProjectConfig("general:\n  noEgressEnabled: true\n")
        val service = newService()
        service.initializeDefaults()

        // The seeded APP row shadows the project file until the file is materialized.
        assertEquals(false, service.getTyped(ConfigKeys.GENERAL_NO_EGRESS_ENABLED))

        service.materializeProjectConfig()

        assertEquals(true, service.getTyped(ConfigKeys.GENERAL_NO_EGRESS_ENABLED))
    }

    @Test
    fun `materialized values are readable through the raw string getter too`() {
        writeProjectConfig("limits:\n  apiCallTimeout: 111\n")
        val service = newService()
        service.initializeDefaults()
        service.materializeProjectConfig()

        assertEquals("111", service.get(ConfigKeys.API_CALL_TIMEOUT.key))
    }

    @Test
    fun `an explicit settings change wins over the project file and survives a restart`() {
        writeProjectConfig("general:\n  noEgressEnabled: true\n")
        val service = newService()
        service.initializeDefaults()
        service.materializeProjectConfig()
        assertEquals(true, service.getTyped(ConfigKeys.GENERAL_NO_EGRESS_ENABLED))

        // User flips the switch in Settings: the write lands in APP scope.
        service.setTyped(ConfigKeys.GENERAL_NO_EGRESS_ENABLED, false)
        assertEquals(false, service.getTyped(ConfigKeys.GENERAL_NO_EGRESS_ENABLED))

        // Restart with an unchanged file must not resurrect the file value.
        val restarted = newService()
        restarted.materializeProjectConfig()
        assertEquals(false, restarted.getTyped(ConfigKeys.GENERAL_NO_EGRESS_ENABLED))
    }

    @Test
    fun `editing the project file re-applies it over an earlier settings change`() {
        writeProjectConfig("general:\n  noEgressEnabled: true\n")
        val service = newService()
        service.initializeDefaults()
        service.materializeProjectConfig()
        service.setTyped(ConfigKeys.GENERAL_NO_EGRESS_ENABLED, false)

        writeProjectConfig("general:\n  noEgressEnabled: true\n  formatMarkdown: false\n")
        val restarted = newService()
        restarted.materializeProjectConfig()

        assertEquals(true, restarted.getTyped(ConfigKeys.GENERAL_NO_EGRESS_ENABLED))
        assertEquals(false, restarted.getTyped(ConfigKeys.FORMAT_MARKDOWN))
    }

    @Test
    fun `deleting the project file drops the values it had materialized`() {
        writeProjectConfig("general:\n  noEgressEnabled: true\n")
        val service = newService()
        service.initializeDefaults()
        service.materializeProjectConfig()
        assertEquals(true, service.getTyped(ConfigKeys.GENERAL_NO_EGRESS_ENABLED))

        deleteProjectConfig()
        val restarted = newService()
        restarted.materializeProjectConfig()

        assertEquals(false, restarted.getTyped(ConfigKeys.GENERAL_NO_EGRESS_ENABLED))
        assertNull(ConfigRepository().get(ConfigKeys.GENERAL_NO_EGRESS_ENABLED.key, ConfigScope.PROJECT, projectId = projectId))
    }

    @Test
    fun `materialization writes project-scoped rows rather than app-scoped ones`() {
        writeProjectConfig("general:\n  noEgressEnabled: true\n")
        val service = newService()
        service.initializeDefaults()
        service.materializeProjectConfig()

        val repository = ConfigRepository()
        assertNotNull(repository.get(ConfigKeys.GENERAL_NO_EGRESS_ENABLED.key, ConfigScope.PROJECT, projectId = projectId))
        assertEquals("false", repository.get(ConfigKeys.GENERAL_NO_EGRESS_ENABLED.key, ConfigScope.APP)?.value)
    }

    @Test
    fun `a value the project file cannot legally set is reported by name, not ignored`() {
        // Below the accepted minimum context size.
        writeProjectConfig("limits:\n  maxContextSize: 10\n")
        val service = newService()

        val failure = assertFailsWith<ConfigValidator.InvalidConfigException> {
            service.materializeProjectConfig()
        }

        assertTrue(failure.failures.any { it.key == ConfigKeys.MAX_CONTEXT_SIZE.key })
    }

    @Test
    fun `a service without a project root leaves the store untouched`() {
        writeProjectConfig("general:\n  noEgressEnabled: true\n")
        val appScopedService = ConfigService(ConfigRepository(), defaultProjectId = null, projectRoot = null)

        assertEquals(0, appScopedService.materializeProjectConfig())
    }
}
