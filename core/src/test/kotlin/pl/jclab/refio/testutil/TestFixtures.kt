package pl.jclab.refio.testutil

import io.mockk.every
import io.mockk.mockk
import pl.jclab.refio.core.db.*

/**
 * Test fixtures and helper functions for tests.
 */
object TestFixtures {

    /**
     * Create a Config for testing.
     */
    fun createConfig(
        key: String = "test_key",
        value: String = "test_value",
        scope: ConfigScope = ConfigScope.APP,
        projectId: String? = null,
        taskId: String? = null
    ) = Config(
        key = key,
        value = value,
        scope = scope,
        projectId = projectId,
        taskId = taskId,
        description = null,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis()
    )

    /**
     * Create JSON for tool permissions config.
     */
    fun createToolPermissionsJson(
        tools: Map<String, Pair<String, String>> = emptyMap()
    ): String {
        val toolsJson = tools.entries.joinToString(",") { (name, permissions) ->
            """"$name":{"planMode":"${permissions.first}","agentMode":"${permissions.second}"}"""
        }
        return """{"tools":{$toolsJson}}"""
    }

    /**
     * Sample test project paths.
     */
    object Paths {
        const val PROJECT_ROOT = "/test/project"
        const val SRC_DIR = "/test/project/src"
        const val TEST_FILE = "/test/project/src/main.kt"
    }

    /**
     * Sample task IDs.
     */
    object TaskIds {
        const val CHAT_TASK = "chat-task-001"
        const val PLAN_TASK = "plan-task-001"
        const val AGENT_TASK = "agent-task-001"
    }
}

/**
 * Extension function to run a test with an in-memory database.
 */
inline fun <T> withTestDatabase(block: (org.jetbrains.exposed.sql.Database) -> T): T {
    val database = TestDatabase.createInMemory()
    return try {
        block(database)
    } finally {
        // In-memory database is automatically cleaned up
    }
}
