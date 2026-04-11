package pl.jclab.refio.core.services.context

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.config.ConfigKey
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.models.context.*
import pl.jclab.refio.core.services.ConfigService
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [ContextFormatter] focused on Bug 2B —
 * the CONVERSATION section must be governed by the token budget, not by a
 * hardcoded maxMessages ceiling.
 *
 * Regression context: in the observed AGENT session the CONVERSATION budget
 * was 58 491 tokens but only 1 884 tokens were ever used because the formatter
 * capped the number of rendered messages at 25–100 regardless of budget. With
 * 82 very short messages in history that meant ~25 were shown and the rest of
 * the budget (~56 k) was wasted. These tests lock in the new behaviour.
 */
class ContextFormatterTest {

    private lateinit var configService: ConfigService
    private lateinit var formatter: ContextFormatter

    @BeforeEach
    fun setUp() {
        configService = mockk(relaxed = true)
        // getTyped is called for RECENT_WORK config only, not relevant for these tests,
        // but mockk needs reasonable defaults so calls don't throw.
        every { configService.getTyped<Any>(any<ConfigKey<Any>>()) } answers {
            val key = firstArg<ConfigKey<Any>>()
            when (key) {
                ConfigKeys.RECENT_WORK_SUMMARY_MAX_LENGTH -> 300
                ConfigKeys.RECENT_WORK_FULL_DATA_LIMIT -> 2
                else -> 300
            }
        }
        formatter = ContextFormatter(configService)
    }

    private fun message(id: Int, role: String, content: String) = ConversationMessageDTO(
        id = "m$id",
        role = role,
        content = content,
        createdAt = Instant.ofEpochMilli(id.toLong())
    )

    private fun contextWith(messages: List<ConversationMessageDTO>) = ProjectContextDTO(
        metaData = MetaDataDTO(projectName = "TestProject"),
        summary = SummaryDTO(projectType = "JVM", mainLanguage = "Kotlin"),
        structure = StructureDTO(totalFiles = 10),
        dependencies = DependenciesDTO(),
        codeAnalysis = CodeAnalysisDTO(),
        workspace = WorkspaceDTO(path = "/test/project"),
        executionMetadata = ExecutionMetadataDTO(),
        projectType = "JVM",
        conversationHistory = messages,
        contextGeneratedAt = Instant.now(),
        analyzerVersion = "test-v1"
    )

    // Count how many `[ROLE]` headers appear in the rendered section — each rendered
    // message gets exactly one, regardless of multi-line content.
    private fun countRenderedMessages(section: String): Int =
        Regex("^\\[(?:USER|ASSISTANT|TOOL|SYSTEM)]$", RegexOption.MULTILINE)
            .findAll(section).count()

    @Test
    fun `returns empty string for empty budget`() {
        val ctx = contextWith(listOf(message(1, "user", "hello")))
        val section = formatter.buildCompressedConversationSection(ctx, budgetTokens = 0)
        assertTrue(section.isEmpty())
    }

    @Test
    fun `returns empty string for empty conversation`() {
        val ctx = contextWith(emptyList())
        val section = formatter.buildCompressedConversationSection(ctx, budgetTokens = 10_000)
        assertTrue(section.isEmpty())
    }

    @Test
    fun `renders all messages when budget comfortably fits them`() {
        // 10 short messages, large budget → everything must appear, in order.
        val messages = (1..10).map { i ->
            message(i, if (i % 2 == 0) "assistant" else "user", "short msg $i")
        }
        val section = formatter.buildCompressedConversationSection(contextWith(messages), budgetTokens = 10_000)

        assertTrue(section.contains("<CONVERSATION_HISTORY>"))
        assertTrue(section.contains("</CONVERSATION_HISTORY>"))
        assertEquals(10, countRenderedMessages(section), "All 10 messages must fit into a 10k budget")
        // Order check: msg 1 must appear before msg 10 in the rendered text.
        assertTrue(section.indexOf("short msg 1") < section.indexOf("short msg 10"))
    }

    @Test
    fun `Bug 2B regression - large budget with many short messages renders far more than 25`() {
        // This is the exact failure mode from the filesystem AGENT session: ~80 short
        // messages in history, a CONVERSATION budget of ~58k tokens, the OLD code
        // capped at 25 messages regardless of budget. The NEW code must render ALL
        // of them (or at least much more than 25) because the token budget is huge.
        val messages = (1..80).map { i ->
            message(i, if (i % 2 == 0) "assistant" else "user", "message number $i")
        }
        val section = formatter.buildCompressedConversationSection(contextWith(messages), budgetTokens = 58_000)

        val rendered = countRenderedMessages(section)
        assertTrue(
            rendered >= 80,
            "With a 58k budget and 80 short messages, ALL should fit (got $rendered). " +
                "This is the Bug 2B regression — previous impl capped at maxMessages=100 " +
                "but because of per-message token caps, typically only 25 were rendered."
        )
        // The oldest message must still be present (no forced newest-N drop).
        assertTrue(section.contains("message number 1"), "Oldest message must not be dropped")
        assertTrue(section.contains("message number 80"), "Newest message must not be dropped")
    }

    @Test
    fun `newest messages win when budget is tight`() {
        // Many messages, tight budget → only the most recent subset fits. Each message
        // uses ~60 characters of padding so that ~10 messages fit in a ~150-token budget
        // rather than all 50 (which would happen with one-word content). Unique tokens
        // `#NN#` prevent substring overlap (so "line#1#" does NOT match "line#10#").
        val padding = "x".repeat(60)
        val messages = (1..50).map { i -> message(i, "user", "line#${i}#end $padding") }
        val section = formatter.buildCompressedConversationSection(contextWith(messages), budgetTokens = 150)

        val rendered = countRenderedMessages(section)
        assertTrue(rendered in 1..49, "Some messages must be dropped under a tight budget (got $rendered)")
        // The NEWEST message must still be present, and the VERY oldest must be gone.
        // This ordering is essential: the model must see recent context.
        assertTrue(section.contains("line#50#end"), "Newest message must always be kept under tight budget")
        assertFalse(section.contains("line#1#end"), "Oldest message must be dropped when budget is exhausted")
    }

    @Test
    fun `kept messages remain in chronological order`() {
        // Reverse-iteration internal implementation detail should NOT leak to output.
        val messages = (1..30).map { i -> message(i, if (i % 2 == 0) "assistant" else "user", "line-$i") }
        val section = formatter.buildCompressedConversationSection(contextWith(messages), budgetTokens = 5_000)

        // Compare index positions for 3 distinct messages — must be strictly ascending.
        val idxOfFirst = section.indexOf("line-1")
        val idxOfMid = section.indexOf("line-15")
        val idxOfLast = section.indexOf("line-30")
        assertTrue(idxOfFirst >= 0 && idxOfMid >= 0 && idxOfLast >= 0, "All three messages must be present")
        assertTrue(idxOfFirst < idxOfMid && idxOfMid < idxOfLast, "Chronological order must be preserved")
    }

    @Test
    fun `long single message is truncated per-message to not eat the whole budget`() {
        // A single 10k-token message in a 2k budget must be truncated rather than consuming
        // everything. The rest of the messages should still appear.
        val hugeContent = "x".repeat(40_000)  // ~10k tokens
        val messages = listOf(
            message(1, "user", "short-a"),
            message(2, "assistant", hugeContent),
            message(3, "user", "short-b")
        )
        val section = formatter.buildCompressedConversationSection(contextWith(messages), budgetTokens = 2_000)

        // Must not contain the full huge payload (it should be truncated).
        assertFalse(
            section.contains("x".repeat(20_000)),
            "Per-message truncation must prevent one huge message from leaking the whole thing"
        )
    }
}
