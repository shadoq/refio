package pl.jclab.refio.cli

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for StandaloneCoreBootstrap.
 *
 * Note: DatabaseFactory is a global singleton, so tests that call initialize()
 * share database state. We use a single test method for the full lifecycle
 * to avoid global state conflicts.
 */
class StandaloneCoreBootstrapTest {

    @Test
    fun `should throw if accessed before initialize`() {
        val tempDir = Files.createTempDirectory("refio-test-")
        try {
            val bootstrap = StandaloneCoreBootstrap(tempDir)
            assertFailsWith<IllegalStateException> {
                bootstrap.router
            }
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `should shutdown gracefully even without initialize`() {
        val tempDir = Files.createTempDirectory("refio-test-")
        try {
            val bootstrap = StandaloneCoreBootstrap(tempDir)
            // Should not throw
            bootstrap.shutdown()
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `should initialize and shutdown full lifecycle`() {
        val tempDir = Files.createTempDirectory("refio-test-")
        try {
            val bootstrap = StandaloneCoreBootstrap(tempDir)

            // Initialize
            val router = bootstrap.initialize()
            assertNotNull(router)
            assertEquals(router, bootstrap.router)

            // .refio directory should be created
            val refioDir = tempDir.resolve(".refio").toFile()
            assertTrue(refioDir.exists(), ".refio directory should be created")
            assertTrue(refioDir.resolve("database.sqlite").exists(), "database.sqlite should be created")

            // Shutdown
            bootstrap.shutdown()
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
