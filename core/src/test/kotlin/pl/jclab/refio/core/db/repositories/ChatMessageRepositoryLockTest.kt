package pl.jclab.refio.core.db.repositories

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.jclab.refio.core.db.MessageRole
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.testutil.TestDatabase
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.concurrent.thread
import kotlin.test.assertEquals

/**
 * The IDE and the CLI write to one database file. While one of them writes, the other must wait for
 * its turn the way SQLite's busy timeout lets it, instead of failing in the middle of a turn.
 */
class ChatMessageRepositoryLockTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `a new message waits for another process's write instead of failing`() {
        // Reading the task's last position before inserting made the insert a read that later
        // wants to write. SQLite answers that with "database is locked" at once, without waiting.
        val (_, path) = TestDatabase.createTemporary(dir)
        val url = "jdbc:sqlite:$path"
        Database.connect(
            url = url,
            driver = "org.sqlite.JDBC",
            setupConnection = { connection -> connection.createStatement().use { it.execute("PRAGMA busy_timeout=10000") } },
        )
        transaction { TaskRepository().create(name = "Locks", mode = TaskMode.AGENT, projectId = "p", projectPath = "/tmp/p", id = "t1") }
        val otherProcess = DriverManager.getConnection(url)
        otherProcess.createStatement().use { it.execute("CREATE TABLE other_process (x INTEGER)") }
        otherProcess.autoCommit = false
        otherProcess.createStatement().use { it.execute("INSERT INTO other_process VALUES (1)") }
        val release = thread {
            Thread.sleep(1_000)
            otherProcess.commit()
            otherProcess.close()
        }

        val message = ChatMessageRepository().create(taskId = "t1", role = MessageRole.USER, content = "Implement the stubs")

        release.join()
        assertEquals("Implement the stubs", message.content)
    }
}
