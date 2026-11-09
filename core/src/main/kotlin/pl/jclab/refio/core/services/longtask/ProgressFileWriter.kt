package pl.jclab.refio.core.services.longtask

import pl.jclab.refio.core.services.AgentPlanStep
import pl.jclab.refio.core.services.PlanStepStatus
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Writes `.refio/progress.md` in the project after every round of a long task: the goal, the rounds
 * so far and the open plan steps, readable by a person and by the agent itself.
 */
class ProgressFileWriter(private val projectRoot: Path, private val clock: () -> Long = System::currentTimeMillis) {

    /** Replaces the file in one move, so a reader never sees half of it. */
    fun write(state: LongTaskState, goal: String?, steps: List<AgentPlanStep>): Path {
        val file = projectRoot.resolve(".refio").resolve("progress.md")
        Files.createDirectories(file.parent)
        val tmp = Files.createTempFile(file.parent, "progress", ".tmp")
        try {
            Files.writeString(tmp, render(state, goal, steps))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
        return file
    }

    private fun render(state: LongTaskState, goal: String?, steps: List<AgentPlanStep>): String = buildString {
        val spec = state.spec
        appendLine("# Long task progress")
        appendLine()
        appendLine("Goal: ${(goal ?: spec.goal)?.trim()?.take(GOAL_CHARS) ?: "(not set)"}")
        spec.untilCommand?.let { appendLine("Runs until this command succeeds: `$it`") }
        appendLine(
            "Status: ${state.status.name.lowercase()}, round ${state.roundsDone} of ${spec.maxRounds} done, " +
                "${minutes(state.activeMs)} of ${spec.maxMinutes} minutes used"
        )
        state.stopReason?.let { appendLine("Stopped because: $it") }
        if (steps.isNotEmpty()) {
            appendLine("Plan: ${steps.count { it.status == PlanStepStatus.COMPLETED }} of ${steps.size} steps done")
        }
        appendLine("Updated: ${TIME.format(Instant.ofEpochMilli(clock()))}")
        appendLine()
        appendLine("## Rounds")
        if (state.rounds.isEmpty()) {
            appendLine("- none yet")
        }
        state.rounds.forEach { round ->
            appendLine(
                "- Round ${round.round}: ${round.iterations} iterations, ${minutes(round.durationMs)} min, " +
                    "${round.filesWritten} files written, ${check(round)}" + if (round.progress) "" else ", no progress"
            )
        }
        appendLine()
        appendLine("## Open steps")
        val open = steps.filter { it.status in OPEN }
        when {
            steps.isEmpty() -> appendLine("- no plan yet")
            open.isEmpty() -> appendLine("- none, all ${steps.size} steps done")
            // The first open step is where the next round starts; its description carries the
            // command that checks it.
            else -> open.forEachIndexed { position, step ->
                appendLine("- ${step.status.icon} ${step.index + 1}. ${step.title}" + if (position == 0) " - next" else "")
                step.description?.takeIf { it.isNotBlank() }?.let { appendLine("  ${it.trim()}") }
            }
        }
    }

    private fun check(round: RoundRecord): String = when (round.checkPassed) {
        true -> "check passed"
        false -> "check failed (exit ${round.exitCode ?: "?"}" + (round.errorLines?.let { ", $it error lines" } ?: "") + ")"
        null -> "not checked"
    }

    private fun minutes(ms: Long) = ms / 60_000

    private companion object {
        const val GOAL_CHARS = 1_000
        val OPEN = setOf(PlanStepStatus.PENDING, PlanStepStatus.IN_PROGRESS, PlanStepStatus.FAILED)
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    }
}
