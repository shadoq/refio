package pl.jclab.refio.ui.components.history

import pl.jclab.refio.api.models.Session
import pl.jclab.refio.api.models.TaskMode
import pl.jclab.refio.api.models.TaskStatus
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A history row must let a user judge a past session at a glance. The rules under test:
 * an abandoned turn is not reported as either success or failure, a session's duration comes
 * from its own timestamps, and the meta line stays on one line by shortening large numbers.
 */
class SessionRowTest {

    private fun session(
        status: TaskStatus = TaskStatus.SUCCESS,
        createdAt: Long = 1_000_000L,
        updatedAt: Long = 1_000_000L,
        tokensIn: Int = 0,
        tokensOut: Int = 0,
        costUsd: Double = 0.0
    ) = Session(
        id = "s1",
        name = "Refactor discount rules",
        mode = TaskMode.AGENT,
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt,
        tokensIn = tokensIn,
        tokensOut = tokensOut,
        costUsd = costUsd
    )

    @Test
    fun `an incomplete turn is neither success nor failure`() {
        assertEquals(SessionRow.Status.PARTIAL, SessionRow.statusOf(TaskStatus.INCOMPLETE))
        assertEquals(SessionRow.Status.OK, SessionRow.statusOf(TaskStatus.SUCCESS))
        assertEquals(SessionRow.Status.FAILED, SessionRow.statusOf(TaskStatus.FAILED))
    }

    @Test
    fun `a canceled session reads as failed because nothing was delivered`() {
        assertEquals(SessionRow.Status.FAILED, SessionRow.statusOf(TaskStatus.CANCELED))
    }

    @Test
    fun `duration comes from the session timestamps and never goes negative`() {
        val row = SessionRow.from(session(createdAt = 5_000L, updatedAt = 83_000L), generationMs = null)
        assertEquals(78_000L, row.durationMs)

        // Clock skew between writes must not render as a negative duration.
        val skewed = SessionRow.from(session(createdAt = 90_000L, updatedAt = 5_000L), generationMs = null)
        assertEquals(0L, skewed.durationMs)
    }

    /** Numbers keep the user's locale separator, so the expectation is built the same way. */
    private val dec = java.text.DecimalFormatSymbols.getInstance().decimalSeparator

    @Test
    fun `large token counts are shortened so the meta line fits a narrow dock`() {
        assertEquals("187${dec}1K", SessionRow.formatCount(187_100))
        assertEquals("1${dec}2M", SessionRow.formatCount(1_200_000))
        assertEquals("42", SessionRow.formatCount(42))
    }

    @Test
    fun `meta line omits token and cost segments when the session has neither`() {
        val row = SessionRow.from(session(createdAt = 0L, updatedAt = 12_000L), generationMs = null)

        assertTrue(row.metaText.contains("12s"))
        assertTrue(row.metaText.none { it == '↓' })
        assertTrue(!row.metaText.contains("$"))
    }

    @Test
    fun `meta line shows token direction and cost when present`() {
        val row = SessionRow.from(
            session(createdAt = 0L, updatedAt = 1_000L, tokensIn = 187_100, tokensOut = 1_100, costUsd = 0.0412),
            generationMs = null
        )

        assertTrue(row.metaText.contains("187${dec}1K↓"))
        assertTrue(row.metaText.contains("1${dec}1K↑"))
        assertTrue(row.metaText.contains("0${dec}0412"))
    }
}
