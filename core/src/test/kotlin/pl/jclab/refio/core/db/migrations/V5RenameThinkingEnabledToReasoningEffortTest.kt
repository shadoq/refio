package pl.jclab.refio.core.db.migrations

import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import pl.jclab.refio.core.db.ConfigScope
import pl.jclab.refio.core.db.ConfigTable
import pl.jclab.refio.testutil.TestDatabase
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The V5 migration renames the legacy boolean config key `general.thinking_enabled` to the
 * enum `general.reasoning_effort`, mapping `true` -> MEDIUM and everything else -> OFF. It must
 * do so for every stored override (any scope) and leave databases without the legacy key alone.
 */
class V5RenameThinkingEnabledToReasoningEffortTest {

    private val shared = TestDatabase.createSharedInMemory()
    private val db = shared.database

    @AfterTest
    fun tearDown() {
        shared.keepAlive.close()
    }

    private fun insert(key: String, value: String, scope: ConfigScope, projectId: String? = null) {
        transaction(db) {
            ConfigTable.insert {
                it[ConfigTable.key] = key
                it[ConfigTable.value] = value
                it[ConfigTable.scope] = scope
                it[ConfigTable.projectId] = projectId
                it[ConfigTable.taskId] = null
            }
        }
    }

    private fun valueOf(key: String, scope: ConfigScope): String? = transaction(db) {
        ConfigTable
            .select { (ConfigTable.key eq key) and (ConfigTable.scope eq scope) }
            .map { it[ConfigTable.value] }
            .firstOrNull()
    }

    private fun countOf(key: String): Long = transaction(db) {
        ConfigTable.select { ConfigTable.key eq key }.count()
    }

    @Test
    fun `maps true to MEDIUM and any other value to OFF across scopes while renaming the key`() {
        insert("general.thinking_enabled", "true", ConfigScope.APP)
        insert("general.thinking_enabled", "false", ConfigScope.PROJECT, projectId = "proj-1")

        V5RenameThinkingEnabledToReasoningEffort().migrate(db)

        assertEquals("MEDIUM", valueOf("general.reasoning_effort", ConfigScope.APP))
        assertEquals("OFF", valueOf("general.reasoning_effort", ConfigScope.PROJECT))
        // The legacy key is fully renamed, not duplicated.
        assertEquals(0L, countOf("general.thinking_enabled"))
    }

    @Test
    fun `leaves a database without the legacy key untouched`() {
        insert("general.reasoning_effort", "HIGH", ConfigScope.APP)

        V5RenameThinkingEnabledToReasoningEffort().migrate(db)

        assertEquals("HIGH", valueOf("general.reasoning_effort", ConfigScope.APP))
    }
}
