package pl.jclab.refio.core.errors

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The server-side tool-template failure has to be told apart from an ordinary 500: an ordinary one
 * is worth retrying, this one reproduces itself on every attempt, so retrying it only spends the
 * backoff before the turn falls back to the text contract anyway.
 */
class NativeToolTemplateErrorTest {

    @Test
    fun `recognises the template parse failure Ollama returns as a 500`() {
        val real = RuntimeException(
            """Ollama API error (HTTP 500): {"error":"XML syntax error on line 5: element <function> closed by </parameter>"}"""
        )

        assertTrue(NativeToolTemplateError.matches(real))
    }

    @Test
    fun `finds it when it is wrapped by the adapter`() {
        val wrapped = RuntimeException(
            "LLM call failed",
            IllegalStateException("upstream", RuntimeException("xml syntax error on line 5")),
        )

        assertTrue(NativeToolTemplateError.matches(wrapped))
    }

    @Test
    fun `an ordinary server error stays retryable`() {
        assertFalse(NativeToolTemplateError.matches(RuntimeException("Ollama API error (HTTP 500): internal error")))
        assertFalse(NativeToolTemplateError.matches(RuntimeException("HTTP 503 service unavailable")))
        assertFalse(NativeToolTemplateError.matches(null))
    }

    @Test
    fun `a self-referencing cause chain terminates`() {
        val a = RuntimeException("first")
        val b = RuntimeException("second", a)
        a.initCause(b)

        assertFalse(NativeToolTemplateError.matches(b))
    }
}
