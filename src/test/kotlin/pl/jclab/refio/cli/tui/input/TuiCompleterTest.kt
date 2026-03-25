package pl.jclab.refio.cli.tui.input

import org.jline.reader.Candidate
import org.jline.reader.impl.DefaultParser
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class TuiCompleterTest {

    private val completer = TuiCompleter()

    private fun complete(input: String): List<String> {
        val parser = DefaultParser()
        val parsedLine = parser.parse(input, input.length)
        val candidates = mutableListOf<Candidate>()
        completer.complete(org.jline.reader.impl.LineReaderImpl(
            org.jline.terminal.TerminalBuilder.builder().dumb(true).build()
        ), parsedLine, candidates)
        return candidates.map { it.value() }
    }

    @Test
    fun `should complete slash commands`() {
        val candidates = complete("/he")
        assertTrue(candidates.contains("/help"), "Should suggest /help for /he")
    }

    @Test
    fun `should complete all commands for slash prefix`() {
        val candidates = complete("/")
        assertTrue(candidates.size > 5, "Should suggest multiple commands")
        assertTrue(candidates.contains("/help"))
        assertTrue(candidates.contains("/quit"))
    }

    @Test
    fun `should complete at-mentions`() {
        val candidates = complete("@fi")
        assertTrue(candidates.contains("@file:"), "Should suggest @file: for @fi")
    }

    @Test
    fun `should complete all mentions for at prefix`() {
        val candidates = complete("@")
        assertTrue(candidates.size > 3, "Should suggest multiple mention types")
        assertTrue(candidates.contains("@codebase:"))
        assertTrue(candidates.contains("@git-diff"))
    }

    @Test
    fun `should return empty for regular text`() {
        val candidates = complete("hello")
        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `should complete clipboard mention`() {
        val candidates = complete("@clip")
        assertTrue(candidates.contains("@clipboard"))
    }
}
