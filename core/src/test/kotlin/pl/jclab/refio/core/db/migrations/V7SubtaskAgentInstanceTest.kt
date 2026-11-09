package pl.jclab.refio.core.db.migrations

import org.jetbrains.exposed.sql.Database
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Subtask rows learn which agent run produced them. Rows written before the column existed get the
 * owner from the tool result messages, which already carry both the subtask id and the agent
 * instance id; the parent run's rows stay without an owner.
 */
class V7SubtaskAgentInstanceTest {

    private val url = "jdbc:sqlite:file:v7-${System.nanoTime()}?mode=memory&cache=shared"
    private val keepAlive: Connection = DriverManager.getConnection(url)
    private val db = Database.connect(url = url, driver = "org.sqlite.JDBC")

    @AfterTest
    fun tearDown() {
        keepAlive.close()
    }

    private fun exec(sql: String) {
        keepAlive.createStatement().use { it.executeUpdate(sql) }
    }

    private fun seedSchemaWithoutColumn() {
        exec("CREATE TABLE subtasks (id VARCHAR(36) PRIMARY KEY, task_id VARCHAR(36) NOT NULL, order_index INT NOT NULL)")
        exec(
            "CREATE TABLE chat_messages (id VARCHAR(36) PRIMARY KEY, task_id VARCHAR(36) NOT NULL, role VARCHAR(16) NOT NULL, " +
                "subtask_id VARCHAR(36) NULL, agent_instance_id VARCHAR(36) NULL)"
        )
        exec("INSERT INTO subtasks VALUES ('s-parent', 't1', 0), ('s-sub', 't1', 1)")
        exec(
            "INSERT INTO chat_messages VALUES ('m1', 't1', 'TOOL', 's-parent', NULL), " +
                "('m2', 't1', 'TOOL', 's-sub', 'sub-1'), ('m3', 't1', 'ASSISTANT', NULL, 'sub-1')"
        )
    }

    private fun ownerOf(subtaskId: String): String? =
        keepAlive.prepareStatement("SELECT agent_instance_id FROM subtasks WHERE id = ?").use { ps ->
            ps.setString(1, subtaskId)
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getString(1)
            }
        }

    @Test
    fun `backfills agent instance from tool messages`() {
        seedSchemaWithoutColumn()

        V7SubtaskAgentInstance().migrate(db)

        assertTrue(columnExists(keepAlive, "subtasks", "agent_instance_id"))
        assertEquals("sub-1", ownerOf("s-sub"))
        assertNull(ownerOf("s-parent"), "the parent run's step must stay without an owner")

        V7SubtaskAgentInstance().migrate(db)

        assertEquals("sub-1", ownerOf("s-sub"))
        assertNull(ownerOf("s-parent"))
    }

    @Test
    fun `fresh database without tables is left alone`() {
        V7SubtaskAgentInstance().migrate(db)

        assertTrue(!tableExists(keepAlive, "subtasks"))
    }

    @Test
    fun `backfill reads the tool messages once, not once per step`() {
        // The first start after the upgrade runs this over months of history. A lookup per step
        // that scans every message took minutes on such a database, with the write lock held.
        seedSchemaWithoutColumn()
        exec("ALTER TABLE subtasks ADD COLUMN agent_instance_id VARCHAR(36) NULL")

        val plan = keepAlive.createStatement().use { st ->
            st.executeQuery("EXPLAIN QUERY PLAN " + V7SubtaskAgentInstance.BACKFILL_SQL).use { rs ->
                buildList { while (rs.next()) add(rs.getString("detail")) }
            }
        }

        assertTrue(plan.none { it.contains("CORRELATED") }, "plan: $plan")
    }
}
