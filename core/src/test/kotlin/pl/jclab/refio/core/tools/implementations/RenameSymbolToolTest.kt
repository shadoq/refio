package pl.jclab.refio.core.tools.implementations

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.tools.refactor.RenameResult
import pl.jclab.refio.core.tools.refactor.StructuralRefactorer
import pl.jclab.refio.core.tools.refactor.UsageLocation
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The blast-radius gate. The text engine behind this tool rewrites every project file containing the
 * word, comments and string literals included, and no production path restores a snapshot, so a
 * rename of a common word is unrecoverable. The tool therefore refuses a very wide rename until the
 * caller confirms it, rather than relying on the permission gate (which in headless means "always
 * rejected" - the auto-approve regex describes build commands, not symbol names).
 */
class RenameSymbolToolTest {

    private fun usages(fileCount: Int): List<UsageLocation> =
        (1..fileCount).map { UsageLocation(file = "src/file$it.kt", line = 1, snippet = "value") }

    private fun refactorerWith(usageFiles: Int): StructuralRefactorer = mockk {
        every { engineDescription } returns "text"
        coEvery { findUsages(any()) } returns usages(usageFiles)
        coEvery { renameSymbol(any(), any(), any(), any()) } returns RenameResult(
            filesChanged = (1..usageFiles).map { "src/file$it.kt" },
            replacements = usageFiles,
        )
    }

    private val params = mapOf(
        "file" to "src/file1.kt",
        "line" to 1,
        "old_name" to "value",
        "new_name" to "amount",
    )

    @Test
    fun `refuses a rename that would rewrite more files than the limit`() = runTest {
        val refactorer = refactorerWith(usageFiles = 40)

        val result = RenameSymbolTool(refactorer).execute(params)

        assertFalse(result.success)
        assertTrue(result.error?.contains("40 files") == true, "the refusal must state the real blast radius")
        coVerify(exactly = 0) { refactorer.renameSymbol(any(), any(), any(), any()) }
    }

    @Test
    fun `an explicit confirmation lets a wide rename through`() = runTest {
        val refactorer = refactorerWith(usageFiles = 40)

        val result = RenameSymbolTool(refactorer).execute(params + ("confirm_wide_rename" to true))

        assertTrue(result.success)
        coVerify(exactly = 1) { refactorer.renameSymbol(any(), any(), any(), any()) }
    }

    // The gate must not stand in the way of ordinary renames. It does cost a usage scan on every
    // unconfirmed call, which is a second walk over the project; that is the price of knowing the
    // blast radius before writing, and it is paid only by renames the caller has not vetted.
    @Test
    fun `an ordinary rename proceeds without confirmation`() = runTest {
        val refactorer = refactorerWith(usageFiles = 3)

        val result = RenameSymbolTool(refactorer).execute(params)

        assertTrue(result.success)
        coVerify(exactly = 1) { refactorer.findUsages("value") }
        coVerify(exactly = 1) { refactorer.renameSymbol(any(), any(), any(), any()) }
    }

    // A confirmed rename skips the scan entirely - the caller already reviewed the list.
    @Test
    fun `a confirmed rename does not pay for the usage scan`() = runTest {
        val refactorer = refactorerWith(usageFiles = 3)

        RenameSymbolTool(refactorer).execute(params + ("confirm_wide_rename" to true))

        coVerify(exactly = 0) { refactorer.findUsages(any()) }
    }
}
