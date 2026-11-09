package pl.jclab.refio.core.services.turn

import java.util.concurrent.ConcurrentHashMap

/**
 * The last verdict of the end-of-turn judge on a task's goal (`/goal`), kept until a long task reads
 * it after the round. Only a judge that actually compared the transcript with the goal records one;
 * a turn that ended without that comparison leaves nothing, so the goal is not taken as met.
 */
object GoalVerdicts {
    private val verdicts = ConcurrentHashMap<String, Boolean>()
    private val awaited: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun record(taskId: String, met: Boolean) {
        verdicts[taskId] = met
    }

    /**
     * A long task will read the verdict on [taskId]'s next turn. Until it does, even a turn that
     * ends on a question gets judged: nobody answers that question before the round's check.
     */
    fun await(taskId: String) {
        verdicts.remove(taskId)
        awaited.add(taskId)
    }

    fun isAwaited(taskId: String): Boolean = taskId in awaited

    /** The verdict recorded since the last [take] or [clear], or null when none was. Ends the wait. */
    fun take(taskId: String): Boolean? {
        awaited.remove(taskId)
        return verdicts.remove(taskId)
    }

    fun clear(taskId: String) {
        verdicts.remove(taskId)
        awaited.remove(taskId)
    }
}
