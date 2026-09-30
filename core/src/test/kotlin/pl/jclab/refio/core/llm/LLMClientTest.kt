package pl.jclab.refio.core.llm

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.errors.RefioError
import pl.jclab.refio.core.services.ConfigService
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LLMClientTest {

    private lateinit var configService: ConfigService
    private lateinit var llmClient: LLMClient

    @BeforeEach
    fun setup() {
        configService = mockk(relaxed = true)
        // The context pre-flight check resolves the window through ModelWindow, and a relaxed
        // mock cannot produce an Int for the generic getTyped. Give it the real default.
        every {
            configService.getTyped(pl.jclab.refio.core.config.ConfigKeys.MAX_CONTEXT_SIZE, any<String>())
        } returns pl.jclab.refio.core.config.ConfigKeys.MAX_CONTEXT_SIZE.default
        llmClient = LLMClient(configService)
    }

    @Nested
    inner class StreamedCost {

        @Test
        fun `a streamed call is billed at the cost the provider reported, not a local price estimate`() {
            // kimi-k3 on OpenRouter: the local literal ($0.60/$2.50) is ~5x below the real price,
            // which undercounted a benchmark's Kimi spend at $1.46 against $4.04 on the invoice.
            val usage = LLMUsage(inputTokens = 16_376, outputTokens = 203, totalTokens = 16_579, upstreamCostUsd = 0.0397341)

            assertEquals(0.0397341, llmClient.estimateCost(usage, "openrouter", "moonshotai/kimi-k3"), 1e-9)
        }

        @Test
        fun `without a reported cost the streamed call falls back to the local price table`() {
            val usage = LLMUsage(inputTokens = 1_000_000, outputTokens = 0, totalTokens = 1_000_000)

            assertEquals(0.20, llmClient.estimateCost(usage, "openai", "gpt-5.4-nano"), 1e-9)
        }
    }

    @Nested
    inner class NoEgressEnforcement {

        @Test
        fun `should block openai when noEgress is enabled`() = runTest {
            assertFailsWith<NoEgressViolationException> {
                llmClient.complete(
                    provider = "openai",
                    model = "gpt-4o",
                    messages = listOf(LLMMessage("user", "test")),
                    noEgressEnabled = true,
                    taskId = "task-1"
                )
            }
        }

        @Test
        fun `should block anthropic when noEgress is enabled`() = runTest {
            assertFailsWith<NoEgressViolationException> {
                llmClient.complete(
                    provider = "anthropic",
                    model = "claude-3",
                    messages = listOf(LLMMessage("user", "test")),
                    noEgressEnabled = true,
                    taskId = "task-1"
                )
            }
        }

        @Test
        fun `should block openrouter when noEgress is enabled`() = runTest {
            assertFailsWith<NoEgressViolationException> {
                llmClient.complete(
                    provider = "openrouter",
                    model = "some-model",
                    messages = listOf(LLMMessage("user", "test")),
                    noEgressEnabled = true,
                    taskId = "task-1"
                )
            }
        }

        @Test
        fun `should block gemini when noEgress is enabled`() = runTest {
            assertFailsWith<NoEgressViolationException> {
                llmClient.complete(
                    provider = "gemini",
                    model = "gemini-pro",
                    messages = listOf(LLMMessage("user", "test")),
                    noEgressEnabled = true,
                    taskId = "task-1"
                )
            }
        }

        @Test
        fun `should block zai when noEgress is enabled`() = runTest {
            assertFailsWith<NoEgressViolationException> {
                llmClient.complete(
                    provider = "zai",
                    model = "glm-model",
                    messages = listOf(LLMMessage("user", "test")),
                    noEgressEnabled = true,
                    taskId = "task-1"
                )
            }
        }

        @Test
        fun `should not block local provider with noEgress`() = runTest {
            // Ollama is local - should NOT throw NoEgressViolationException
            // It will throw a different error (connection refused or adapter error), not NoEgress
            try {
                llmClient.complete(
                    provider = "ollama",
                    model = "llama2",
                    messages = listOf(LLMMessage("user", "test")),
                    noEgressEnabled = true,
                    taskId = "task-1"
                )
            } catch (e: NoEgressViolationException) {
                throw AssertionError("Ollama should not be blocked by no-egress mode", e)
            } catch (_: Exception) {
                // Expected — adapter will fail because no Ollama server is running
            }
        }
    }

    @Nested
    inner class AdapterSelection {

        @Test
        fun `should throw on unknown provider`() = runTest {
            assertFailsWith<RefioError.ProviderNotConfigured> {
                llmClient.complete(
                    provider = "unknown_provider",
                    model = "model",
                    messages = listOf(LLMMessage("user", "test")),
                    taskId = "task-1"
                )
            }
        }

        @Test
        fun `should be case insensitive for provider selection`() = runTest {
            // OpenAI with uppercase — should NOT throw ProviderNotConfigured
            try {
                llmClient.complete(
                    provider = "OpenAI",
                    model = "gpt-4o",
                    messages = listOf(LLMMessage("user", "test")),
                    taskId = "task-1"
                )
            } catch (e: RefioError.ProviderNotConfigured) {
                throw AssertionError("Provider selection should be case-insensitive", e)
            } catch (_: Exception) {
                // Expected — adapter will fail because no API key is configured
            }
        }
    }

    @Nested
    inner class PrepareRequestPayload {

        @Test
        fun `should combine system messages and system prompt`() {
            val payload = LLMClient.prepareRequestPayload(
                messages = listOf(LLMMessage("user", "hello")),
                systemPrompt = "You are helpful",
                systemMessages = listOf("Context info")
            )

            assertEquals(2, payload.systemMessages.size)
            assertTrue(payload.systemMessages.contains("Context info"))
            assertTrue(payload.systemMessages.contains("You are helpful"))
        }

        @Test
        fun `should filter blank system messages`() {
            val payload = LLMClient.prepareRequestPayload(
                messages = listOf(LLMMessage("user", "hello")),
                systemMessages = listOf("Valid", "", "  ", "Also valid")
            )

            assertEquals(2, payload.systemMessages.size)
        }

        @Test
        fun `should inject context before last user message`() {
            val messages = listOf(
                LLMMessage("user", "first"),
                LLMMessage("assistant", "reply"),
                LLMMessage("user", "second")
            )
            val payload = LLMClient.prepareRequestPayload(
                messages = messages,
                contextContent = "project context"
            )

            // Context injected before last user message
            assertEquals(4, payload.messages.size)
            assertEquals("project context", payload.messages[2].content)
            assertEquals("second", payload.messages[3].content)
        }

        @Test
        fun `should append context as user message when no user messages exist`() {
            val messages = listOf(LLMMessage("system", "sys"))
            val payload = LLMClient.prepareRequestPayload(
                messages = messages,
                contextContent = "context"
            )

            assertEquals(2, payload.messages.size)
            assertEquals("context", payload.messages.last().content)
        }

        @Test
        fun `should not modify messages when no context`() {
            val messages = listOf(LLMMessage("user", "hello"))
            val payload = LLMClient.prepareRequestPayload(
                messages = messages,
                contextContent = null
            )

            assertEquals(1, payload.messages.size)
        }

        @Test
        fun `should estimate tokens`() {
            val payload = LLMClient.prepareRequestPayload(
                messages = listOf(LLMMessage("user", "hello world")),
                systemPrompt = "You are a helper"
            )

            assertTrue(payload.estimatedInputTokens > 0)
        }
    }
}
