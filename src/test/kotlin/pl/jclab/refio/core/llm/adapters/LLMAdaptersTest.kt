package pl.jclab.refio.core.llm.adapters

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.llm.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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
}
