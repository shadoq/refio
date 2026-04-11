package pl.jclab.refio.core.services.context

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkingMemoryServiceTest {

    @Test
    fun `recordEntries should evict least important entries over limit`() {
        val service = WorkingMemoryService(maxEntriesPerTask = 2)

        service.recordEntries(
            "task-1",
            listOf(
                WorkingMemoryEntry(iteration = 1, key = "facts", value = "low", importance = 1),
                WorkingMemoryEntry(iteration = 1, key = "facts", value = "high", importance = 10),
                WorkingMemoryEntry(iteration = 1, key = "facts", value = "medium", importance = 5)
            )
        )

        val section = service.buildWorkingMemorySection("task-1", maxTokens = 200)

        assertTrue(section.contains("high"))
        assertTrue(section.contains("medium"))
        assertFalse(section.contains("low"))
    }

    @Test
    fun `buildWorkingMemorySection should prefer recently accessed entries with same importance`() {
        val service = WorkingMemoryService(maxEntriesPerTask = 2)
        val older = Instant.parse("2024-01-01T00:00:00Z")
        val newer = Instant.parse("2024-01-02T00:00:00Z")

        service.recordEntries(
            "task-2",
            listOf(
                WorkingMemoryEntry(iteration = 1, key = "files", value = "older", importance = 5, timestamp = older, lastAccessedAt = older),
                WorkingMemoryEntry(iteration = 1, key = "files", value = "newer", importance = 5, timestamp = newer, lastAccessedAt = newer)
            )
        )

        val section = service.buildWorkingMemorySection("task-2", maxTokens = 40)

        assertTrue(section.contains("newer"))
    }

    // ── Duplication consolidation regression tests ──────────────────────────
    // Previously extractKnowledge("run_code", ...) returned TWO WorkingMemoryEntry
    // objects — one keyed `code_execution` and one keyed `analysis_results` — both
    // carrying the same `outputExcerpt`. Same pattern for http_request / run_terminal_command
    // / invoke_subagent. For every tool call the model saw the same data twice in
    // WORKING_MEMORY and (if the call was also in the RECENT_WORK window) a third time
    // in RECENT_WORK. The consolidation merges metadata + facts into a single entry
    // and keeps only ONE outputExcerpt per tool call.

    private val service = WorkingMemoryService()

    @Test
    fun `extractKnowledge run_code returns single consolidated entry`() {
        val output = """
            Status: 200
            Result: ok
            Total actions: 10
        """.trimIndent()

        val entries = service.extractKnowledge(
            toolName = "run_code",
            args = mapOf("language" to "python", "code" to "print(42)"),
            output = output,
            iteration = 1,
            metadata = mapOf("language" to "python", "exit_code" to 0, "output_length" to output.length)
        )

        assertEquals(1, entries.size, "run_code must produce exactly one WM entry (was 2 before consolidation)")
        val entry = entries[0]
        assertEquals("code_execution", entry.key)
        // The consolidated value must contain both the metadata line and the extracted facts.
        assertTrue(entry.value.contains("run_code python"), "metadata line missing")
        assertTrue(entry.value.contains("facts:"), "facts must be appended to single entry")
        assertTrue(entry.value.contains("Status: 200"), "actual fact text must appear in value")
    }

    @Test
    fun `extractKnowledge run_code without facts still produces single entry`() {
        // Output that has no structured facts (no `:` or `=` patterns) — make sure
        // we still get exactly one entry and the value contains just the metadata.
        val entries = service.extractKnowledge(
            toolName = "run_code",
            args = mapOf("language" to "python", "code" to "pass"),
            output = "Hello world",
            iteration = 1,
            metadata = mapOf("language" to "python")
        )

        assertEquals(1, entries.size)
        assertEquals("code_execution", entries[0].key)
        assertFalse(entries[0].value.contains("facts:"), "no facts → no 'facts:' tag")
    }

    @Test
    fun `extractKnowledge http_request returns single network entry plus optional api_failure`() {
        val output = """
            HTTP 400 Bad Request
            {"code": -807, "message": "unexpected goods"}
        """.trimIndent()

        val entries = service.extractKnowledge(
            toolName = "http_request",
            args = mapOf("url" to "https://api.example.com/verify", "method" to "POST"),
            output = output,
            iteration = 5,
            metadata = mapOf(
                "url" to "https://api.example.com/verify",
                "method" to "POST",
                "status_code" to 400
            )
        )

        // Should produce: 1 `network` (consolidated) + 1 `api_failure` (separate, importance=10).
        assertEquals(2, entries.size, "http_request 4xx should produce network + api_failure")
        val networkEntry = entries.first { it.key == "network" }
        val failureEntry = entries.first { it.key == "api_failure" }
        assertTrue(networkEntry.value.contains("http_request POST"))
        assertEquals(10, failureEntry.importance, "api_failure must stay pinned at importance=10")
    }

    @Test
    fun `extractKnowledge http_request success returns single network entry`() {
        val entries = service.extractKnowledge(
            toolName = "http_request",
            args = mapOf("url" to "https://api.example.com/", "method" to "GET"),
            output = "Status: 200\n{\"ok\": true}",
            iteration = 1,
            metadata = mapOf(
                "url" to "https://api.example.com/",
                "method" to "GET",
                "status_code" to 200
            )
        )

        assertEquals(1, entries.size, "Successful http_request must produce ONE consolidated network entry")
        assertEquals("network", entries[0].key)
    }

    @Test
    fun `extractKnowledge run_terminal_command returns single entry`() {
        val entries = service.extractKnowledge(
            toolName = "run_terminal_command",
            args = mapOf("command" to "ls -la"),
            output = "total 4\nfile1.txt\nfile2.txt",
            iteration = 1,
            metadata = mapOf("command" to "ls -la", "exit_code" to 0)
        )

        assertEquals(1, entries.size, "run_terminal_command must produce ONE consolidated entry")
        assertEquals("command_execution", entries[0].key)
    }

    @Test
    fun `extractKnowledge invoke_subagent returns single entry`() {
        val entries = service.extractKnowledge(
            toolName = "invoke_subagent",
            args = mapOf("subagent_name" to "code-reviewer"),
            output = "Review done. Score: 8/10",
            iteration = 2,
            metadata = mapOf("subagent_name" to "code-reviewer", "depth" to 1, "iterations" to 5)
        )

        assertEquals(1, entries.size, "invoke_subagent must produce ONE consolidated entry")
        assertEquals("subagent_work", entries[0].key)
    }

    // ── skipExcerptForOriginIds (RECENT_WORK de-duplication) ────────────────────

    @Test
    fun `buildWorkingMemorySection skips outputExcerpt for entries in RECENT_WORK window`() {
        val svc = WorkingMemoryService()
        val distinctOutput = "MARKER_HEAD unique excerpt that must disappear when skipped"
        svc.recordEntries(
            "task-skip",
            listOf(
                WorkingMemoryEntry(
                    iteration = 1,
                    key = "code_execution",
                    value = "run_code python: exit=0",
                    outputExcerpt = distinctOutput,
                    importance = 8,
                    originId = "subtask-abc"
                )
            )
        )

        val withoutSkip = svc.buildWorkingMemorySection("task-skip", maxTokens = 500)
        assertTrue(
            withoutSkip.contains("MARKER_HEAD"),
            "Baseline: excerpt must be included when not skipped"
        )

        val withSkip = svc.buildWorkingMemorySection(
            taskId = "task-skip",
            maxTokens = 500,
            skipExcerptForOriginIds = setOf("subtask-abc")
        )
        assertFalse(
            withSkip.contains("MARKER_HEAD"),
            "Excerpt must be hidden when the entry's originId is in RECENT_WORK window"
        )
        // The metadata line MUST still be present — the entry itself isn't dropped, only its excerpt is.
        assertTrue(withSkip.contains("run_code python"), "Metadata line must still appear")
    }

    @Test
    fun `buildWorkingMemorySection keeps excerpt for api_failure even when in RECENT_WORK window`() {
        // api_failure is the one exception: the failure message carries the server's
        // rejection reason and we want the model to see it EVERY turn the agent tries
        // to retry, even if the tool call is technically in the recent window.
        val svc = WorkingMemoryService()
        svc.recordEntries(
            "task-fail",
            listOf(
                WorkingMemoryEntry(
                    iteration = 1,
                    key = "api_failure",
                    value = "FAILED POST api.example.com: status=400",
                    outputExcerpt = "PIN_MARKER_HEAD server rejected body because X",
                    importance = 10,
                    originId = "subtask-fail"
                )
            )
        )

        val section = svc.buildWorkingMemorySection(
            taskId = "task-fail",
            maxTokens = 500,
            skipExcerptForOriginIds = setOf("subtask-fail")
        )
        assertTrue(
            section.contains("PIN_MARKER_HEAD"),
            "api_failure excerpt must survive skip because importance=10 and key=api_failure"
        )
    }

    @Test
    fun `buildWorkingMemorySection keeps excerpt for entries whose originId is NOT in skip set`() {
        val svc = WorkingMemoryService()
        svc.recordEntries(
            "task-keep",
            listOf(
                WorkingMemoryEntry(
                    iteration = 1,
                    key = "code_execution",
                    value = "run_code python",
                    outputExcerpt = "KEEP_MARKER unique tail",
                    importance = 8,
                    originId = "subtask-NOT-in-recent"
                )
            )
        )

        val section = svc.buildWorkingMemorySection(
            taskId = "task-keep",
            maxTokens = 500,
            skipExcerptForOriginIds = setOf("subtask-something-else")
        )
        assertTrue(
            section.contains("KEEP_MARKER"),
            "Excerpt must be kept when the entry's originId is NOT in the skip set"
        )
    }

    // ── read_file pinning regression tests ─────────────────────────────────

    @Test
    fun `extractKnowledge read_file data file gets higher importance and data_files_read key`() {
        val notesOutput = (1..20).joinToString("\n") { "line $it: fact number $it" }
        val entries = service.extractKnowledge(
            toolName = "read_file",
            args = mapOf("path" to "rozmowy.txt"),
            output = notesOutput,
            iteration = 1,
            metadata = mapOf("path" to "rozmowy.txt", "lines_read" to 20)
        )

        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals(
            "data_files_read",
            entry.key,
            "Data files (.txt) must land under `data_files_read` key, not `files_read`"
        )
        assertEquals(
            9,
            entry.importance,
            "Data-file read_file must be pinned at importance=9 (above regular code=7)"
        )
    }

    @Test
    fun `extractKnowledge read_file code file stays at default importance`() {
        val entries = service.extractKnowledge(
            toolName = "read_file",
            args = mapOf("path" to "src/main/kotlin/Foo.kt"),
            output = "class Foo { fun bar() {} }",
            iteration = 1,
            metadata = mapOf("path" to "src/main/kotlin/Foo.kt", "lines_read" to 1)
        )

        assertEquals(1, entries.size)
        val entry = entries[0]
        assertEquals("files_read", entry.key, "Code files stay on the default `files_read` key")
        assertEquals(7, entry.importance, "Code file importance must remain at default=7")
    }

    @Test
    fun `extractKnowledge read_file data file uses larger excerpt budget`() {
        // A data file with 30 short lines should produce a longer outputExcerpt than
        // the same length of content in a code file, because the pinning path uses a
        // bigger char budget (DATA_FILE_EXCERPT_CHARS ≈ 1200 vs default 220).
        val manyLines = (1..30).joinToString("\n") { "DATALINE$it: value=$it" }
        val dataEntries = service.extractKnowledge(
            toolName = "read_file",
            args = mapOf("path" to "notes.md"),
            output = manyLines,
            iteration = 1,
            metadata = mapOf("path" to "notes.md", "lines_read" to 30)
        )
        val codeEntries = service.extractKnowledge(
            toolName = "read_file",
            args = mapOf("path" to "Code.kt"),
            output = manyLines,
            iteration = 1,
            metadata = mapOf("path" to "Code.kt", "lines_read" to 30)
        )

        val dataExcerpt = dataEntries[0].outputExcerpt.orEmpty()
        val codeExcerpt = codeEntries[0].outputExcerpt.orEmpty()

        assertTrue(
            dataExcerpt.length > codeExcerpt.length,
            "Data-file excerpt should be longer (${dataExcerpt.length}) than code-file excerpt (${codeExcerpt.length})"
        )
        // Sanity: the data-file excerpt should contain lines from beyond the default
        // 8-line cap of the code path, proving the bigger budget is in effect.
        assertTrue(
            dataExcerpt.contains("DATALINE20"),
            "Data-file excerpt must contain lines past the 8-line code cap, got: $dataExcerpt"
        )
    }

    @Test
    fun `extractKnowledge read_file data file excerpt is still capped under large content`() {
        // A 200-line file must NOT let WORKING_MEMORY explode — the excerpt is still
        // capped at DATA_FILE_EXCERPT_CHARS.
        val bigContent = (1..200).joinToString("\n") { "row-$it with some padding text to make it longer than one word" }
        val entries = service.extractKnowledge(
            toolName = "read_file",
            args = mapOf("path" to "big.txt"),
            output = bigContent,
            iteration = 1,
            metadata = mapOf("path" to "big.txt", "lines_read" to 200)
        )
        val excerpt = entries[0].outputExcerpt.orEmpty()
        assertTrue(
            excerpt.length <= 1_200,
            "Data-file excerpt must be hard-capped (was ${excerpt.length} chars)"
        )
    }
}
