package pl.jclab.refio.core.api.routers

import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import pl.jclab.refio.core.api.*
import pl.jclab.refio.core.db.repositories.ConfigRepository
import pl.jclab.refio.core.llm.LLMClient
import pl.jclab.refio.core.llm.ModelConfig
import pl.jclab.refio.core.llm.clearModelsCache
import pl.jclab.refio.core.llm.getAllModels
import pl.jclab.refio.core.llm.getModelsByProvider
import pl.jclab.refio.core.services.ConfigService

class ConfigRouterTest {

    private lateinit var configService: ConfigService
    private lateinit var llmClient: LLMClient
    private lateinit var configRepository: ConfigRepository
    private lateinit var configRouter: ConfigRouter
    private val mockModels = listOf(
        ModelConfig(
            id = "gpt-4",
            name = "GPT-4",
            provider = "openai",
            capabilities = listOf("CHAT_COMPLETION"),
            maxContext = 8192,
            costPer1mInput = 1.0,
            costPer1mOutput = 1.0
        )
    )

    @BeforeEach
    fun setup() {
        configService = mockk()
        llmClient = mockk()
        configRepository = mockk()
        configRouter = ConfigRouter(configService, llmClient, configRepository)
        mockkStatic("pl.jclab.refio.core.llm.ModelRegistryKt")
    }

    @AfterEach
    fun teardown() {
        unmockkAll()
    }

    @Test
    fun `getModels returns all models when provider is null`() = runBlocking {
        // When
        coEvery { getAllModels(configService) } returns mockModels
        val response = configRouter.getModels(provider = null)

        // Then
        assertNotNull(response)
        assertTrue(response.models.isNotEmpty())
    }

    @Test
    fun `getDefaultModel returns correct model for operation`() = runBlocking {
        // Given
        val operation = ModelOperation.DEFAULT
        every { configService.getDefaultModel(operation, null) } returns Pair("gpt-4", "openai")

        // When
        val response = configRouter.getDefaultModel(operation, null)

        // Then
        assertEquals("DEFAULT", response.operation)
        assertEquals("gpt-4", response.modelId)
        assertEquals("openai", response.provider)
        verify { configService.getDefaultModel(operation, null) }
    }

    @Test
    fun `setDefaultModel updates model for operation`() = runBlocking {
        // Given
        val operation = ModelOperation.PLAN
        val modelId = "gpt-4"
        val provider = "openai"
        val request = SetDefaultModelRequest(operation, modelId, provider)
        every { configService.setDefaultModel(operation, modelId, provider, null) } just Runs

        // When
        val response = configRouter.setDefaultModel(request, null)

        // Then
        assertEquals("PLAN", response.operation)
        assertEquals(modelId, response.modelId)
        assertEquals(provider, response.provider)
        assertEquals("app", response.scope)
        verify { configService.setDefaultModel(operation, modelId, provider, null) }
    }

    @Test
    fun `setDefaultModelAllModes updates model for all operations`() = runBlocking {
        // Given
        val modelId = "gpt-4"
        val provider = "openai"
        val request = SetDefaultModelAllModesRequest(modelId, provider)
        every { configService.setDefaultModelAllModes(modelId, provider, null) } just Runs

        // When
        val response = configRouter.setDefaultModelAllModes(request, null)

        // Then
        assertEquals(modelId, response.modelId)
        assertEquals(provider, response.provider)
        assertEquals("app", response.scope)
        assertEquals(3, response.modes.size) // DEFAULT, PLAN, CODING
        verify { configService.setDefaultModelAllModes(modelId, provider, null) }
    }

    @Test
    fun `getModelsWithVisibility returns models with visibility info`() = runBlocking {
        // When
        coEvery { getAllModels(configService) } returns mockModels
        every { configService.getModelsVisibility() } returns emptyMap()
        val models = configRouter.getModelsWithVisibility(provider = null)

        // Then
        assertNotNull(models)
        assertTrue(models.isNotEmpty())
        models.forEach { model ->
            assertNotNull(model.id)
            assertNotNull(model.provider)
            assertNotNull(model.name)
            assertTrue(model.contextSize > 0)
        }
    }

    @Test
    fun `buildRateLimitedConnectionResult returns success with warning details`() {
        val result = configRouter.buildRateLimitedConnectionResult(
            provider = "zai",
            models = listOf(
                ModelConfig(
                    id = "glm-4.5",
                    name = "GLM-4.5",
                    provider = "zai",
                    capabilities = listOf("CHAT_COMPLETION"),
                    maxContext = 128000,
                    costPer1mInput = 0.0,
                    costPer1mOutput = 0.0
                )
            ),
            testModel = "glm-4.5",
            latency = 1234
        )

        assertTrue(result.success)
        assertEquals(1234, result.latencyMs)
        assertEquals("Connected successfully, but the probe request was rate limited", result.message)
        assertEquals("rate_limited", result.details?.get("warning"))
    }

    // A local server that lists its models is reachable. Probing a chat model on top of that would
    // load the first listed model (often a large one) into GPU memory, which alone can outlast the
    // 30 s test budget and report a running server as unreachable.
    @Test
    fun `ollama connection test succeeds on model list without loading a model`() = runBlocking {
        every { configService.get(any(), any()) } returns "http://127.0.0.1:11434"
        every { configService.set(any(), any(), any()) } just Runs
        every { clearModelsCache() } just Runs
        coEvery { getModelsByProvider("ollama", configService) } returns listOf(
            ModelConfig(
                id = "qwen3.8:27b",
                name = "qwen3.8:27b",
                provider = "ollama",
                capabilities = listOf("CHAT_COMPLETION"),
                maxContext = 65536,
                costPer1mInput = 0.0,
                costPer1mOutput = 0.0
            )
        )

        val result = configRouter.testProviderConnection("ollama", mapOf("base_url" to "http://127.0.0.1:11434"))

        assertTrue(result.success, result.message)
        confirmVerified(llmClient)
    }
}
