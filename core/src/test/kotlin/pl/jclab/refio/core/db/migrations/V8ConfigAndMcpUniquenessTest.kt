package pl.jclab.refio.core.db.migrations

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.db.repositories.ConfigRepository
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class V8ConfigAndMcpUniquenessTest {

    @Test
    fun `deduplicates APP and PROJECT config rows and global mcp_servers keeping newest entry and enforcing uniqueness`(@TempDir tempDir: Path) {
        val dbPath = tempDir.resolve("v8-dedup.sqlite").toString()
        val jdbcUrl = "jdbc:sqlite:$dbPath"

        // Build pre-V8 tables without partial unique indexes and insert duplicate rows with NULL PK columns.
        DriverManager.getConnection(jdbcUrl).use { conn ->
            conn.createStatement().use { st ->
                st.execute("PRAGMA foreign_keys = ON")
                st.execute(
                    """
                    CREATE TABLE tasks (
                        id VARCHAR(36) PRIMARY KEY
                    )
                    """.trimIndent()
                )
                st.execute(
                    """
                    CREATE TABLE config (
                        key VARCHAR(128) NOT NULL,
                        value TEXT NOT NULL,
                        scope VARCHAR(16) NOT NULL,
                        project_id VARCHAR(512),
                        task_id VARCHAR(36) REFERENCES tasks(id) ON DELETE CASCADE,
                        description TEXT,
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL,
                        PRIMARY KEY (key, scope, project_id, task_id)
                    )
                    """.trimIndent()
                )
                st.execute(
                    """
                    CREATE TABLE mcp_servers (
                        id VARCHAR(128) NOT NULL,
                        project_id VARCHAR(512),
                        config_json TEXT NOT NULL,
                        enabled BOOLEAN NOT NULL DEFAULT 1,
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL,
                        PRIMARY KEY (id, project_id)
                    )
                    """.trimIndent()
                )

                // Duplicate APP config rows (SQLite allows this because project_id/task_id are NULL)
                st.execute(
                    "INSERT INTO config(key, value, scope, project_id, task_id, created_at, updated_at) " +
                        "VALUES ('general.no_egress_enabled', 'false', 'APP', NULL, NULL, 100, 100)"
                )
                st.execute(
                    "INSERT INTO config(key, value, scope, project_id, task_id, created_at, updated_at) " +
                        "VALUES ('general.no_egress_enabled', 'true', 'APP', NULL, NULL, 100, 200)"
                )
                // Duplicate PROJECT config rows (task_id is NULL)
                st.execute(
                    "INSERT INTO config(key, value, scope, project_id, task_id, created_at, updated_at) " +
                        "VALUES ('limits.api_call_timeout', '30', 'PROJECT', 'proj-1', NULL, 100, 150)"
                )
                st.execute(
                    "INSERT INTO config(key, value, scope, project_id, task_id, created_at, updated_at) " +
                        "VALUES ('limits.api_call_timeout', '120', 'PROJECT', 'proj-1', NULL, 100, 300)"
                )
                // Duplicate global MCP rows (project_id is NULL)
                st.execute(
                    "INSERT INTO mcp_servers(id, project_id, config_json, enabled, created_at, updated_at) " +
                        "VALUES ('ctx7', NULL, '{\"id\":\"ctx7\",\"url\":\"http://old\"}', 0, 100, 100)"
                )
                st.execute(
                    "INSERT INTO mcp_servers(id, project_id, config_json, enabled, created_at, updated_at) " +
                        "VALUES ('ctx7', NULL, '{\"id\":\"ctx7\",\"url\":\"http://new\"}', 1, 100, 250)"
                )
            }
        }

        val database = Database.connect(
            url = jdbcUrl,
            driver = "org.sqlite.JDBC",
            setupConnection = { conn ->
                conn.createStatement().use { st ->
                    st.execute("PRAGMA foreign_keys = ON")
                    st.execute("PRAGMA busy_timeout = 10000")
                }
            }
        )

        MigrationRunner.run(database)
        // Re-running MigrationRunner must be idempotent.
        MigrationRunner.run(database)

        val repo = ConfigRepository()
        val appConfig = transaction(database) { repo.get("general.no_egress_enabled", ConfigScope.APP) }
        assertNotNull(appConfig)
        assertEquals("true", appConfig.value)

        val projectConfig = transaction(database) {
            repo.get("limits.api_call_timeout", ConfigScope.PROJECT, projectId = "proj-1")
        }
        assertNotNull(projectConfig)
        assertEquals("120", projectConfig.value)

        // Verify partial unique indexes block new duplicate inserts with NULLs.
        DriverManager.getConnection(jdbcUrl).use { conn ->
            conn.createStatement().use { st ->
                assertFailsWith<SQLException> {
                    st.execute(
                        "INSERT INTO config(key, value, scope, project_id, task_id, created_at, updated_at) " +
                            "VALUES ('general.no_egress_enabled', 'false', 'APP', NULL, NULL, 300, 300)"
                    )
                }
                assertFailsWith<SQLException> {
                    st.execute(
                        "INSERT INTO mcp_servers(id, project_id, config_json, enabled, created_at, updated_at) " +
                            "VALUES ('ctx7', NULL, '{\"id\":\"ctx7\"}', 1, 300, 300)"
                    )
                }
                st.executeQuery("SELECT config_json FROM mcp_servers WHERE id = 'ctx7' AND project_id IS NULL").use { rs ->
                    assertEquals(true, rs.next())
                    assertEquals("{\"id\":\"ctx7\",\"url\":\"http://new\"}", rs.getString(1))
                    assertEquals(false, rs.next())
                }
            }
        }
    }

    @Test
    fun `V4SnapshotGroups rebuilds snapshots and subtasks cleanly with foreign_keys enabled on connection`(@TempDir tempDir: Path) {
        val dbPath = tempDir.resolve("v4-fk.sqlite").toString()
        val jdbcUrl = "jdbc:sqlite:$dbPath"

        DriverManager.getConnection(jdbcUrl).use { conn ->
            conn.createStatement().use { st ->
                st.execute("PRAGMA foreign_keys = ON")
                st.execute("CREATE TABLE tasks (id VARCHAR(36) PRIMARY KEY)")
                st.execute("INSERT INTO tasks(id) VALUES ('task-1')")
                st.execute(
                    """
                    CREATE TABLE subtasks (
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
                        FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                st.execute(
                    """
                    CREATE TABLE snapshots (
                        id VARCHAR(36) PRIMARY KEY,
                        task_id VARCHAR(36) NOT NULL,
                        subtask_id VARCHAR(36),
                        file_path TEXT NOT NULL,
                        content_hash VARCHAR(64) NOT NULL,
                        content_compressed BLOB NOT NULL,
                        original_size BIGINT NOT NULL,
                        compression_ratio DOUBLE NOT NULL DEFAULT 1.0,
                        created_at BIGINT NOT NULL,
                        FOREIGN KEY (task_id) REFERENCES tasks(id) ON DELETE CASCADE,
                        FOREIGN KEY (subtask_id) REFERENCES subtasks(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                // Create another table referencing subtasks to prove DROP TABLE subtasks needs FK=OFF outside transaction!
                st.execute(
                    """
                    CREATE TABLE chat_messages (
                        id VARCHAR(36) PRIMARY KEY,
                        subtask_id VARCHAR(36) REFERENCES subtasks(id)
                    )
                    """.trimIndent()
                )
                st.execute(
                    "INSERT INTO subtasks(id, task_id, order_index, kind, description, created_at, updated_at) " +
                        "VALUES ('sub-1', 'task-1', 1, 'CODE_EDITING', 'edit', 100, 100)"
                )
                st.execute(
                    "INSERT INTO snapshots(id, task_id, subtask_id, file_path, content_hash, content_compressed, original_size, created_at) " +
                        "VALUES ('snap-1', 'task-1', 'sub-1', 'a.kt', 'h', X'789C030000000001', 0, 100)"
                )
                st.execute("INSERT INTO chat_messages(id, subtask_id) VALUES ('msg-1', 'sub-1')")
            }
        }

        val database = Database.connect(
            url = jdbcUrl,
            driver = "org.sqlite.JDBC",
            setupConnection = { conn ->
                conn.createStatement().use { st ->
                    st.execute("PRAGMA foreign_keys = ON")
                }
            }
        )

        V4SnapshotGroups().migrate(database)

        DriverManager.getConnection(jdbcUrl).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM snapshot_groups").use { rs ->
                    assertEquals(true, rs.next())
                    assertEquals(1, rs.getInt(1))
                }
                st.executeQuery("SELECT COUNT(*) FROM snapshots").use { rs ->
                    assertEquals(true, rs.next())
                    assertEquals(1, rs.getInt(1))
                }
                st.executeQuery("SELECT COUNT(*) FROM subtasks").use { rs ->
                    assertEquals(true, rs.next())
                    assertEquals(1, rs.getInt(1))
                }
            }
        }
    }
}
