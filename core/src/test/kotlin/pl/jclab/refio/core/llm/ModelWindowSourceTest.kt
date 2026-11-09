package pl.jclab.refio.core.llm

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.services.ConfigService
import kotlin.test.assertEquals

/**
 * A status line that shows "32K" is only useful if it also says why: an Ollama size the user never
 * configured reads very differently from one they chose. These tests pin which resolution step
 * each window comes from, and that the number always equals what [ModelWindow.resolve] returns.
 */
class ModelWindowSourceTest {

    private val modelRegistry = "pl.jclab.refio.core.llm.ModelRegistryKt"

    @BeforeEach
    fun setUp() {
        // The discovered-model cache is process-global; pin it so other tests cannot leak models in.
        mockkStatic(modelRegistry)
        every { getModelConfigFromCache(any()) } returns null
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(modelRegistry)
    }

    /** No explicit max-context ceiling and no provider size key set, unless a test says otherwise. */
    private fun config() = mockk<ConfigService>().also {
        every { it.get(ConfigKeys.MAX_CONTEXT_SIZE.key, any(), any(), any()) } returns null
    }

    private fun resolved(provider: String, model: String?, config: ConfigService): ResolvedContextWindow {
        val withSource = ModelWindow.resolveWithSource(provider, model, config, taskId = "t1")
        assertEquals(
            ModelWindow.resolve(provider, model, config, taskId = "t1"),
            withSource.tokens,
            "the window with its source must be the same number the engine uses",
        )
        return withSource
    }

    @Test
    fun `an Ollama size the user set is reported as explicit`() {
        val config = config()
        every { config.getTyped(ConfigKeys.PROVIDER_OLLAMA_CONTEXT_SIZE, any<String>()) } returns 16_384
        every { config.get(ConfigKeys.PROVIDER_OLLAMA_CONTEXT_SIZE.key, any(), any(), any()) } returns "16384"

        val window = resolved("ollama", "qwen3.5:9b", config)

        assertEquals(ResolvedContextWindow(16_384, ContextWindowSource.PROVIDER_EXPLICIT), window)
    }

    @Test
    fun `an Ollama size nobody configured is the 32768 default and says so`() {
        // The case the source exists for: a long session silently truncated at 32K because the
        // key was never set, while the status line looked like a deliberate choice.
        val config = config()
        every { config.getTyped(ConfigKeys.PROVIDER_OLLAMA_CONTEXT_SIZE, any<String>()) } returns
            ConfigKeys.PROVIDER_OLLAMA_CONTEXT_SIZE.default
        every { config.get(ConfigKeys.PROVIDER_OLLAMA_CONTEXT_SIZE.key, any(), any(), any()) } returns null

        val window = resolved("ollama", "qwen3.5:9b", config)

        assertEquals(ResolvedContextWindow(32_768, ContextWindowSource.PROVIDER_DEFAULT), window)
    }

    @Test
    fun `a model known to the static table reports the table as its source`() {
        val window = resolved("openrouter", "qwen/qwen3.6-plus", config())

        assertEquals(ResolvedContextWindow(1_000_000, ContextWindowSource.MODEL_TABLE), window)
    }

    @Test
    fun `a model only the provider listing knows reports discovery as its source`() {
        every { getModelConfigFromCache("unregistered-vendor/local-coder") } returns ModelConfig(
            id = "unregistered-vendor/local-coder",
            name = "local-coder",
            provider = "openrouter",
            capabilities = listOf("CHAT_COMPLETION"),
            maxContext = 262_144,
            costPer1mInput = 0.0,
            costPer1mOutput = 0.0,
        )

        val window = resolved("openrouter", "unregistered-vendor/local-coder", config())

        assertEquals(ResolvedContextWindow(262_144, ContextWindowSource.DISCOVERED), window)
    }

    @Test
    fun `a model nothing knows falls back to the global max context`() {
        val config = config()
        every { config.getTyped(ConfigKeys.MAX_CONTEXT_SIZE, any<String>()) } returns 850_000

        val window = resolved("openrouter", "unregistered-vendor/nope-2099", config)

        assertEquals(ResolvedContextWindow(850_000, ContextWindowSource.FALLBACK), window)
    }

    @Test
    fun `an explicit max context that lowers a declared provider window is reported as the ceiling`() {
        val config = mockk<ConfigService>()
        every { config.get(ConfigKeys.MAX_CONTEXT_SIZE.key, any(), any(), any()) } returns "200000"
        every { config.getTyped(ConfigKeys.PROVIDER_CUSTOM_OPENAI_CONTEXT_SIZE, any<String>()) } returns 524_288
        every { config.get(ConfigKeys.PROVIDER_CUSTOM_OPENAI_CONTEXT_SIZE.key, any(), any(), any()) } returns "524288"

        val window = resolved("generic_openai", "qwen3-coder-local", config)

        assertEquals(ResolvedContextWindow(200_000, ContextWindowSource.CEILING), window)
    }

    @Test
    fun `a ceiling above the window changes nothing, so the window keeps its own source`() {
        val config = mockk<ConfigService>()
        every { config.get(ConfigKeys.MAX_CONTEXT_SIZE.key, any(), any(), any()) } returns "200000"
        every { config.getTyped(ConfigKeys.PROVIDER_OLLAMA_CONTEXT_SIZE, any<String>()) } returns 16_384
        every { config.get(ConfigKeys.PROVIDER_OLLAMA_CONTEXT_SIZE.key, any(), any(), any()) } returns "16384"

        val window = resolved("ollama", "qwen3.5:9b", config)

        assertEquals(ResolvedContextWindow(16_384, ContextWindowSource.PROVIDER_EXPLICIT), window)
    }
}
