package pl.jclab.refio.core.security

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class SecureLoggerTest {

    @Test
    fun `truncation does not pay for the whole payload`() {
        // A 100 KB request body ends up as 63 characters in the log. Running ~10 regexes over all
        // 100 KB to get there is the expensive half of every API-request log line. Narrowing the
        // input first is safe only with slack around the cut: a key straddling the boundary would
        // otherwise reach the output as an unrecognised - and unredacted - fragment.
        val key = "sk-ant-abcdefghijklmnopqrstuvwxyz012345"
        val input = "x".repeat(20) + key + "y".repeat(200_000)

        val output = SecureLogger.redactAndTruncate(input)

        assertFalse(output.contains("sk-ant"), "the key must not survive truncation in any form: $output")
        assertTrue(output.length < 200, "the result is a preview, not the payload: ${output.length}")
    }

    @Test
    fun `short input is redacted and returned whole`() {
        val input = "Authorization: Bearer sk-ant-abcdefghijklmnopqrstuvwxyz"

        val output = SecureLogger.redactAndTruncate(input, head = 200, tail = 200)

        assertEquals(SecureLogger.redact(input), output)
    }

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
