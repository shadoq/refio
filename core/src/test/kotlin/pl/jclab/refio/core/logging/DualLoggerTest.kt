package pl.jclab.refio.core.logging

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Locks the cost of a log line nobody will read.
 *
 * WHY this matters: these lambdas are not cheap. The per-token streaming trace formats a chunk on
 * every token, and the API-request debug hands a 100 KB prompt to a chain of ~10 redaction regexes -
 * all of it discarded when the level is off. Building the message before asking whether anyone wants
 * it turns logging into a measurable share of a turn.
 */
class DualLoggerTest {

    private class RecordingSink : LogSink {
        val debugMessages = mutableListOf<String>()
        override fun debug(component: String, message: String) {
            debugMessages.add(message)
        }
        override fun info(component: String, message: String) = Unit
        override fun warn(component: String, message: String) = Unit
        override fun error(component: String, message: String, throwable: Throwable?) = Unit
    }

    @BeforeEach
    @AfterEach
    fun clearSink() {
        LogSinkRegistry.clear()
    }

    @Test
    fun `a disabled trace never builds its message`() {
        // Tests run at WARN (logback-test.xml), so TRACE is off here exactly as it is in production.
        var evaluated = 0
        val logger = dualLogger("DualLoggerTest")

        logger.trace { evaluated++; "expensive message" }

        assertEquals(0, evaluated, "a disabled trace must not evaluate its message lambda")
    }

    @Test
    fun `a disabled debug with no UI sink never builds its message`() {
        var evaluated = 0
        val logger = dualLogger("DualLoggerTest")

        logger.debug { evaluated++; "expensive message" }

        assertEquals(0, evaluated, "a disabled debug with nobody listening must not evaluate its lambda")
    }

    @Test
    fun `debug still reaches a registered UI sink while the log level is off`() {
        // The in-app Debug Logs view is fed from here, not from logback - guarding on the logback
        // level alone would silently empty that panel.
        val sink = RecordingSink()
        LogSinkRegistry.register(sink)
        val logger = dualLogger("DualLoggerTest")

        logger.debug { "shown in the UI panel" }

        assertEquals(listOf("shown in the UI panel"), sink.debugMessages)
    }
}
