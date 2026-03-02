package pl.jclab.refio.core.db.repositories

import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.db.*
import pl.jclab.refio.testutil.TestDatabase
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Testy dla ConfigRepository.
 */
class ConfigRepositoryTest {

    private lateinit var db: TestDatabase.SharedInMemoryDb
    private lateinit var repository: ConfigRepository

    @BeforeEach
    fun setup() {
        db = TestDatabase.createSharedInMemory()
        repository = ConfigRepository()
    }

    @AfterEach
    fun tearDown() {
        db.keepAlive.close()
    }

    @Nested
    inner class CreateTests {

        @Test
        fun `should create config value`() {
            transaction {
                // When
                val config = repository.set(
                    key = "test.key",
                    value = "test value",
                    scope = ConfigScope.APP
                )

                // Then
                assertNotNull(config)
                assertEquals("test.key", config.key)
                assertEquals("test value", config.value)
                assertEquals(ConfigScope.APP, config.scope)
            }
        }

        @Test
        fun `should update existing config`() {
            transaction {
                // Given
                repository.set(key = "test.key", value = "original", scope = ConfigScope.APP)

                // When
                val updated = repository.set(key = "test.key", value = "updated", scope = ConfigScope.APP)

                // Then
                assertNotNull(updated)
                assertEquals("updated", updated.value)
            }
        }
    }

    @Nested
    inner class FindTests {

        @Test
        fun `should find config by key and scope`() {
            transaction {
                // Given
                repository.set(key = "find.me", value = "found", scope = ConfigScope.APP)

                // When
                val found = repository.get("find.me", ConfigScope.APP)

                // Then
                assertNotNull(found)
                assertEquals("found", found.value)
            }
        }

        @Test
        fun `should return null for nonexistent key`() {
            transaction {
                // When
                val found = repository.get("nonexistent", ConfigScope.APP)

                // Then
                assertNull(found)
            }
        }

        @Test
        fun `should get config with precedence`() {
            transaction {
                // Given - APP level config
                repository.set(key = "precedence.key", value = "app-value", scope = ConfigScope.APP)

                // When - get with no scope specified (uses precedence)
                val found = repository.getWithPrecedence("precedence.key")

                // Then - should return APP level config
                assertNotNull(found)
                assertEquals("app-value", found.value)
            }
        }
    }

    @Nested
    inner class DeleteTests {

        @Test
        fun `should delete config`() {
            transaction {
                // Given
                repository.set(key = "delete.me", value = "value", scope = ConfigScope.APP)

                // When
                val deleted = repository.delete("delete.me", ConfigScope.APP)

                // Then
                assertEquals(true, deleted)
                assertNull(repository.get("delete.me", ConfigScope.APP))
            }
        }

        @Test
        fun `should find configs by scope`() {
            transaction {
                // Given
                repository.set(key = "config1", value = "v1", scope = ConfigScope.APP)
                repository.set(key = "config2", value = "v2", scope = ConfigScope.APP)

                // When
                val configs = repository.findByScope(ConfigScope.APP)

                // Then
                assertTrue(configs.size >= 2)
            }
        }
    }
}
