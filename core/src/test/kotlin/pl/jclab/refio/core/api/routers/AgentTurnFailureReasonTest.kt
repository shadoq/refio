package pl.jclab.refio.core.api.routers

import org.junit.jupiter.api.Test
import pl.jclab.refio.core.debug.VerificationSummary
import pl.jclab.refio.core.services.TurnResult
import kotlin.test.assertTrue

/**
 * A multi-agent run that fails must say why. The router used to copy only `success` out of the
 * turn and drop every signal explaining it, so a failed agent reached run.json with a null error.
 */
class AgentTurnFailureReasonTest {

    private fun turn(
        success: Boolean = false,
        iterations: Int = 7,
        incomplete: Boolean = false,
        rejectedByUser: Boolean = false,
        rejectedToolName: String? = null,
        rejectionReason: String? = null,
        verification: VerificationSummary? = null,
    ) = TurnResult(
        success = success,
        response = "",
        iterations = iterations,
        tokensIn = 1000,
        tokensOut = 10,
        cost = 0.0,
        incomplete = incomplete,
        rejectedByUser = rejectedByUser,
        rejectedToolName = rejectedToolName,
        rejectionReason = rejectionReason,
        verification = verification,
    )

    @Test
    fun `a rejected tool is named together with the reason it was rejected`() {
        val reason = describeTurnFailure(
            turn(rejectedByUser = true, rejectedToolName = "run_terminal_command", rejectionReason = "not on the allow list")
        )

        assertTrue(reason.contains("run_terminal_command"), "reason must name the tool: $reason")
        assertTrue(reason.contains("not on the allow list"), "reason must carry the rejection text: $reason")
    }

    @Test
    fun `a failed verification is reported as such, not as a generic failure`() {
        val reason = describeTurnFailure(
            turn(verification = VerificationSummary(ran = true, attempts = 2, result = VerificationSummary.RESULT_FAILED))
        )

        assertTrue(reason.contains("verification"), "reason must point at verification: $reason")
    }

    @Test
    fun `an undelivered turn is distinguished from an errored one`() {
        val reason = describeTurnFailure(turn(incomplete = true, iterations = 12))

        assertTrue(reason.contains("without delivering"), "reason must say the request was not delivered: $reason")
        assertTrue(reason.contains("12"), "reason must carry the iteration count: $reason")
    }

    @Test
    fun `a plain failure still reports how far the turn got`() {
        val reason = describeTurnFailure(turn(iterations = 3))

        assertTrue(reason.isNotBlank(), "a failed turn must never yield an empty reason")
        assertTrue(reason.contains("3"), "reason must carry the iteration count: $reason")
    }
}
