package pl.jclab.refio.core.services.maintenance

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A long task holds its task's lease through all its rounds, and each round's turn holds it again.
 * The check between two rounds runs after the turn let go, and a process that dies during that
 * check must still leave the lease behind, or nobody would ever repair the task.
 */
class TaskLeaseTest {

    private val events = mutableListOf<String>()

    private val store = object : LeaseStore {
        override fun claimLease(taskId: String, pid: Long, startedAt: Long) {
            events += "claim $taskId by $pid"
        }

        override fun releaseLease(taskId: String) {
            events += "release $taskId"
        }
    }

    @Test
    fun `a nested hold keeps the lease until the outer one ends`() = runTest {
        val lease = TaskLease(store, ProcessOwner(pid = 7, startedAt = 1))

        lease.holding("t1") {
            lease.holding("t1") { events += "turn" }
            events += "check"
        }

        assertEquals(listOf("claim t1 by 7", "turn", "check", "release t1"), events)
    }
}
