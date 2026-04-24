package pl.jclab.refio.core.db.migrations

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import pl.jclab.refio.core.logging.dualLogger
import java.util.UUID

private val logger = dualLogger("V4SnapshotGroups")

/**
 * Introduces `snapshot_groups` and rewires FKs to fix the snapshot wiring:
 *
 * - `snapshots.subtask_id` (FK -> subtasks.id) becomes `snapshots.group_id` (FK -> snapshot_groups.id).
 * - `subtasks.snapshot_id_before_write` previously declared FK -> snapshots.id; the runtime
 *   always tried to store a subtaskId there, so every link attempt failed the FK constraint
 *   and the column stayed NULL. We point the FK at snapshot_groups.id instead.
 *
 * SQLite cannot alter FKs in place, so affected tables are rebuilt. Existing snapshots are
 * migrated by creating one group per distinct `subtask_id` (subtask_id is preserved on the
 * group for traceability).
 */
class V4SnapshotGroups : Migration {
    override val version: Int = 4

    override fun migrate(database: Database) {
        transaction(database) {
            val jdbc = connection.connection as java.sql.Connection
            // Fresh DB: snapshots is created later with new schema, nothing to migrate.
            if (!tableExists(jdbc, "snapshots") || !columnExists(jdbc, "snapshots", "subtask_id")) {
                logger.info { "snapshots table absent or already migrated; skipping V4" }
                return@transaction
            }
            jdbc.createStatement().use { st ->
                st.execute("PRAGMA foreign_keys = OFF")
                try {
                    // 1) Create snapshot_groups if not present
                    st.execute(
                        """
                        CREATE TABLE IF NOT EXISTS snapshot_groups (
                            id VARCHAR(36) PRIMARY KEY,
                            task_id VARCHAR(36) NOT NULL,
                            subtask_id VARCHAR(36),
                            created_at BIGINT NOT NULL,
                            FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )
                    st.execute("CREATE INDEX IF NOT EXISTS idx_snapshot_groups_task ON snapshot_groups(task_id, created_at)")
                    st.execute("CREATE INDEX IF NOT EXISTS idx_snapshot_groups_subtask ON snapshot_groups(subtask_id)")

                    // 2) Create groups for existing snapshots.
                    // One group per distinct (task_id, subtask_id) — NULL subtask_id also forms its own group per task.
                    val existingSnapshots = mutableListOf<Triple<String, String?, Long>>()
                    jdbc.prepareStatement(
                        "SELECT DISTINCT task_id, subtask_id, MIN(created_at) FROM snapshots GROUP BY task_id, subtask_id"
                    ).use { ps ->
                        ps.executeQuery().use { rs ->
                            while (rs.next()) {
                                existingSnapshots.add(
                                    Triple(rs.getString(1), rs.getString(2), rs.getLong(3))
                                )
                            }
                        }
                    }

                    val groupIdBySubtaskKey = mutableMapOf<Pair<String, String?>, String>()
                    jdbc.prepareStatement(
                        "INSERT INTO snapshot_groups(id, task_id, subtask_id, created_at) VALUES (?, ?, ?, ?)"
                    ).use { ins ->
                        for ((taskId, subtaskId, createdAt) in existingSnapshots) {
                            val groupId = UUID.randomUUID().toString()
                            groupIdBySubtaskKey[taskId to subtaskId] = groupId
                            ins.setString(1, groupId)
                            ins.setString(2, taskId)
                            if (subtaskId == null) ins.setNull(3, java.sql.Types.VARCHAR) else ins.setString(3, subtaskId)
                            ins.setLong(4, createdAt)
                            ins.addBatch()
                        }
                        ins.executeBatch()
                    }

                    // 3) Rebuild `snapshots` with group_id instead of subtask_id
                    st.execute(
                        """
                        CREATE TABLE snapshots_new (
                            id VARCHAR(36) PRIMARY KEY,
                            task_id VARCHAR(36) NOT NULL,
                            group_id VARCHAR(36) NOT NULL,
                            file_path TEXT NOT NULL,
                            content_hash VARCHAR(64) NOT NULL,
                            content_compressed BLOB NOT NULL,
                            original_size BIGINT NOT NULL,
                            compression_ratio DOUBLE NOT NULL DEFAULT 1.0,
                            created_at BIGINT NOT NULL,
                            FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE,
                            FOREIGN KEY (group_id) REFERENCES snapshot_groups(id) ON DELETE CASCADE
                        )
                        """.trimIndent()
                    )

                    // Copy rows, resolving group_id from the (task_id, subtask_id) pair
                    jdbc.prepareStatement(
                        "SELECT id, task_id, subtask_id, file_path, content_hash, content_compressed, original_size, compression_ratio, created_at FROM snapshots"
                    ).use { ps ->
                        jdbc.prepareStatement(
                            "INSERT INTO snapshots_new(id, task_id, group_id, file_path, content_hash, content_compressed, original_size, compression_ratio, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                        ).use { ins ->
                            ps.executeQuery().use { rs ->
                                while (rs.next()) {
                                    val id = rs.getString(1)
                                    val taskId = rs.getString(2)
                                    val subtaskId = rs.getString(3) // may be null
                                    val groupId = groupIdBySubtaskKey[taskId to subtaskId]
                                        ?: continue  // orphan; shouldn't happen, skip safely
                                    ins.setString(1, id)
                                    ins.setString(2, taskId)
                                    ins.setString(3, groupId)
                                    ins.setString(4, rs.getString(4))
                                    ins.setString(5, rs.getString(5))
                                    ins.setBytes(6, rs.getBytes(6))
                                    ins.setLong(7, rs.getLong(7))
                                    ins.setDouble(8, rs.getDouble(8))
                                    ins.setLong(9, rs.getLong(9))
                                    ins.addBatch()
                                }
                                ins.executeBatch()
                            }
                        }
                    }

                    st.execute("DROP TABLE snapshots")
                    st.execute("ALTER TABLE snapshots_new RENAME TO snapshots")
                    st.execute("CREATE INDEX IF NOT EXISTS idx_snapshots_task ON snapshots(task_id, created_at)")
                    st.execute("CREATE INDEX IF NOT EXISTS idx_snapshots_group ON snapshots(group_id)")
                    st.execute("CREATE INDEX IF NOT EXISTS idx_snapshots_file_hash ON snapshots(file_path, content_hash)")

                    // 4) Rebuild `subtasks` to change FK of snapshot_id_before_write.
                    // Pre-existing values are effectively all NULL (FK always failed), so we can also
                    // NULL them defensively during the rebuild.
                    st.execute(
                        """
                        CREATE TABLE subtasks_new (
                            id VARCHAR(36) PRIMARY KEY,
                            task_id VARCHAR(36) NOT NULL,
                            order_index INT NOT NULL,
                            kind VARCHAR(32) NOT NULL,
                            status VARCHAR(16) NOT NULL DEFAULT 'NEW',
                            description TEXT NOT NULL,
                            params_json TEXT,
                            step_plan_json TEXT,
                            summary TEXT,
                            requires_approval INT NOT NULL DEFAULT 0,
                            approval_status VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUIRED',
                            approved_at BIGINT,
                            result TEXT,
                            error_message TEXT,
                            error_stacktrace TEXT,
                            llm_model VARCHAR(64),
                            llm_provider VARCHAR(32),
                            input_tokens INT NOT NULL DEFAULT 0,
                            output_tokens INT NOT NULL DEFAULT 0,
                            cost_usd DOUBLE NOT NULL DEFAULT 0.0,
                            latency_ms INT NOT NULL DEFAULT 0,
                            snapshot_id_before_write VARCHAR(36),
                            created_at BIGINT NOT NULL,
                            updated_at BIGINT NOT NULL,
                            started_at BIGINT,
                            completed_at BIGINT,
                            FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE,
                            FOREIGN KEY (snapshot_id_before_write) REFERENCES snapshot_groups(id) ON DELETE SET NULL,
                            UNIQUE (task_id, order_index)
                        )
                        """.trimIndent()
                    )

                    st.execute(
                        """
                        INSERT INTO subtasks_new
                            (id, task_id, order_index, kind, status, description, params_json, step_plan_json,
                             summary, requires_approval, approval_status, approved_at, result, error_message,
                             error_stacktrace, llm_model, llm_provider, input_tokens, output_tokens, cost_usd,
                             latency_ms, snapshot_id_before_write, created_at, updated_at, started_at, completed_at)
                        SELECT
                             id, task_id, order_index, kind, status, description, params_json, step_plan_json,
                             summary, requires_approval, approval_status, approved_at, result, error_message,
                             error_stacktrace, llm_model, llm_provider, input_tokens, output_tokens, cost_usd,
                             latency_ms, NULL, created_at, updated_at, started_at, completed_at
                        FROM subtasks
                        """.trimIndent()
                    )

                    st.execute("DROP TABLE subtasks")
                    st.execute("ALTER TABLE subtasks_new RENAME TO subtasks")

                    logger.info {
                        "Migrated ${existingSnapshots.size} snapshot group(s); rebuilt snapshots + subtasks with corrected FKs"
                    }
                } finally {
                    st.execute("PRAGMA foreign_keys = ON")
                }
            }
        }
    }
}
