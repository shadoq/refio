package pl.jclab.refio.core.services.turn

import pl.jclab.refio.core.db.TaskMode

/**
 * Decides whether several subagents asked for in one reply may run at the same time.
 *
 * - Two subagents that can write files would edit the same project at once, so in AGENT mode a
 *   subagent with a write tool runs after the previous one finished. PLAN mode cannot write, so the
 *   definition's write tools do not matter there.
 * - An Ollama endpoint with one request slot (the default) serves one request at a time: parallel
 *   subagents only wait for each other while all their contexts sit in memory. They run together
 *   only when the endpoint has more than one slot (`providers.ollama_max_concurrent`, matching
 *   `OLLAMA_NUM_PARALLEL` on the server).
 */
object SubagentConcurrencyPolicy {

    fun canRunInParallel(
        definitionHasWriteTools: Boolean,
        mode: TaskMode,
        provider: String,
        ollamaMaxConcurrent: Int
    ): Boolean {
        if (definitionHasWriteTools && mode == TaskMode.AGENT) {
            return false
        }
        if (provider.equals("ollama", ignoreCase = true) && ollamaMaxConcurrent <= 1) {
            return false
        }
        return true
    }
}
