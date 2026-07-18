package pl.jclab.refio.services.logging

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PluginLogger backs the in-IDE "Logs" panel. The business rules under test: the buffer is
 * bounded (an unbounded log would grow for the life of the IDE process), errors carry their
 * stack trace so a report is actionable, and clearing empties the view.
 */
class PluginLoggerTest {

    @Test
    fun `buffer is capped so a chatty session cannot grow memory unbounded`() {
        val logger = PluginLogger()
        repeat(1100) { logger.info("Test", "message $it") }

        val entries = logger.logEntries.value
        assertEquals(1000, entries.size)
        // The oldest entries are dropped, the newest kept.
        assertEquals("message 1099", entries.last().message)
        assertEquals("message 100", entries.first().message)
    }

    @Test
    fun `error with throwable embeds the stack trace for actionable reports`() {
        val logger = PluginLogger()
        logger.error("Core", "boom happened", IllegalStateException("root cause"))

        val entry = logger.logEntries.value.single()
        assertEquals(LogLevel.ERROR, entry.level)
        assertTrue(entry.message.contains("boom happened"))
        assertTrue(entry.message.contains("IllegalStateException"))
        assertTrue(entry.message.contains("root cause"))
    }

    @Test
    fun `clear empties the panel`() {
        val logger = PluginLogger()
        logger.info("Test", "one")
        logger.clear()
        assertTrue(logger.logEntries.value.isEmpty())
    }

    @Test
    fun `api call success and failure map to distinct levels`() {
        val logger = PluginLogger()
        logger.apiCall("ollama", "qwen", 10, 20, 0.0, 100)
        logger.apiCall("ollama", "qwen", 10, 0, 0.0, 100, error = "timeout")

        val entries = logger.logEntries.value
        assertEquals(LogLevel.HTTP, entries[0].level)
        assertEquals(LogLevel.ERROR, entries[1].level)
        assertTrue(entries[1].message.contains("timeout"))
    }

    @Test
    fun `http response body is truncated to keep single entries bounded`() {
        val logger = PluginLogger()
        logger.logResponse("GET", "http://x", 200, body = "a".repeat(600))

        val entry = logger.logEntries.value.single()
        assertTrue(entry.message.contains("a".repeat(500) + "..."))
        assertTrue(!entry.message.contains("a".repeat(501)))
    }
}
