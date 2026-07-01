package pl.jclab.refio.ui.components.autocomplete

import pl.jclab.refio.api.models.ContextReference
import pl.jclab.refio.api.models.ContextType
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [ContextValidator] - the guardrail that stops oversized context from being sent to the
 * LLM (cost/context-overflow protection). The boundary comparisons (single-file vs aggregate caps,
 * the 80% "approaching" warning, and the "unresolved reference is always valid" short-circuit) are
 * exactly the kind of logic that regresses silently, so they are pinned here.
 */
class ContextValidatorTest {

    private fun file(
        sizeBytes: Long = 0,
        tokens: Int = 0,
        content: String? = "resolved",
        name: String = "F.kt"
    ) = ContextReference(
        type = ContextType.FILE,
        path = "src/$name",
        displayName = name,
        content = content,
        sizeBytes = sizeBytes,
        estimatedTokens = tokens
    )

    private fun folder(depth: Int) = ContextReference(
        type = ContextType.FOLDER,
        path = "src",
        displayName = "src",
        content = "resolved",
        sizeBytes = 10,
        estimatedTokens = 10,
        metadata = mapOf("depth" to depth)
    )

    @Test
    fun `an unresolved reference is always valid regardless of declared size`() {
        // content == null means the reference isn't loaded yet; size is validated at send time.
        val result = ContextValidator.validateSingle(file(sizeBytes = 10_000_000, tokens = 999_999, content = null))
        assertTrue(result.isValid)
    }

    @Test
    fun `a single file over the byte cap is rejected`() {
        assertFalse(ContextValidator.validateSingle(file(sizeBytes = ContextValidator.MAX_SINGLE_FILE_SIZE_BYTES + 1)).isValid)
    }

    @Test
    fun `a single file over the token cap is rejected`() {
        assertFalse(ContextValidator.validateSingle(file(tokens = ContextValidator.MAX_SINGLE_FILE_TOKENS + 1)).isValid)
    }

    @Test
    fun `a file past half the byte cap is valid but warns`() {
        val result = ContextValidator.validateSingle(file(sizeBytes = ContextValidator.MAX_SINGLE_FILE_SIZE_BYTES / 2 + 1))
        assertTrue(result.isValid)
        assertTrue(result.warnings.isNotEmpty(), "a large-but-allowed file should warn")
    }

    @Test
    fun `a small file is valid with no warnings`() {
        val result = ContextValidator.validateSingle(file(sizeBytes = 1024, tokens = 100))
        assertTrue(result.isValid)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `folder depth beyond the maximum is rejected, at the maximum is allowed`() {
        assertFalse(ContextValidator.validateSingle(folder(depth = ContextValidator.MAX_FOLDER_DEPTH + 1)).isValid)
        assertTrue(ContextValidator.validateSingle(folder(depth = ContextValidator.MAX_FOLDER_DEPTH)).isValid)
    }

    @Test
    fun `aggregate byte cap is enforced even when each file is individually allowed`() {
        // Six 90KB files each pass the 100KB single-file cap but together exceed the 500KB total.
        val refs = List(6) { file(sizeBytes = 90 * 1024, name = "F$it.kt") }
        assertFalse(ContextValidator.validateList(refs).isValid)
    }

    @Test
    fun `aggregate token cap is enforced even when each file is individually allowed`() {
        // Six files at 24k tokens each pass the 25k single cap but together exceed the 125k total.
        val refs = List(6) { file(tokens = 24_000, name = "F$it.kt") }
        assertFalse(ContextValidator.validateList(refs).isValid)
    }

    @Test
    fun `approaching the total size limit produces a warning but stays valid`() {
        // Five 90KB files = 450KB: over 80% of 500KB, under the hard cap.
        val refs = List(5) { file(sizeBytes = 90 * 1024, name = "F$it.kt") }
        val result = ContextValidator.validateList(refs)
        assertTrue(result.isValid)
        assertTrue(result.warnings.any { it.contains("Approaching total size") }, "should warn near the aggregate cap")
    }
}
