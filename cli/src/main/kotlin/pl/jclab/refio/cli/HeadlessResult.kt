package pl.jclab.refio.cli

import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.db.TaskStatus

/**
 * Exit-code contract for headless / multi-agent runs.
 *
 * The process exits 0 only when the work actually succeeded, so a CI step gating on the
 * process exit code sees a non-delivering or failed run as a failure. A failed, incomplete
 * or errored run returns a non-zero code.
 */
internal object HeadlessExit {
    const val SUCCESS = 0
    const val FAILURE = 1

    /** SUCCESS only for a delivered turn; FAILED / INCOMPLETE / anything else is non-zero. */
    fun forStatus(status: TaskStatus): Int =
        if (status == TaskStatus.SUCCESS) SUCCESS else FAILURE
}

/**
 * Fold the `--no-egress` switch into the run-scope config overrides used by the headless and
 * multi-agent paths.
 *
 * no-egress is enforced by reading `general.no_egress_enabled` (the egress gate plus the LLM
 * callers), so a headless run must surface the flag through that same config key. The flag is
 * otherwise dropped on the headless path (it only reaches the interactive TUI). When the flag
 * is absent the overrides are returned untouched, so the configured / UI default still applies.
 */
internal fun withNoEgress(base: Map<String, String>, noEgress: Boolean): Map<String, String> =
    if (noEgress) base + (ConfigKeys.GENERAL_NO_EGRESS_ENABLED.key to "true") else base
