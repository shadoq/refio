package pl.jclab.refio.cli.tui.golden

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import pl.jclab.refio.cli.tui.rendering.TuiRenderBuffer
import pl.jclab.refio.cli.tui.state.TuiTab
import pl.jclab.refio.cli.tui.state.TuiToolApprovalRequest
import pl.jclab.refio.core.services.turn.ProposedChange
import java.util.TimeZone
import kotlin.test.assertTrue

/** The main screen at the three reference sizes: sidebar only when wide, right column from medium up and only while a panel or an approval is open. */
class MainScreenGoldenTest {

    companion object {
        private val originalZone = TimeZone.getDefault()

        @JvmStatic
        @BeforeAll
        fun utc() = TimeZone.setDefault(TimeZone.getTimeZone("UTC"))

        @JvmStatic
        @AfterAll
        fun restore() = TimeZone.setDefault(originalZone)
    }

    private val renderer = TuiFixtures.renderer()

    @ParameterizedTest(name = "{0}x{1}")
    @CsvSource("200, 50", "140, 42", "80, 24")
    fun `chat screen at reference sizes`(width: Int, height: Int) {
        val rows = renderer.composeFrame(TuiFixtures.chatState(), width, height).snapshot()
        GoldenScreen.assertRowWidths(width, rows)
        GoldenScreen.assertText("main-${width}x$height", rows)
        GoldenScreen.assertStyles("main-${width}x$height", rows)
    }

    @Test
    fun `open panel sits in the right column beside the chat`() {
        val rows = renderer.composeFrame(TuiFixtures.chatState().copy(activeTab = TuiTab.STEPS), 140, 42).snapshot()
        GoldenScreen.assertRowWidths(140, rows)
        GoldenScreen.assertText("main-140x42-steps", rows)
    }

    @Test
    fun `open panel fills the narrow screen`() {
        val rows = renderer.composeFrame(TuiFixtures.chatState().copy(activeTab = TuiTab.STEPS), 80, 24).snapshot()
        GoldenScreen.assertRowWidths(80, rows)
        GoldenScreen.assertText("main-80x24-steps", rows)
    }

    @Test
    fun `approval waits at the end of the chat with the diff, the choices and this turn's calls`() {
        val path = "cli/src/main/kotlin/TuiViewModel.kt"
        // The core's diff format: a sign and a space before every line.
        val diff = "@@ -10,5 +10,5 @@\n  context a\n  context b\n-     val max = 128_000\n+     val max = window.tokens\n  context c\n"
        val approval = TuiToolApprovalRequest("r1", "advance_code_editing", "Edit file", mapOf("path" to path), ProposedChange(path, "x", "y", diff))
        val rows = renderer.composeFrame(TuiFixtures.chatState().copy(pendingToolApproval = approval), 140, 42).snapshot()
        GoldenScreen.assertRowWidths(140, rows)
        GoldenScreen.assertText("main-140x42-approval", rows)
    }

    @Test
    fun `resize during streaming never throws and every frame fills the terminal`() {
        val streaming = TuiFixtures.chatState().let { s ->
            s.copy(isStreaming = true, messages = s.messages + s.messages.last().copy(id = "m4", content = "Partial answer", isStreaming = true))
        }
        for ((w, h) in listOf(200 to 50, 79 to 30, 140 to 42, 80 to 24, 60 to 20, 120 to 24, 180 to 60)) {
            val rows = renderer.composeFrame(streaming, w, h).snapshot()
            assertTrue(rows.size == h, "$w x $h gave ${rows.size} rows")
            if (w >= 80 && h >= 24) GoldenScreen.assertRowWidths(w, rows)
        }
    }

    @Test
    fun `terminal below the minimum shows only the size message`() {
        val rows = renderer.composeFrame(TuiFixtures.chatState(), 60, 20).snapshot().map(TuiRenderBuffer::stripAnsi)
        assertTrue(rows.any { it.contains("Refio needs at least 80×24 (now 60×20)") }, rows.joinToString("\n"))
    }
}
