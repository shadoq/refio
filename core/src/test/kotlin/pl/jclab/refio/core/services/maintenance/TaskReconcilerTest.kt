package pl.jclab.refio.core.services.maintenance

import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.SubtaskKind
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.db.TaskStatus
import pl.jclab.refio.core.db.ToolCallData
import pl.jclab.refio.core.db.repositories.ChatMessageRepository
import pl.jclab.refio.core.db.repositories.SubtaskRepository
import pl.jclab.refio.core.db.repositories.TaskRepository
import pl.jclab.refio.core.db.repositories.TaskStateRepository
import pl.jclab.refio.core.db.repositories.TaskStateRow
import pl.jclab.refio.testutil.TestDatabase
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A closed IDE, Ctrl+C or a crash in the middle of a round leaves a task marked as running, a long
 * task's loop marked as running and a tool call without a result. The next process to start must
 * put that right, so the user can continue the task, but it must not touch a task that another
 * live process (the IDE and the CLI share one database) is still working on.
 */
class TaskReconcilerTest {

    private companion object {
        const val OWNER_PID = 4242L
        const val OWNER_STARTED_AT = 1_700_000_000_000L
        const val INTERRUPTED = "Interrupted: the Refio process stopped before this tool call finished."
    }

    private lateinit var db: TestDatabase.SharedInMemoryDb
    private val tasks = TaskRepository()
    private val states = TaskStateRepository()
    private val subtasks = SubtaskRepository()
    private val messages = ChatMessageRepository()

    @BeforeEach
    fun setup() {
        db = TestDatabase.createSharedInMemory()
        transaction { tasks.create(name = "Stubs", mode = TaskMode.AGENT, projectId = "p", projectPath = "/tmp/p", id = "t1") }
        tasks.update("t1", status = TaskStatus.RUNNING)
        states.upsert(TaskStateRow(taskId = "t1", untilCommand = "npm test", loopStatus = "RUNNING", roundsDone = 2))
        states.claimLease("t1", pid = OWNER_PID, startedAt = OWNER_STARTED_AT)
    }

    @AfterEach
    fun tearDown() {
        db.keepAlive.close()
    }

    private fun reconciler(ownerAlive: Boolean) = TaskReconciler(
        taskStateRepository = states,
        taskRepository = tasks,
        subtaskRepository = subtasks,
        chatMessageRepository = messages,
        isAlive = { pid, startedAt -> ownerAlive && pid == OWNER_PID && startedAt == OWNER_STARTED_AT },
    )

    private fun call(id: String, name: String) =
        messages.create(taskId = "t1", role = MessageRole.ASSISTANT, content = "", toolCalls = listOf(ToolCallData(id = id, name = name, arguments = "{}")))

    private fun answer(id: String, result: String) =
        messages.createToolResult(taskId = "t1", toolCallId = id, subtaskId = null, result = result)

    private fun toolResults() = messages.findByTaskId("t1").filter { it.role == MessageRole.TOOL }

    @Test
    fun `dead owner - task incomplete, loop interrupted, dangling tool calls closed`() {
        messages.create(taskId = "t1", role = MessageRole.USER, content = "Implement the 12 stubs in src/")
        call("c1", "read_file")
        answer("c1", "fun a() = TODO()")
        call("c2", "run_terminal_command")
        val running = subtasks.create(taskId = "t1", orderIndex = 1, kind = SubtaskKind.PLAN_STEP, description = "run_terminal_command", status = TaskStatus.RUNNING)

        val repaired = reconciler(ownerAlive = false).reconcile()

        assertEquals(1, repaired)
        assertEquals(TaskStatus.INCOMPLETE, tasks.findById("t1")?.status)
        val state = states.get("t1")!!
        assertEquals("INTERRUPTED", state.loopStatus, "the user can continue the loop with its next round")
        assertNull(state.ownerPid, "the dead process's lease is released")
        assertEquals(listOf("c1", "c2"), toolResults().map { it.toolCallId }, "every call has exactly one result")
        assertEquals(INTERRUPTED, toolResults().last().content)
        assertEquals(TaskStatus.CANCELED, subtasks.findById(running.id)?.status)
    }

    @Test
    fun `live owner is left alone`() {
        call("c2", "run_terminal_command")

        val repaired = reconciler(ownerAlive = true).reconcile()

        assertEquals(0, repaired)
        assertEquals(TaskStatus.RUNNING, tasks.findById("t1")?.status)
        assertEquals("RUNNING", states.get("t1")?.loopStatus)
        assertEquals(OWNER_PID, states.get("t1")?.ownerPid)
        assertEquals(emptyList(), toolResults(), "the call is still running in the other process")
    }

    @Test
    fun `a call an earlier stop left open is not answered at the end of the history`() {
        call("c0", "grep_search") // the user pressed Stop here an hour ago; Stop leaves the call open
        messages.create(taskId = "t1", role = MessageRole.USER, content = "Go on with the parser")
        call("c3", "advance_code_editing")

        reconciler(ownerAlive = false).reconcile()

        assertEquals(listOf("c3"), toolResults().map { it.toolCallId }, "a result far below its call would confuse the model")
    }
}
