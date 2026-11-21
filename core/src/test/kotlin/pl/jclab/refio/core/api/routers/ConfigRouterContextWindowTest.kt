package pl.jclab.refio.core.api.routers

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.api.models.ExecutionMode
import pl.jclab.refio.api.models.Session
import pl.jclab.refio.api.models.TaskMode
import pl.jclab.refio.api.models.TaskStatus
import pl.jclab.refio.core.api.CoreApiRouter
import pl.jclab.refio.core.api.ModelOperation
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.llm.ContextWindowSource
import pl.jclab.refio.core.llm.ResolvedContextWindow
import pl.jclab.refio.core.llm.getModelConfigFromCache
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.session.SessionLifecycleService
import pl.jclab.refio.core.session.SessionStateManager
import kotlin.test.assertEquals

/**
 * The terminal UI shows the same context window as the plugin's status bar, plus where it came
 * from. The router must therefore pick the model the same way the plugin does, and accept the
 * model the UI just switched to before it is stored anywhere.
 */
class ConfigRouterContextWindowTest {

    private val modelRegistry = "pl.jclab.refio.core.llm.ModelRegistryKt"
    private val configService = mockk<ConfigService>()
    private val router = ConfigRouter(configService, mockk(), mockk())

    @BeforeEach
    fun setUp() {
        // The discovered-model cache is process-global; pin it so other tests cannot leak models in.
        mockkStatic(modelRegistry)
        every { getModelConfigFromCache(any()) } returns null
        every { configService.get(ConfigKeys.MAX_CONTEXT_SIZE.key, any(), any(), any()) } returns null
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(modelRegistry)
    }

    private fun ollamaSizeNeverConfigured() {
        every { configService.getTyped(ConfigKeys.PROVIDER_OLLAMA_CONTEXT_SIZE, any<String>()) } returns
            ConfigKeys.PROVIDER_OLLAMA_CONTEXT_SIZE.default
        every { configService.get(ConfigKeys.PROVIDER_OLLAMA_CONTEXT_SIZE.key, any(), any(), any()) } returns null
    }

    @Test
    fun `without a model choice the window follows the session's selected model`() {
        every { configService.getModel(ModelOperation.DEFAULT, "s1", any()) } returns Pair("qwen3.5:9b", "ollama")
        ollamaSizeNeverConfigured()

        val window = router.resolveContextWindow("s1")

        assertEquals(ResolvedContextWindow(32_768, ContextWindowSource.PROVIDER_DEFAULT), window)
    }

    @Test
    fun `a model the UI just switched to wins over the stored selection`() {
        // The parsed pair comes back as (provider, model) while the stored selection is
        // (model, provider); mixing them up would send the provider name in as a model id.
        every { configService.parseModelString("openrouter/qwen/qwen3.6-plus") } returns
            Pair("openrouter", "qwen/qwen3.6-plus")

        val window = router.resolveContextWindow("s1", model = "openrouter/qwen/qwen3.6-plus")

        assertEquals(ResolvedContextWindow(1_000_000, ContextWindowSource.MODEL_TABLE), window)
        verify(exactly = 0) { configService.getModel(any(), any(), any()) }
    }

    @Test
    fun `an auto model choice means the configured model, not a model named auto`() {
        every { configService.getModel(ModelOperation.DEFAULT, "s1", any()) } returns Pair("qwen3.5:9b", "ollama")
        ollamaSizeNeverConfigured()

        val window = router.resolveContextWindow("s1", model = "auto")

        assertEquals(ResolvedContextWindow(32_768, ContextWindowSource.PROVIDER_DEFAULT), window)
    }

    @Test
    fun `the router shows the same number as the plugin status bar for the same config`() {
        every { configService.getModel(ModelOperation.DEFAULT, "s1", any()) } returns Pair("qwen3.5:9b", "ollama")
        every { configService.getTyped(ConfigKeys.PROVIDER_OLLAMA_CONTEXT_SIZE, any<String>()) } returns 16_384
        every { configService.get(ConfigKeys.PROVIDER_OLLAMA_CONTEXT_SIZE.key, any(), any(), any()) } returns "16384"
        val stateManager = SessionStateManager().apply {
            setActiveSession(
                Session(
                    id = "s1",
                    name = "Window check",
                    mode = TaskMode.AGENT,
                    status = TaskStatus.PENDING,
                    createdAt = 1L,
                    updatedAt = 1L,
                    executionMode = ExecutionMode.INTERACTIVE,
                )
            )
        }
        val statusBarPath = SessionLifecycleService(
            projectRouter = mockk<CoreApiRouter>(relaxed = true),
            configService = configService,
            stateManager = stateManager,
            modeSwitchMutex = Mutex(),
            projectId = "project-1",
            normalizedProjectPath = "/project",
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )

        val fromStatusBar = statusBarPath.getMaxContextWindow()
        val fromRouter = router.resolveContextWindow("s1")

        assertEquals(fromStatusBar, fromRouter.tokens)
        assertEquals(16_384, fromRouter.tokens)
    }
}
