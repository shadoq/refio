package pl.jclab.refio.core.services.turn

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TurnGuardrailsTest {

    @Nested
    inner class ToolErrorTrackerTest {

        @Test
        fun `should return zero error rate when empty`() {
            val tracker = TurnGuardrails.ToolErrorTracker(windowSize = 10)
            assertEquals(0.0, tracker.getErrorRate())
        }

        @Test
        fun `should track error rate correctly`() {
            val tracker = TurnGuardrails.ToolErrorTracker(windowSize = 10)
            repeat(8) { tracker.recordResult(false) }
            repeat(2) { tracker.recordResult(true) }
            assertEquals(0.8, tracker.getErrorRate())
        }

        @Test
        fun `should not abort when not enough data`() {
            val tracker = TurnGuardrails.ToolErrorTracker(windowSize = 10)
            repeat(4) { tracker.recordResult(false) }
            // 100% error rate but only 4 samples (need >= 5)
            assertFalse(tracker.shouldAbort())
        }

        @Test
        fun `should abort when error rate exceeds threshold with enough data`() {
            val tracker = TurnGuardrails.ToolErrorTracker(windowSize = 10)
            repeat(5) { tracker.recordResult(false) }
            // 100% error rate with 5 samples
            assertTrue(tracker.shouldAbort())
        }

        @Test
        fun `should not abort when error rate is below threshold`() {
            val tracker = TurnGuardrails.ToolErrorTracker(windowSize = 10)
            repeat(3) { tracker.recordResult(false) }
            repeat(7) { tracker.recordResult(true) }
            assertFalse(tracker.shouldAbort())
        }

        @Test
        fun `should use sliding window and evict old results`() {
            val tracker = TurnGuardrails.ToolErrorTracker(windowSize = 5)
            repeat(5) { tracker.recordResult(false) }
            assertEquals(1.0, tracker.getErrorRate())
            repeat(5) { tracker.recordResult(true) }
            assertEquals(0.0, tracker.getErrorRate())
        }

        @Test
        fun `should support custom threshold`() {
            val tracker = TurnGuardrails.ToolErrorTracker(windowSize = 10)
            repeat(5) { tracker.recordResult(false) }
            repeat(5) { tracker.recordResult(true) }
            assertFalse(tracker.shouldAbort(threshold = 0.7))
            assertTrue(tracker.shouldAbort(threshold = 0.4))
        }

        @Test
        fun `should return formatted stats`() {
            val tracker = TurnGuardrails.ToolErrorTracker(windowSize = 10)
            repeat(3) { tracker.recordResult(false) }
            repeat(7) { tracker.recordResult(true) }
            val stats = tracker.getStats()
            assertTrue(stats.contains("3/10"))
        }
    }

    @Nested
    inner class TurnRepetitionTrackerTest {

        // ─── Effect-key isolation ──────────────────────────────────────────

        @Test
        fun `same url with different bodies should not trip the loop`() {
            // Regression: 7 legitimate game-API calls (different actions, same /verify
            // endpoint) must not collapse into one effect key.
            val tracker = TurnGuardrails.TurnRepetitionTracker()
            val url = "https://hub.ag3nts.org/verify"
            val actions = listOf("help", "getMap", "create", "move", "dismount", "inspect", "callHelicopter")
            for (action in actions) {
                val args = mapOf<String, Any?>(
                    "url" to url,
                    "method" to "POST",
                    "body" to """{"apikey":"k","task":"domatowo","answer":{"action":"$action"}}"""
                )
                val status = tracker.record("http_request", args)
                assertIs<TurnGuardrails.LoopStatus.OK>(
                    status,
                    "Expected OK for distinct action '$action', got $status"
                )
            }
        }

        @Test
        fun `body as Map vs equivalent body as String share the same key`() {
            // After the HttpRequestTool body-coercion fix, the LLM may pass the body
            // either as a String or as a Map. Both must land in the same effect key.
            val tracker = TurnGuardrails.TurnRepetitionTracker()
            val mapBody = linkedMapOf("a" to "b")
            val stringBody = mapBody.toString() // same .toString() => same hash
            val argsMap = mapOf<String, Any?>("url" to "https://x", "body" to mapBody)
            val argsStr = mapOf<String, Any?>("url" to "https://x", "body" to stringBody)
            tracker.record("http_request", argsMap)
            tracker.record("http_request", argsStr)
            val stats = tracker.stats()
            assertTrue(stats.contains("=2"), "Expected merged key count, stats=$stats")
        }

        @Test
        fun `many varied calls without identical output stay OK`() {
            // ~20 varied http_request turns — different bodies → different keys,
            // no output passed → only the count side could ever trigger and
            // count-based abort was removed.
            val tracker = TurnGuardrails.TurnRepetitionTracker()
            val url = "https://hub.ag3nts.org/verify"
            for (i in 1..20) {
                val args = mapOf<String, Any?>(
                    "url" to url,
                    "method" to "POST",
                    "body" to """{"action":"step$i"}"""
                )
                val status = tracker.record("http_request", args)
                assertIs<TurnGuardrails.LoopStatus.OK>(
                    status,
                    "Should stay OK on varied call $i, got $status"
                )
            }
        }

        @Test
        fun `non-tracked tool returns OK indefinitely`() {
            // `think` and `memory` stay un-tracked — their repetition is by design
            // (no-op reasoning slot, cross-turn state store). Read-only exploration
            // tools (read_file, rag_search, etc.) ARE tracked — see dedicated tests.
            val tracker = TurnGuardrails.TurnRepetitionTracker()
            val args = mapOf<String, Any?>("thought" to "considering options")
            repeat(10) {
                val status = tracker.record("think", args)
                assertIs<TurnGuardrails.LoopStatus.OK>(status)
            }
        }

        @Test
        fun `rag_search with identical query and output aborts at output threshold`() {
            // Test 4 pathology: weak model re-issues the same rag_search query 5×
            // with byte-identical results. identicalOutputAbortThreshold defaults to 4.
            val tracker = TurnGuardrails.TurnRepetitionTracker()
            val args = mapOf<String, Any?>(
                "query" to "auto compaction conversation context window",
                "content_type" to "PROJECT_CODE"
            )
            val output = "Found 3 fragment(s)\n--- [1] foo.kt ---\n..."
            val statuses = (1..4).map { tracker.record("rag_search", args, output) }
            assertIs<TurnGuardrails.LoopStatus.OK>(statuses[0])
            assertIs<TurnGuardrails.LoopStatus.OK>(statuses[1])
            assertIs<TurnGuardrails.LoopStatus.OK>(statuses[2])
            assertIs<TurnGuardrails.LoopStatus.ABORT>(statuses[3])
        }

        @Test
        fun `rag_search varying query stays OK across many calls`() {
            // Legitimate exploration: different queries each time, default 15-call
            // count threshold is never approached, output hashes vary.
            val tracker = TurnGuardrails.TurnRepetitionTracker()
            repeat(8) { i ->
                val args = mapOf<String, Any?>("query" to "query number $i")
                val status = tracker.record("rag_search", args, "result for $i")
                assertIs<TurnGuardrails.LoopStatus.OK>(status)
            }
        }

        @Test
        fun `read_file identical reads abort at output threshold`() {
            val tracker = TurnGuardrails.TurnRepetitionTracker()
            val args = mapOf<String, Any?>("path" to "src/Main.kt")
            val output = "package x\nclass Main { fun main() = Unit }\n"
            val statuses = (1..4).map { tracker.record("read_file", args, output) }
            assertIs<TurnGuardrails.LoopStatus.OK>(statuses[0])
            assertIs<TurnGuardrails.LoopStatus.OK>(statuses[1])
            assertIs<TurnGuardrails.LoopStatus.OK>(statuses[2])
            assertIs<TurnGuardrails.LoopStatus.ABORT>(statuses[3])
        }

        @Test
        fun `read_file across different paths stays OK`() {
            val tracker = TurnGuardrails.TurnRepetitionTracker()
            repeat(10) { i ->
                val args = mapOf<String, Any?>("path" to "src/File$i.kt")
                val status = tracker.record("read_file", args, "content $i")
                assertIs<TurnGuardrails.LoopStatus.OK>(status)
            }
        }

        @Test
        fun `grep_search uses pattern+path as effect key`() {
            val tracker = TurnGuardrails.TurnRepetitionTracker()
            // Same pattern + path with identical output → abort at 4th
            val args = mapOf<String, Any?>("pattern" to "TODO", "path" to "src")
            val output = "no matches"
            val statuses = (1..4).map { tracker.record("grep_search", args, output) }
            assertIs<TurnGuardrails.LoopStatus.ABORT>(statuses[3])
        }

        @Test
        fun `code_intelligence keyed by action plus target`() {
            val tracker = TurnGuardrails.TurnRepetitionTracker()
            val args = mapOf<String, Any?>("action" to "find_usages", "symbol" to "foo")
            val output = "no usages found"
            val statuses = (1..4).map { tracker.record("code_intelligence", args, output) }
            assertIs<TurnGuardrails.LoopStatus.ABORT>(statuses[3])
        }

        @Test
        fun `code editing on same path with no output stays OK`() {
            // Write tools typically don't pass output. Without count-based abort,
            // identical-arg repeats are NOT caught by the repetition tracker —
            // they fall to ToolErrorTracker (when the edits genuinely fail) or
            // to legitimate refactor work (when each call has a different effect).
            val tracker = TurnGuardrails.TurnRepetitionTracker()
            val args = mapOf<String, Any?>(
                "path" to "src/main.kt",
                "old_string" to "x",
                "new_string" to "y"
            )
            repeat(20) {
                val status = tracker.record("code_editing", args)
                assertIs<TurnGuardrails.LoopStatus.OK>(status)
            }
        }

        // ─── Output-hash abort ─────────────────────────────────────────────

        @Test
        fun `http_request with identical response aborts at threshold`() {
            val tracker = TurnGuardrails.TurnRepetitionTracker(identicalOutputAbortThreshold = 3)
            val args = mapOf<String, Any?>(
                "url" to "https://api.example.com/poll",
                "method" to "GET",
                "body" to null
            )
            val response = """{"status":"pending"}"""
            val s1 = tracker.record("http_request", args, response)
            val s2 = tracker.record("http_request", args, response)
            val s3 = tracker.record("http_request", args, response)
            assertIs<TurnGuardrails.LoopStatus.OK>(s1)
            assertIs<TurnGuardrails.LoopStatus.OK>(s2)
            assertIs<TurnGuardrails.LoopStatus.ABORT>(s3)
        }

        @Test
        fun `http_request with changing response stays OK`() {
            val tracker = TurnGuardrails.TurnRepetitionTracker(identicalOutputAbortThreshold = 3)
            val args = mapOf<String, Any?>(
                "url" to "https://api.example.com/poll",
                "method" to "GET"
            )
            val s1 = tracker.record("http_request", args, """{"value":1}""")
            val s2 = tracker.record("http_request", args, """{"value":2}""")
            val s3 = tracker.record("http_request", args, """{"value":3}""")
            assertIs<TurnGuardrails.LoopStatus.OK>(s1)
            assertIs<TurnGuardrails.LoopStatus.OK>(s2)
            assertIs<TurnGuardrails.LoopStatus.OK>(s3)
        }

        @Test
        fun `http_request with different bodies are tracked separately`() {
            // Different `body` fields → different keys → neither reaches threshold.
            val tracker = TurnGuardrails.TurnRepetitionTracker(identicalOutputAbortThreshold = 4)
            val baseArgs = mapOf<String, Any?>(
                "url" to "https://api.example.com/x",
                "method" to "POST"
            )
            val response = """{"ok":true}"""
            for (i in 1..3) {
                val argsA = baseArgs + ("body" to """{"action":"a"}""")
                val argsB = baseArgs + ("body" to """{"action":"b"}""")
                assertIs<TurnGuardrails.LoopStatus.OK>(tracker.record("http_request", argsA, response), "key A iter $i")
                assertIs<TurnGuardrails.LoopStatus.OK>(tracker.record("http_request", argsB, response), "key B iter $i")
            }
            // Sanity: piling one key past the threshold DOES abort.
            val argsA = baseArgs + ("body" to """{"action":"a"}""")
            assertIs<TurnGuardrails.LoopStatus.ABORT>(tracker.record("http_request", argsA, response))
        }

        // ── run_code key: language-only, NOT code-hash keyed ──────────────────

        @Test
        fun `run_code tracker detects identical tail across different scripts same language`() {
            val tracker = TurnGuardrails.TurnRepetitionTracker(identicalOutputAbortThreshold = 4)
            val script1 = "import requests\nrequests.post('https://api.x/verify', json={'answer': 'opalin'})"
            val script2 = "import requests\nrequests.post('https://api.x/verify', json={'answer': 'opalino'})"
            val script3 = "import requests\nrequests.post('https://api.x/verify', json={'answer': 'opalinx'})"
            val script4 = "import requests\nrequests.post('https://api.x/verify', json={'answer': 'opaliny'})"
            val identicalErrorTail =
                "HTTP 400\n{'code': -807, 'message': 'City JSON contains goods that are not required', 'unexpected_goods': ['city', 'items']}"

            val s1 = tracker.record("run_code", mapOf("language" to "python", "code" to script1), identicalErrorTail)
            val s2 = tracker.record("run_code", mapOf("language" to "python", "code" to script2), identicalErrorTail)
            val s3 = tracker.record("run_code", mapOf("language" to "python", "code" to script3), identicalErrorTail)
            val s4 = tracker.record("run_code", mapOf("language" to "python", "code" to script4), identicalErrorTail)

            assertIs<TurnGuardrails.LoopStatus.OK>(s1)
            assertIs<TurnGuardrails.LoopStatus.OK>(s2)
            assertIs<TurnGuardrails.LoopStatus.OK>(s3)
            assertIs<TurnGuardrails.LoopStatus.ABORT>(
                s4,
                "4th identical-tail run_code (with different scripts) must abort"
            )
        }

        @Test
        fun `run_code tracker resets counter on different tail`() {
            val tracker = TurnGuardrails.TurnRepetitionTracker(identicalOutputAbortThreshold = 3)
            val args = mapOf<String, Any?>("language" to "python", "code" to "print(1)")

            tracker.record("run_code", args, "error A")
            tracker.record("run_code", args, "error A")
            // Different tail → counter restarts at 1.
            assertIs<TurnGuardrails.LoopStatus.OK>(tracker.record("run_code", args, "error B"))
            assertIs<TurnGuardrails.LoopStatus.OK>(tracker.record("run_code", args, "error B"))
            assertIs<TurnGuardrails.LoopStatus.ABORT>(tracker.record("run_code", args, "error B"))
        }

        // ─── Content-chanting tests live in their own nested class below.

        @Test
        fun `run_code tracker separates languages`() {
            val tracker = TurnGuardrails.TurnRepetitionTracker(identicalOutputAbortThreshold = 3)
            val py = mapOf<String, Any?>("language" to "python", "code" to "x")
            val kt = mapOf<String, Any?>("language" to "kotlin", "code" to "y")
            val sameTail = "NullPointerException at line 42"

            assertIs<TurnGuardrails.LoopStatus.OK>(tracker.record("run_code", py, sameTail))
            assertIs<TurnGuardrails.LoopStatus.OK>(tracker.record("run_code", kt, sameTail))
            assertIs<TurnGuardrails.LoopStatus.OK>(tracker.record("run_code", py, sameTail))
            // py=2 identical, kt=1 — nothing aborts yet.
            assertIs<TurnGuardrails.LoopStatus.OK>(tracker.record("run_code", kt, sameTail))
            // py bucket gets 3rd identical → abort (threshold is 3).
            assertIs<TurnGuardrails.LoopStatus.ABORT>(tracker.record("run_code", py, sameTail))
        }
    }

    @Nested
    inner class ContentChantingDetectorTest {

        @Test
        fun `short content passes`() {
            // Below the size floor — can't reach threshold even if entirely a chant.
            val short = "hello hello hello hello"
            assertIs<TurnGuardrails.LoopStatus.OK>(
                TurnGuardrails.ContentChantingDetector.inspect(short)
            )
        }

        @Test
        fun `normal prose passes`() {
            val prose = """
                The user asked me to analyze the file structure. I read the main entry point
                and found three modules. The first module handles authentication, the second
                module provides the API layer, and the third manages the database connection.
                Based on this review, I recommend extracting the auth logic into a separate
                package for testability. The current coupling makes unit tests difficult.
            """.trimIndent()
            assertIs<TurnGuardrails.LoopStatus.OK>(
                TurnGuardrails.ContentChantingDetector.inspect(prose)
            )
        }

        @Test
        fun `chant of identical sentence aborts`() {
            // 30× identical 60-char sentence — clear pathology.
            val chant = ("I will check the file. ".repeat(40)).trim()
            val status = TurnGuardrails.ContentChantingDetector.inspect(chant)
            assertIs<TurnGuardrails.LoopStatus.ABORT>(status)
            assertTrue(status.reason.contains("Content chanting detected"))
        }

        @Test
        fun `enumeration with varied items passes`() {
            // Tens of bullet points, each slightly different — legitimate output, not a chant.
            val list = (1..30).joinToString("\n") { i ->
                "- Item $i: this is a unique description about item number $i that varies in content"
            }
            assertIs<TurnGuardrails.LoopStatus.OK>(
                TurnGuardrails.ContentChantingDetector.inspect(list)
            )
        }

        @Test
        fun `code-style legitimate repeats pass`() {
            // Common Kotlin pattern that produces some hash collisions but stays below threshold.
            val code = (1..8).joinToString("\n\n") { i ->
                "fun method$i(param: String): Int {\n    return param.length + $i\n}"
            }
            assertIs<TurnGuardrails.LoopStatus.OK>(
                TurnGuardrails.ContentChantingDetector.inspect(code)
            )
        }

        @Test
        fun `chant report includes sample phrase`() {
            val phrase = "Let me check the configuration file now. "
            val chant = phrase.repeat(20)
            val status = TurnGuardrails.ContentChantingDetector.inspect(chant)
            assertIs<TurnGuardrails.LoopStatus.ABORT>(status)
            // The sample should contain part of the repeated phrase for diagnostic value.
            assertTrue(
                status.reason.contains("check the configuration"),
                "Reason should include sample of repeated phrase, got: ${status.reason}"
            )
        }
    }
}
