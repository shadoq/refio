package pl.jclab.refio.core.db.repositories

import org.junit.jupiter.api.Test
import pl.jclab.refio.core.db.ConfigScope
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
}
