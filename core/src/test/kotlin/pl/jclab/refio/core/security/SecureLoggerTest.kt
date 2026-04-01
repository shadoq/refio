package pl.jclab.refio.core.security

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class SecureLoggerTest {

    @Test
    fun `redacts OpenAI style keys`() {
        val input = "Authorization: Bearer sk-proj-abc123def456ghi7890"
        val output = SecureLogger.redact(input)

        assertFalse(output.contains("sk-proj-"))
        assertTrue(output.contains("[REDACTED]"))
    }

    @Test
    fun `redacts Anthropic and OpenRouter keys`() {
        val input = "keys: sk-ant-12345678901234567890 sk-or-abcdefghijklmnopqrstuv"
        val output = SecureLogger.redact(input)

        assertFalse(output.contains("sk-ant-"))
        assertFalse(output.contains("sk-or-"))
        assertTrue(output.contains("[REDACTED]"))
    }

    @Test
    fun `redacts Gemini API keys`() {
        val key = "AIza" + "a".repeat(35)
        val input = "x-goog-api-key=$key"
        val output = SecureLogger.redact(input)

        assertFalse(output.contains("AIza"))
        assertTrue(output.contains("[REDACTED]"))
    }

    @Test
    fun `redacts key value fields`() {
        val input = """{"apiKey":"secret123","model":"gpt-4"}"""
        val output = SecureLogger.redact(input)

        assertFalse(output.contains("secret123"))
        assertTrue(output.contains("model"))
    }

    @Test
    fun `redacts map values recursively`() {
        val input = mapOf(
            "config" to mapOf(
                "apiKey" to "sk-proj-secret",
                "nested" to mapOf("token" to "tok-123")
            )
        )

        val output = SecureLogger.redactMap(input)
        val config = output["config"] as Map<*, *>
        val nested = config["nested"] as Map<*, *>

        assertEquals("[REDACTED]", config["apiKey"])
        assertEquals("[REDACTED]", nested["token"])
    }
}
