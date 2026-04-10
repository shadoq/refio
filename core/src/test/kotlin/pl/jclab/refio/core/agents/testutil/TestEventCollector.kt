package pl.jclab.refio.core.agents.testutil

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import pl.jclab.refio.core.agents.events.AgentEvent
import pl.jclab.refio.core.agents.events.AgentEventBus
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * EventBus assertion helper for tests.
 *
 * Collects events from SharedFlow into a list with await/filter/ordering assertions.
 * Create BEFORE emitting events (before runner.run()) to ensure collection starts.
 */
class TestEventCollector(
    eventBus: AgentEventBus,
    scope: CoroutineScope
) {
    @PublishedApi
    internal val _events = CopyOnWriteArrayList<AgentEvent>()
    val events: List<AgentEvent> get() = _events.toList()

    private val job: Job = scope.launch {
        eventBus.events.collect { _events.add(it) }
    }

    suspend fun awaitCount(n: Int, timeout: Duration = 5.seconds) {
        val deadline = System.currentTimeMillis() + timeout.inWholeMilliseconds
        while (_events.size < n) {
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError(
                    "Timed out waiting for $n events, got ${_events.size}: " +
                    _events.map { it::class.simpleName }
                )
            }
            delay(10)
        }
    }

    suspend inline fun <reified T : AgentEvent> awaitEvent(timeout: Duration = 5.seconds): T {
        val deadline = System.currentTimeMillis() + timeout.inWholeMilliseconds
        while (true) {
            _events.filterIsInstance<T>().firstOrNull()?.let { return it }
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError(
                    "Timed out waiting for ${T::class.simpleName}, got: " +
                    _events.map { it::class.simpleName }
                )
            }
            delay(10)
        }
    }

    /**
     * Assert that events of the given types appear in order (not necessarily consecutive).
     */
    fun assertEventOrder(vararg types: KClass<out AgentEvent>) {
        val actual = _events.map { it::class }
        var searchFrom = 0
        for (type in types) {
            val idx = actual.subList(searchFrom, actual.size).indexOfFirst { it == type }
            assertTrue(
                idx >= 0,
                "Expected ${type.simpleName} after position $searchFrom, " +
                "events: ${actual.map { it.simpleName }}"
            )
            searchFrom += idx + 1
        }
    }

    /**
     * Filter events by agent name prefix (agentId format: "name-uuid8").
     */
    fun eventsForAgent(namePrefix: String): List<AgentEvent> =
        _events.filter { it.sourceAgentId.startsWith(namePrefix) }

    fun eventsForSession(sessionId: String): List<AgentEvent> =
        _events.filter { it.sessionId == sessionId }

    inline fun <reified T : AgentEvent> eventsOfType(): List<T> =
        _events.filterIsInstance<T>()

    fun stop() {
        job.cancel()
    }
}
