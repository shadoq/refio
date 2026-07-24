package pl.jclab.refio.core.db.repositories

import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.jclab.refio.core.db.SubtaskKind
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.testutil.TestDatabase
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * createNext must allocate order_index collision-safely.
 *
 * The unique index uk_task_order(task_id, order_index) crashes the naive "read getMaxOrderIndex,
 * then create at max+1" pattern whenever turns that share a taskId run in parallel - exactly what
 * happens with parallel subagents. That collision took down a real subagent mid-run and, because
 * the exception escaped the turn loop, left its Agents-Graph node stuck [RUNNING].
 *
 * A file-backed WAL database with the production PRAGMAs is used so the only conflict createNext has
 * to resolve is the stale-max UNIQUE collision (writers coordinate via busy_timeout), which is the
 * exact failure being fixed - not raw lock contention.
 */
class SubtaskRepositoryConcurrencyTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var repository: SubtaskRepository
    private lateinit var taskRepository: TaskRepository
    private var dbPath: String = ""

    @BeforeEach
    fun setup() {
        val (_, path) = TestDatabase.createTemporary(tempDir)
        dbPath = path
        repository = SubtaskRepository()
        taskRepository = TaskRepository()
    }

    @AfterEach
    fun tearDown() {
        TestDatabase.cleanup(dbPath)
    }

    private fun createTask(id: String) = transaction {
        taskRepository.create(
            name = "Concurrent Task",
            mode = TaskMode.AGENT,
            projectId = "project-123",
            projectPath = "/test/project",
            id = id
        )
    }

    @Test
    fun `createNext allocates sequential order_index values on a shared task`() {
        val task = createTask("task-seq")

        val first = repository.createNext(task.id, SubtaskKind.READ_FILE, "one")
        val second = repository.createNext(task.id, SubtaskKind.READ_FILE, "two")
        val third = repository.createNext(task.id, SubtaskKind.READ_FILE, "three")

        assertEquals(0, first.orderIndex)
        assertEquals(1, second.orderIndex)
        assertEquals(2, third.orderIndex)
    }

    @Test
    fun `concurrent createNext on one task never collides on order_index`() {
        val task = createTask("task-parallel")
        val threads = 4
        val perThread = 25

        val allocated = ConcurrentLinkedQueue<Int>()
        val failures = ConcurrentLinkedQueue<Throwable>()
        val startGate = CountDownLatch(1)

        val workers = (1..threads).map {
            Thread {
                startGate.await()
                repeat(perThread) {
                    try {
                        allocated.add(
                            repository.createNext(task.id, SubtaskKind.READ_FILE, "parallel").orderIndex
                        )
                    } catch (e: Throwable) {
                        failures.add(e)
                    }
                }
            }.apply { start() }
        }
        startGate.countDown()
        workers.forEach { it.join(30_000) }

        assertTrue(
            failures.isEmpty(),
            "createNext must resolve order_index collisions via retry, but ${failures.size} escaped: " +
                failures.take(3).map { it.message }
        )
        val indices = allocated.toList()
        assertEquals(threads * perThread, indices.size, "every createNext call must produce a row")
        assertEquals(indices.size, indices.toSet().size, "every order_index must be unique per task")
    }
}
