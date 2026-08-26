package pl.jclab.refio.core.session

import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.api.models.ExecutionMode
import pl.jclab.refio.api.models.Session
import pl.jclab.refio.api.models.TaskMode
import pl.jclab.refio.api.models.TaskStatus
import pl.jclab.refio.core.api.CoreApiRouter
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.config.HierarchicalConfigLoader
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.db.repositories.ConfigRepository
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.testutil.TestDatabase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Session autosave persists UI state (mode, model, execution mode, no-egress) on every session
 * write. That is bookkeeping, not a settings change, so it must not consume the "an explicit
 * settings change supersedes the project file" rule - otherwise `<project>/.refio/config.yaml`
 * would silently lose these keys seconds after the IDE starts, `general.no_egress_enabled`
 * (a security switch) included.
 */
class SessionStatePersistenceTest {

    private lateinit var db: TestDatabase.SharedInMemoryDb
    private lateinit var projectRoot: Path
    private lateinit var isolatedUserHome: String
    private var originalUserHome: String? = null

    private val projectId = "session-state-project"

    @BeforeEach
    fun setup() {
        originalUserHome = System.getProperty("user.home")
        isolatedUserHome = Files.createTempDirectory("refio-session-state-home").toString()
        System.setProperty("user.home", isolatedUserHome)
        HierarchicalConfigLoader.clearInstances()

        db = TestDatabase.createSharedInMemory()
        projectRoot = Files.createTempDirectory("refio-session-state-root")
    }

    @AfterEach
    fun teardown() {
        db.keepAlive.close()
        originalUserHome?.let { System.setProperty("user.home", it) }
        HierarchicalConfigLoader.clearInstances()
        projectRoot.toFile().deleteRecursively()
        Path.of(isolatedUserHome).toFile().deleteRecursively()
    }

    private fun projectConfiguredService(yaml: String): ConfigService {
        val dir = projectRoot.resolve(".refio")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("config.yaml"), yaml)

        val service = ConfigService(
            configRepository = ConfigRepository(),
            defaultProjectId = projectId,
            projectRoot = projectRoot,
        )
        service.initializeDefaults()
        service.materializeProjectConfig()
        return service
    }

    private fun lifecycleService(configService: ConfigService, scope: CoroutineScope): SessionLifecycleService {
        val stateManager = SessionStateManager()
        stateManager.setActiveSession(
            Session(
                id = "task-1",
                name = "Session",
                mode = TaskMode.AGENT,
                status = TaskStatus.NEW,
                createdAt = 0L,
                updatedAt = 0L,
                executionMode = ExecutionMode.INTERACTIVE,
            )
        )
        return SessionLifecycleService(
            projectRouter = mockk<CoreApiRouter>(relaxed = true),
            configService = configService,
            stateManager = stateManager,
            modeSwitchMutex = Mutex(),
            projectId = projectId,
            normalizedProjectPath = projectRoot.toAbsolutePath().toString(),
            scope = scope,
        )
    }

    /** Session state is persisted off-thread; the assertions have to see the finished write. */
    private fun CoroutineScope.awaitPendingWork() {
        val pending = coroutineContext.job.children.toList()
        runBlocking { pending.forEach { it.join() } }
    }

    @Test
    fun `session autosave leaves the project-pinned no-egress value in force`() {
        val configService = projectConfiguredService("general:\n  noEgressEnabled: true\n")
        assertEquals(true, configService.getTyped(ConfigKeys.GENERAL_NO_EGRESS_ENABLED))

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        lifecycleService(configService, scope).saveCurrentSessionState()
        scope.awaitPendingWork()

        assertNotNull(
            ConfigRepository().get(ConfigKeys.GENERAL_NO_EGRESS_ENABLED.key, ConfigScope.PROJECT, projectId = projectId),
            "autosaving session state must not drop the project row"
        )
        assertEquals(true, configService.getTyped(ConfigKeys.GENERAL_NO_EGRESS_ENABLED))
    }

    @Test
    fun `session autosave leaves the project-pinned execution mode in force`() {
        val configService = projectConfiguredService("general:\n  executionMode: AUTO\n")
        assertEquals("AUTO", configService.get(ConfigKeys.GENERAL_EXECUTION_MODE.key))

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        lifecycleService(configService, scope).saveCurrentSessionState()
        scope.awaitPendingWork()

        assertEquals("AUTO", configService.get(ConfigKeys.GENERAL_EXECUTION_MODE.key))
    }

    @Test
    fun `flipping the switch in the UI still supersedes the project file`() {
        val configService = projectConfiguredService("general:\n  noEgressEnabled: true\n")

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        lifecycleService(configService, scope).setNoEgressEnabled(false)
        scope.awaitPendingWork()

        assertEquals(
            false,
            configService.getTyped(ConfigKeys.GENERAL_NO_EGRESS_ENABLED),
            "an explicit user toggle must win over the project file until the file is edited again"
        )
    }
}
