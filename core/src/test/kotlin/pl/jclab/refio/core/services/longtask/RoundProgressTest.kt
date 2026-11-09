package pl.jclab.refio.core.services.longtask

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A loop gives up after a few rounds that moved nothing, so "moved" must catch real work: a file
 * changed, a plan step finished, fewer errors in the check. Anything else is a round spent in place.
 */
class RoundProgressTest {

    private fun round(files: Int = 0, stepsDone: Int = 0, errorLines: Int? = 5) = RoundRecord(
        round = 2, durationMs = 1_000, iterations = 10, turnStopReason = "COMPLETED", checkPassed = false,
        exitCode = 1, errorLines = errorLines, filesWritten = files, planStepsDone = stepsDone, progress = false,
    )

    @Test
    fun `a changed file is progress`() {
        assertTrue(RoundProgress.madeProgress(round(), round(files = 1)))
    }

    @Test
    fun `a finished plan step or fewer errors is progress`() {
        assertTrue(RoundProgress.madeProgress(round(stepsDone = 1), round(stepsDone = 2)))
        assertTrue(RoundProgress.madeProgress(round(errorLines = 5), round(errorLines = 3)))
    }

    @Test
    fun `same errors, no files and no finished step is no progress`() {
        assertFalse(RoundProgress.madeProgress(round(stepsDone = 2, errorLines = 5), round(stepsDone = 2, errorLines = 5)))
        assertFalse(RoundProgress.madeProgress(round(errorLines = null), round(errorLines = null)))
    }
}
