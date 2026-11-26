package pl.jclab.refio.core.db.migrations

import pl.jclab.refio.core.logging.dualLogger
import java.sql.Connection

private val logger = dualLogger("V2DropAgentEventsSessionFk")

/**
 * Rebuilds `agent_events` without the foreign key on `session_id`.
 *
 * The original schema referenced `agent_sessions.id`, but events emitted from
 * single-agent [pl.jclab.refio.core.services.AgentTurnLoop] runs use the
 * `tasks.id` as the correlation/session id.
 *
 * SQLite cannot DROP a FOREIGN KEY in place, so we recreate the table.
 * Foreign keys are disabled outside the transaction by [SqliteMigration].
 */
class V2DropAgentEventsSessionFk : SqliteMigration() {
    override val version: Int = 2
    override val requiresForeignKeysOff: Boolean = true

    override fun migrateOnConnection(jdbc: Connection) {
        if (!tableExists(jdbc, "agent_events")) {
            logger.info { "agent_events table does not exist (fresh DB); skipping V2 rebuild" }
            return
        }
        jdbc.createStatement().use { st ->
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS agent_events_new (
                    id VARCHAR(36) PRIMARY KEY,
                    session_id VARCHAR(36) NOT NULL,
                    source_agent_id VARCHAR(36) NOT NULL,
                    event_type VARCHAR(64) NOT NULL,
                    correlation_id VARCHAR(36) NOT NULL,
                    payload_json TEXT NOT NULL,
                    "timestamp" BIGINT NOT NULL
                )
                """.trimIndent()
            )

            // Copy any existing rows (best-effort).
            st.execute(
                """
                INSERT OR IGNORE INTO agent_events_new
                    (id, session_id, source_agent_id, event_type, correlation_id, payload_json, "timestamp")
                SELECT id, session_id, source_agent_id, event_type, correlation_id, payload_json, "timestamp"
                FROM agent_events
                """.trimIndent()
            )

            st.execute("DROP TABLE agent_events")
            st.execute("ALTER TABLE agent_events_new RENAME TO agent_events")

            st.execute("CREATE INDEX IF NOT EXISTS idx_agent_events_session_ts ON agent_events(session_id, \"timestamp\")")
            st.execute("CREATE INDEX IF NOT EXISTS idx_agent_events_source ON agent_events(source_agent_id)")
            st.execute("CREATE INDEX IF NOT EXISTS idx_agent_events_type ON agent_events(event_type)")

            logger.info { "Rebuilt agent_events without session_id FK" }
        }
    }
}
