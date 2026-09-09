package pl.jclab.refio.ui.context

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import pl.jclab.refio.core.services.turn.PromptSnapshot
import pl.jclab.refio.services.session.SessionManager
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Tests for [ContextInspectorViewModel] - the canonical StateFlow view-logic in the plugin. It must
 * republish the session's prompt snapshots into its own flow, and must no-op (not crash) when the
 * session exposes no snapshot flow yet.
 *
 * The mocks are built OUTSIDE runTest: MockK's first call in a JVM costs a one-off ~3s of framework
 * instrumentation, and this class holds no mocks anywhere else, so it may well be the call that pays
 * it. Inside runTest that cost lands on the test coroutine's 10s real-time watchdog and surfaces, on
 * a loaded machine, as a bogus "the test coroutine is not completing".
 */
class ContextInspectorViewModelTest {

    @Test
    fun `start republishes snapshots from the session manager`() {
        val snap = mockk<PromptSnapshot>(relaxed = true)
        val sessionManager = mockk<SessionManager> { every { lastPromptSnapshot } returns MutableStateFlow(snap) }

        runTest {
            val viewModel = ContextInspectorViewModel(sessionManager, backgroundScope)
            viewModel.start()
            runCurrent() // let the launched collector deliver the source's current value

            assertEquals(snap, viewModel.snapshot.value)
        }
    }

    @Test
    fun `start is a no-op when the session exposes no snapshot flow`() {
        val sessionManager = mockk<SessionManager> { every { lastPromptSnapshot } returns null }

        runTest {
            val viewModel = ContextInspectorViewModel(sessionManager, backgroundScope)
            viewModel.start()
            runCurrent()

            assertEquals(null, viewModel.snapshot.value)
        }
    }
}
