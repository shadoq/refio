package pl.jclab.refio.cli.tui.input

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.*

class TuiContextValidatorTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `should accept valid small file`() {
        val file = File(tempDir.toFile(), "small.txt")
        file.writeText("Hello world")

        val result = TuiContextValidator.validate("@file:small.txt", tempDir.toString())
        assertTrue(result.isValid)
        assertNull(result.warning)
    }

    @Test
    fun `should reject file that does not exist`() {
        val result = TuiContextValidator.validate("@file:nonexistent.txt", tempDir.toString())
        assertFalse(result.isValid)
        assertNotNull(result.warning)
        assertTrue(result.warning!!.contains("not found"))
    }

    @Test
    fun `should reject file that exceeds size limit`() {
        val file = File(tempDir.toFile(), "big.txt")
        file.writeBytes(ByteArray((TuiContextValidator.MAX_SINGLE_FILE_SIZE_BYTES + 1).toInt()))

        val result = TuiContextValidator.validate("@file:big.txt", tempDir.toString())
        assertFalse(result.isValid)
        assertTrue(result.warning!!.contains("too large"))
    }

    @Test
    fun `should warn for large but valid file`() {
        val file = File(tempDir.toFile(), "medium.txt")
        // Just over half the limit
        file.writeBytes(ByteArray((TuiContextValidator.MAX_SINGLE_FILE_SIZE_BYTES / 2 + 1).toInt()))

        val result = TuiContextValidator.validate("@file:medium.txt", tempDir.toString())
        assertTrue(result.isValid)
        assertNotNull(result.warning)
        assertTrue(result.warning!!.contains("Large file"))
    }

    @Test
    fun `should accept valid folder`() {
        val folder = File(tempDir.toFile(), "src")
        folder.mkdir()

        val result = TuiContextValidator.validate("@folder:src", tempDir.toString())
        assertTrue(result.isValid)
    }

    @Test
    fun `should reject folder that does not exist`() {
        val result = TuiContextValidator.validate("@folder:nonexistent", tempDir.toString())
        assertFalse(result.isValid)
        assertTrue(result.warning!!.contains("not found"))
    }

    @Test
    fun `should pass through non-file context types`() {
        val result = TuiContextValidator.validate("@git_diff", tempDir.toString())
        assertTrue(result.isValid)
    }

    @Test
    fun `should reject directory passed as file`() {
        val folder = File(tempDir.toFile(), "mydir")
        folder.mkdir()

        val result = TuiContextValidator.validate("@file:mydir", tempDir.toString())
        assertFalse(result.isValid)
        assertTrue(result.warning!!.contains("Not a file"))
    }
}
