package pl.jclab.refio.core.db.repositories

import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.db.ChatMessagesTable
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.testutil.TestDatabase
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A long task can outlive a restart of the computer. The clock that orders a task's messages starts
 * again near zero after a restart, and the history must still read in the order it was written.
 */
class MessageOrderAfterRestartTest {

    private lateinit var db: TestDatabase.SharedInMemoryDb

    @BeforeEach
    fun setup() {
        db = TestDatabase.createSharedInMemory()
        transaction { TaskRepository().create(name = "Stubs", mode = TaskMode.AGENT, projectId = "p", projectPath = "/tmp/p", id = "t1") }
    }

    @AfterEach
    fun tearDown() {
        db.keepAlive.close()
    }

    @Test
    fun `messages keep their order when the clock restarts`() {
        var nanos = 5_000_000_000L
        val repository = ChatMessageRepository(nanoClock = { nanos })

        repository.create(taskId = "t1", role = MessageRole.USER, content = "first")
        nanos = 1_000L // the computer restarted
        repository.create(taskId = "t1", role = MessageRole.ASSISTANT, content = "second")
        repository.create(taskId = "t1", role = MessageRole.USER, content = "third")

        assertEquals(listOf("first", "second", "third"), repository.findByTaskId("t1").map { it.content })
    }

    @Test
    fun `a database from before the order index gets it at start`() {
        transaction {
            exec("DROP INDEX IF EXISTS idx_chat_messages_task_seq")

            SchemaUtils.createMissingTablesAndColumns(ChatMessagesTable)

            val indexes = mutableListOf<String>()
            exec("PRAGMA index_list('chat_messages')") { rs ->
                while (rs.next()) {
                    indexes += rs.getString("name")
                }
            }
            assertTrue("idx_chat_messages_task_seq" in indexes, "existing users read history without it: $indexes")
        }
    }
}
