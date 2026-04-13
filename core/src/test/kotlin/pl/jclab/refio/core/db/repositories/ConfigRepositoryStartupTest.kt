package pl.jclab.refio.core.db.repositories

import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.db.ConfigScope
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConfigRepositoryStartupTest {

    private val repository = ConfigRepository()

    @Test
    fun `should return safe defaults before database initialization`() {
        assertNull(repository.get("missing.key", ConfigScope.APP))
        assertNull(repository.getWithPrecedence("missing.key"))
        assertEquals(emptyList(), repository.findByScope(ConfigScope.APP))
        assertEquals(emptyList(), repository.search("missing.%"))
        assertEquals(0L, repository.count())
    }

    @Test
    fun `should return safe defaults when database exists but config table is missing`() {
        val dbName = "config-startup-${System.nanoTime()}"
        val jdbcUrl = "jdbc:sqlite:file:$dbName?mode=memory&cache=shared"

        val keepAlive = DriverManager.getConnection(jdbcUrl)
        try {
            Database.connect(
                url = jdbcUrl,
                driver = "org.sqlite.JDBC"
            )

            assertNull(repository.get("missing.key", ConfigScope.APP))
            assertNull(repository.getWithPrecedence("missing.key"))
            assertEquals(emptyList(), repository.findByScope(ConfigScope.APP))
            assertEquals(emptyList(), repository.search("missing.%"))
            assertEquals(0L, repository.count())
        } finally {
            keepAlive.close()
        }
    }
}
