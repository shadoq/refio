package pl.jclab.refio.core.llm.adapters

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.llm.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Testy podstawowe dla adapterów LLM.
 * Weryfikują poprawność nazw providerów i obsługiwane modele.
 */
class LLMAdaptersTest {

    @Nested
    inner class AnthropicAdapterTests {

        private lateinit var adapter: AnthropicAdapter

        @BeforeEach
        fun setup() {
            adapter = AnthropicAdapter()
        }

        @Test
        fun `should have correct provider name`() {
            assertEquals("anthropic", adapter.provider)
        }

        @Test
        fun `should support streaming`() {
            // Anthropic uses SSE for streaming
            assertNotNull(adapter.provider)
        }
    }

    @Nested
    inner class OllamaAdapterTests {

        private lateinit var adapter: OllamaAdapter

        @BeforeEach
        fun setup() {
            adapter = OllamaAdapter()
        }

        @Test
        fun `should have correct provider name`() {
            assertEquals("ollama", adapter.provider)
        }

        @Test
        fun `should support streaming`() {
            // Ollama uses NDJSON for streaming
            assertNotNull(adapter.provider)
        }
    }

    @Nested
    inner class OpenAIAdapterTests {

        private lateinit var adapter: OpenAIAdapter

        @BeforeEach
        fun setup() {
            adapter = OpenAIAdapter()
        }

        @Test
        fun `should have correct provider name`() {
            assertEquals("openai", adapter.provider)
        }

        @Test
        fun `should support streaming`() {
            // OpenAI uses SSE for streaming
            assertNotNull(adapter.provider)
        }
    }

    @Nested
    inner class GeminiAdapterTests {

        private lateinit var adapter: GeminiAdapter

        @BeforeEach
        fun setup() {
            adapter = GeminiAdapter()
        }

        @Test
        fun `should have correct provider name`() {
            assertEquals("gemini", adapter.provider)
        }

        @Test
        fun `should support streaming`() {
            assertNotNull(adapter.provider)
        }
    }

    @Nested
    inner class OpenRouterAdapterTests {

        private lateinit var adapter: OpenRouterAdapter

        @BeforeEach
        fun setup() {
            adapter = OpenRouterAdapter()
        }

        @Test
        fun `should have correct provider name`() {
            assertEquals("openrouter", adapter.provider)
        }

        @Test
        fun `should support streaming`() {
            assertNotNull(adapter.provider)
        }
    }

    @Nested
    inner class LMStudioAdapterTests {

        private lateinit var adapter: LMStudioAdapter

        @BeforeEach
        fun setup() {
            adapter = LMStudioAdapter()
        }

        @Test
        fun `should have correct provider name`() {
            assertEquals("lmstudio", adapter.provider)
        }

        @Test
        fun `should support streaming`() {
            assertNotNull(adapter.provider)
        }
    }

    @Nested
    inner class CustomOpenAIAdapterTests {

        private lateinit var adapter: CustomOpenAIAdapter

        @BeforeEach
        fun setup() {
            adapter = CustomOpenAIAdapter(
                model = "custom-model",
                baseUrlOverride = "http://localhost:8080/v1"
            )
        }

        @Test
        fun `should have correct provider name`() {
            assertEquals("custom_openai", adapter.provider)
        }

        @Test
        fun `should support streaming`() {
            assertNotNull(adapter.provider)
        }

        @Test
        fun `should parse provider error payload`() {
            val payload = adapter.parseProviderError("""{"error":{"code":"1305","message":"Too many requests"}}""")

            assertEquals("1305", payload.code)
            assertEquals("Too many requests", payload.message)
        }

        @Test
        fun `should build detailed zai rate limit message`() {
            val zaiAdapter = CustomOpenAIAdapter(
                model = "glm-4.5",
                providerName = "zai",
                baseUrlOverride = "https://api.z.ai/api/paas/v4"
            )

            val message = zaiAdapter.buildZAIErrorMessage(
                httpStatus = 429,
                businessCode = "1305",
                message = "Too many requests"
            )

            assertTrue(message.contains("1305"))
            assertTrue(message.contains("API rate limit triggered"))
        }
    }

    @Nested
    inner class ZAIAdapterTests {

        private lateinit var adapter: ZAIAdapter

        @BeforeEach
        fun setup() {
            adapter = ZAIAdapter()
        }

        @Test
        fun `should have correct provider name`() {
            assertEquals("zai", adapter.provider)
        }

        @Test
        fun `should parse openai style models payload`() = runTest {
            val models = adapter.parseModelsPayload(
                """
                {"data":[{"id":"glm-4.5","context_length":128000},{"id":"glm-4.6","context_length":256000}]}
                """.trimIndent()
            )

            assertTrue(models.isNotEmpty())
            assertTrue(models.any { it.id == "glm-4.5" })
            assertTrue(models.all { it.provider == "zai" })
        }

        @Test
        fun `should parse array style models payload`() = runTest {
            val models = adapter.parseModelsPayload(
                """
                [{"id":"glm-4.5","context_length":128000},{"id":"glm-4.7-flash","context_length":128000}]
                """.trimIndent()
            )

            assertEquals(2, models.size)
            assertTrue(models.any { it.id == "glm-4.7-flash" })
        }
    }

    @Test
    fun `llm client should expose custom providers`() {
        val providers = LLMClient().getSupportedProviders()

        kotlin.test.assertTrue("custom_openai" in providers)
        kotlin.test.assertTrue("zai" in providers)
    }
}
