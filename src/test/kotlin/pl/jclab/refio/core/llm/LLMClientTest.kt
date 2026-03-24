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
        llmClient = LLMClient(configService)
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
