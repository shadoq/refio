package pl.jclab.refio.core.db.migrations

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import pl.jclab.refio.core.logging.dualLogger

private val logger = dualLogger("V7SubtaskAgentInstance")

/**
 * Adds `subtasks.agent_instance_id`: which agent run (parent = NULL, or one subagent invocation)
 * produced the step, so each run's prompt is built from its own steps only. Steps written before the
 * column existed get the owner from their tool result message, which already carries both the subtask
 * id and the agent instance id. Skips when the column is present: a fresh database gets it from the
 * table definition, and a second run must not touch rows the application has written since.
 */
class V7SubtaskAgentInstance : Migration {
    override val version: Int = 7

    override fun migrate(database: Database) {
        transaction(database) {
            val jdbc = (connection.connection as java.sql.Connection)
            if (!tableExists(jdbc, "subtasks")) {
                logger.info { "subtasks table does not exist (fresh DB); skipping V7" }
                return@transaction
            }
            if (columnExists(jdbc, "subtasks", "agent_instance_id")) {
                logger.info { "subtasks.agent_instance_id already present; skipping V7" }
                return@transaction
            }
            jdbc.createStatement().use { st ->
                st.executeUpdate("ALTER TABLE subtasks ADD COLUMN agent_instance_id VARCHAR(36) NULL")
                if (tableExists(jdbc, "chat_messages")) {
                    val filled = st.executeUpdate(BACKFILL_SQL)
                    logger.info { "Added subtasks.agent_instance_id; owner set from tool messages for $filled steps" }
                }
            }
        }
    }

    companion object {
        /**
         * Gives each step the agent instance of its tool messages. The messages are read once and
         * grouped by step: a subquery per step scanned every message once per step, which on a
         * long history took minutes with the write lock held.
         */
        internal const val BACKFILL_SQL =
            "UPDATE subtasks SET agent_instance_id = owners.agent_instance_id FROM (" +
                "SELECT subtask_id, MIN(agent_instance_id) AS agent_instance_id FROM chat_messages " +
                "WHERE subtask_id IS NOT NULL AND agent_instance_id IS NOT NULL GROUP BY subtask_id) AS owners " +
                "WHERE owners.subtask_id = subtasks.id"
    }
}
