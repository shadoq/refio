package pl.jclab.refio.core.config

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RefioHomeTest {

    @AfterEach
    fun restoreDefault() {
        RefioHome.override(null)
    }

    @Test
    fun `user state lands under the home directory when nothing overrides it`() {
        val expected = Path.of(System.getProperty("user.home"), ".refio")

        assertEquals(expected, RefioHome.resolve())
    }

    @Test
    fun `an overridden home keeps a headless run out of the directory a human is using`(
        @TempDir tmp: Path
    ) {
        RefioHome.override(tmp)

        val db = RefioHome.resolve("data", "database.sqlite")

        assertTrue(db.startsWith(tmp), "database must live under the override, was $db")
        assertEquals("database.sqlite", db.fileName.toString())
    }

    @Test
    fun `the user config path follows the override, so an e2e run cannot read personal settings`(
        @TempDir tmp: Path
    ) {
        RefioHome.override(tmp)

        assertEquals(tmp.resolve("config.yaml").toFile(), ConfigYaml.getUserConfigPath())
    }

    @Test
    fun `clearing the override restores the default, so one test cannot leak into the next`(
        @TempDir tmp: Path
    ) {
        RefioHome.override(tmp)
        RefioHome.override(null)

        assertEquals(Path.of(System.getProperty("user.home"), ".refio"), RefioHome.resolve())
    }

    @Test
    fun `a relative override is normalized, so the database path does not depend on the cwd`(
        @TempDir tmp: Path
    ) {
        RefioHome.override(tmp.resolve("nested").resolve("..").resolve("home"))

        assertEquals(tmp.resolve("home"), RefioHome.resolve())
    }
}
