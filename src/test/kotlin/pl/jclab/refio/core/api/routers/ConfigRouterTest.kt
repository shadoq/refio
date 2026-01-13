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
import pl.jclab.refio.core.llm.getAllModels
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
}
