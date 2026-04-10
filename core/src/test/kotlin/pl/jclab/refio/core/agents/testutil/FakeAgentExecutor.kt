package pl.jclab.refio.core.agents.testutil

import kotlinx.coroutines.delay
import pl.jclab.refio.core.agents.AgentResult
import pl.jclab.refio.core.agents.AgentSpec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Configurable fake executor for MultiAgentRunner tests.
 *
 * Per-agent config: delay, success/failure, token count, cost, exception, hang.
 * Logs execution order for ordering assertions.
 */
class FakeAgentExecutor(
    private val defaultConfig: AgentConfig = AgentConfig()
) {
    data class AgentConfig(
        val delayMs: Long = 50,
        val success: Boolean = true,
        val response: String = "done",
        val tokensUsed: Long = 100,
        val costUsd: Double = 0.01,
        val throwException: Exception? = null,
        val hang: Boolean = false
    )

    data class ExecutionRecord(
        val agentName: String,
        val agentId: String,
        val startTimestamp: Long,
        val endTimestamp: Long,
        val startOrder: Int,
        val endOrder: Int
    )

    private val configs = ConcurrentHashMap<String, AgentConfig>()
    private val orderCounter = AtomicInteger(0)
    val executionLog = CopyOnWriteArrayList<ExecutionRecord>()

    fun configure(agentName: String, config: AgentConfig) {
        configs[agentName] = config
    }

    fun reset() {
        configs.clear()
        executionLog.clear()
        orderCounter.set(0)
    }

    val executor: suspend (AgentSpec, String) -> AgentResult = { spec, agentId ->
        val config = configs[spec.name] ?: defaultConfig
        val startTime = System.currentTimeMillis()
        val startOrder = orderCounter.getAndIncrement()

        if (config.hang) {
            delay(Long.MAX_VALUE)
        }

        config.throwException?.let { throw it }

        if (config.delayMs > 0) {
            delay(config.delayMs)
        }

        val endTime = System.currentTimeMillis()
        val endOrder = orderCounter.getAndIncrement()
        executionLog.add(ExecutionRecord(spec.name, agentId, startTime, endTime, startOrder, endOrder))

        AgentResult(
            agentName = spec.name,
            success = config.success,
            response = config.response,
            tokensUsed = config.tokensUsed,
            costUsd = config.costUsd,
            durationMs = endTime - startTime,
            error = if (!config.success) "Failed" else null
        )
    }
}
