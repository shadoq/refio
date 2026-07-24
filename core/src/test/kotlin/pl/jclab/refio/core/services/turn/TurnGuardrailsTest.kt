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
            // Order matters since the recovery guard also checks the LAST result: end on a failure so
            // this test exercises the threshold comparison, not the recovery clause. Rate stays 0.5.
            repeat(5) { tracker.recordResult(true) }
            repeat(5) { tracker.recordResult(false) }
            assertFalse(tracker.shouldAbort(threshold = 0.7))
            assertTrue(tracker.shouldAbort(threshold = 0.4))
        }

        // ─── Recovery guard (Fix #3) ───────────────────────────────────────
        // The error guard used to abort purely on rate + sample count, ignoring whether the agent
        // had just recovered. In a real run (2026-07) three subagent failures + one delegate failure
        // + one SUCCESSFUL `tasks` plan gave 4/5 = 80% and the turn was killed WITHOUT ever executing
        // the plan — "the process never reached the end". shouldAbort now additionally requires the
        // most recent call to be a failure.

        @Test
        fun `does not abort right after a recovery even with a high stale error rate`() {
            // Exact reproduction of the observed window: F F F F S. Rate 80% > 70%, 5 samples — but the
            // last call SUCCEEDED, so the agent is making progress and must be allowed to continue.
            val tracker = TurnGuardrails.ToolErrorTracker(windowSize = 10)
            repeat(4) { tracker.recordResult(false) }
            tracker.recordResult(true)
            assertFalse(
                tracker.shouldAbort(),
                "a burst of failures followed by a success must NOT abort — the agent just recovered"
            )
        }

        @Test
        fun `still aborts if the agent fails again after a recovery`() {
            // The recovery grace is a single reprieve, not immunity: F F F F S F re-crosses the
            // threshold with a failing last call, so a genuinely-stuck agent is still stopped.
            val tracker = TurnGuardrails.ToolErrorTracker(windowSize = 10)
            repeat(4) { tracker.recordResult(false) }
            tracker.recordResult(true)
            tracker.recordResult(false)
            assertTrue(
                tracker.shouldAbort(),
                "if the agent fails again immediately after recovering, the guard must fire"
            )
        }

        @Test
        fun `weird case - alternating fail-success never reaches the abort threshold anyway`() {
            // Adversarial probe of whether the recovery clause weakens the guard: it does not, because
            // steady thrash (F S F S …) sits at ~50% error rate, below the 70% bar. The guard's teeth
            // are for sustained failure bursts; the recovery clause only touches the burst-then-recover
            // edge. Documents WHY adding the clause is safe.
            val tracker = TurnGuardrails.ToolErrorTracker(windowSize = 10)
            repeat(5) {
                tracker.recordResult(false)
                tracker.recordResult(true)
            }
            assertFalse(tracker.shouldAbort())
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
    inner class ConsecutiveBlockedToolTrackerTest {

        @Test
        fun `two blocked calls in a row abort the turn`() {
            // The reported failure mode: a subagent whose persona instructs a tool its whitelist
            // forbids keeps calling it. errorTracker dilutes and the definitive-loop signature resets
            // on varying args, so this arg-independent counter is the only backstop.
            val tracker = TurnGuardrails.ConsecutiveBlockedToolTracker()
            assertIs<TurnGuardrails.LoopStatus.OK>(tracker.record(blocked = true))
            assertIs<TurnGuardrails.LoopStatus.ABORT>(tracker.record(blocked = true))
        }

        @Test
        fun `abort is INCOMPLETE and names the recovery path`() {
            val tracker = TurnGuardrails.ConsecutiveBlockedToolTracker()
            tracker.record(blocked = true)
            val status = tracker.record(blocked = true)
            assertIs<TurnGuardrails.LoopStatus.ABORT>(status)
            assertTrue(status.incomplete, "a wrong-toolset loop delivered nothing → INCOMPLETE, not a hard error")
            assertTrue(
                status.reason.contains("available", ignoreCase = true),
                "the message must point the model back to its actual tools / plain text",
            )
        }

        @Test
        fun `a successful non-blocked call between blocks resets the streak`() {
            // WHY: one real tool interaction means the model is no longer stuck asking for a tool it
            // does not have — a single stray blocked call must not creep toward an abort across a
            // long, otherwise-healthy turn.
            val tracker = TurnGuardrails.ConsecutiveBlockedToolTracker()
            assertIs<TurnGuardrails.LoopStatus.OK>(tracker.record(blocked = true))
            assertIs<TurnGuardrails.LoopStatus.OK>(tracker.record(blocked = false))
            assertIs<TurnGuardrails.LoopStatus.OK>(tracker.record(blocked = true))
        }

        @Test
        fun `varying the blocked call arguments does NOT protect it - the streak is arg-independent`() {
            // The exact hole this guard closes: the definitive-loop signature (name+argHash) resets
            // when the persona emits a different `think` "thought" each time, so a same-tool loop with
            // changing args would otherwise never abort. Here the tracker is fed only the boolean, so
            // two blocked calls abort regardless of how the arguments differed.
            val tracker = TurnGuardrails.ConsecutiveBlockedToolTracker()
            tracker.record(blocked = true)
            assertIs<TurnGuardrails.LoopStatus.ABORT>(tracker.record(blocked = true))
        }

        @Test
        fun `only executed non-blocked failures do not count as blocked`() {
            // A normally-failing tool (e.g. read_file on a missing path) is the error-tracker's job,
            // not this guard's — it must not push toward a blocked-tool abort.
            val tracker = TurnGuardrails.ConsecutiveBlockedToolTracker()
            repeat(5) { assertIs<TurnGuardrails.LoopStatus.OK>(tracker.record(blocked = false)) }
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

        // ─── Read-only floor for subagents (Fix #2) ─────────────────────────
        // Subagents run identicalOutputAbortThreshold=2 to cut WRITE/EXEC loops early. Applying 2 to
        // read_file too false-killed real review subagents: reading a large file partially, then in
        // full, then re-reading in full to double-check yields byte-identical output twice → abort
        // mid-analysis. In one run (2026-07) all THREE review subagents died on read_file@ChatService.kt.
        // Read-only tools now use readOnlyIdenticalOutputAbortThreshold (default 4) instead.

        @Test
        fun `subagent threshold does not kill a read_file re-read at 2`() {
            // Subagent-shaped tracker: write threshold 2, read-only default 4.
            val tracker = TurnGuardrails.TurnRepetitionTracker(identicalOutputAbortThreshold = 2)
            val args = mapOf<String, Any?>("path" to "core/services/ChatService.kt")
            val fullFile = "package x\n// ...603 lines of identical content...\n"
            // A partial read then two full reads = two identical outputs. Under the old code this
            // aborted here (threshold 2); now it must stay OK.
            assertIs<TurnGuardrails.LoopStatus.OK>(tracker.record("read_file", args, "package x\n// lines 1-200\n"))
            assertIs<TurnGuardrails.LoopStatus.OK>(tracker.record("read_file", args, fullFile))
            assertIs<TurnGuardrails.LoopStatus.OK>(
                tracker.record("read_file", args, fullFile),
                "two identical full re-reads must NOT abort a subagent — re-reading a file is benign"
            )
        }

        @Test
        fun `subagent read_file still aborts on a genuine read loop at the read-only floor`() {
            // The floor is leniency, not disablement: 4 byte-identical reads of the same file is a real
            // no-progress loop and must still stop, exactly the runaway the tighter threshold targeted.
            val tracker = TurnGuardrails.TurnRepetitionTracker(identicalOutputAbortThreshold = 2)
            val args = mapOf<String, Any?>("path" to "core/services/ChatService.kt")
            val out = "identical content"
            val statuses = (1..4).map { tracker.record("read_file", args, out) }
            assertIs<TurnGuardrails.LoopStatus.OK>(statuses[0])
            assertIs<TurnGuardrails.LoopStatus.OK>(statuses[1])
            assertIs<TurnGuardrails.LoopStatus.OK>(statuses[2])
            assertIs<TurnGuardrails.LoopStatus.ABORT>(statuses[3])
        }

        @Test
        fun `subagent WRITE tool keeps the tight threshold of 2`() {
            // The lenient floor is READ-only. A write/exec loop (identical run_terminal_command output
            // twice) is still a strong no-progress signal for a subagent and must abort at 2.
            val tracker = TurnGuardrails.TurnRepetitionTracker(identicalOutputAbortThreshold = 2)
            val args = mapOf<String, Any?>("command" to "./gradlew build")
            val out = "BUILD FAILED\n> compilation error: unresolved reference foo"
            assertIs<TurnGuardrails.LoopStatus.OK>(tracker.record("run_terminal_command", args, out))
            assertIs<TurnGuardrails.LoopStatus.ABORT>(tracker.record("run_terminal_command", args, out))
        }

        @Test
        fun `read-only floor is independently configurable`() {
            val tracker = TurnGuardrails.TurnRepetitionTracker(
                identicalOutputAbortThreshold = 2,
                readOnlyIdenticalOutputAbortThreshold = 3
            )
            val args = mapOf<String, Any?>("pattern" to "TODO", "path" to "src")
            val out = "no matches"
            assertIs<TurnGuardrails.LoopStatus.OK>(tracker.record("grep_search", args, out))
            assertIs<TurnGuardrails.LoopStatus.OK>(tracker.record("grep_search", args, out))
            assertIs<TurnGuardrails.LoopStatus.ABORT>(tracker.record("grep_search", args, out))
        }

        @Test
        fun `weird case - subagent cross-referencing the same file three times stays OK`() {
            // Adversarial probe of whether the read-loop guard is worth its false-positive risk:
            // a legitimate analysis pattern is "read file, read another, come back and re-read the
            // first to confirm" — up to 3 identical reads. This must be allowed; only the 4th (pure
            // no-progress) trips. If review keeps finding false positives here, consider dropping the
            // read_file output-hash guard entirely (grep/search loops are the real target).
            val tracker = TurnGuardrails.TurnRepetitionTracker(identicalOutputAbortThreshold = 2)
            val args = mapOf<String, Any?>("path" to "core/api/CoreApiRouter.kt")
            val out = "class CoreApiRouter(...) { /* stable */ }"
            repeat(3) {
                assertIs<TurnGuardrails.LoopStatus.OK>(tracker.record("read_file", args, out))
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

        // ─── No-op write abort (P2) ─────────────────────────────────────────

        @Test
        fun `repeated no-op writes on the same target abort as INCOMPLETE`() {
            // Session f998771b / c19: advance_code_editing kept returning "content identical to the
            // existing file" — the editing model could not act on the edit_description. The result is
            // success=true and the output is NOT byte-identical (token counts vary per call), so neither
            // the error tracker nor the output-hash abort fires; the turn ran to maxIterations (828K
            // tokens). A no-op-write streak on one target is the missing "futile edit" signal.
            val tracker = TurnGuardrails.TurnRepetitionTracker(noopWriteAbortThreshold = 3)
            val args = mapOf<String, Any?>("path" to "tmp/report.md", "edit_description" to "fix citations")
            val s1 = tracker.record("advance_code_editing", args, "⚠ No changes applied (1)", isNoopWrite = true)
            val s2 = tracker.record("advance_code_editing", args, "⚠ No changes applied (2)", isNoopWrite = true)
            val s3 = tracker.record("advance_code_editing", args, "⚠ No changes applied (3)", isNoopWrite = true)
            assertIs<TurnGuardrails.LoopStatus.OK>(s1)
            assertIs<TurnGuardrails.LoopStatus.OK>(s2)
            assertIs<TurnGuardrails.LoopStatus.ABORT>(s3)
            assertTrue(
                (s3 as TurnGuardrails.LoopStatus.ABORT).incomplete,
                "a futile-edit abort is INCOMPLETE (deliverable never produced), not a hard failure"
            )
        }

        @Test
        fun `no-op writes interleaved with reads still abort on the same target`() {
            // The futile edits are interleaved with greps (different effect keys). The no-op streak is
            // per-target, so interleaved reads on OTHER keys must not reset it — exactly the c19 shape.
            val tracker = TurnGuardrails.TurnRepetitionTracker(noopWriteAbortThreshold = 3)
            val edit = mapOf<String, Any?>("path" to "tmp/report.md")
            val grep = mapOf<String, Any?>("pattern" to "DEFAULT_BASE_URL", "path" to "src")
            assertIs<TurnGuardrails.LoopStatus.OK>(tracker.record("advance_code_editing", edit, "noop a", isNoopWrite = true))
            assertIs<TurnGuardrails.LoopStatus.OK>(tracker.record("grep_search", grep, "5 matches"))
            assertIs<TurnGuardrails.LoopStatus.OK>(tracker.record("advance_code_editing", edit, "noop b", isNoopWrite = true))
            assertIs<TurnGuardrails.LoopStatus.OK>(tracker.record("grep_search", grep, "6 matches"))
            assertIs<TurnGuardrails.LoopStatus.ABORT>(tracker.record("advance_code_editing", edit, "noop c", isNoopWrite = true))
        }

        @Test
        fun `a real edit between no-ops resets the no-op streak`() {
            // A write that actually changed bytes (isNoopWrite=false) means the editing model CAN act —
            // so the futile-edit counter must restart.
            val tracker = TurnGuardrails.TurnRepetitionTracker(noopWriteAbortThreshold = 3)
            val args = mapOf<String, Any?>("path" to "tmp/report.md")
            tracker.record("advance_code_editing", args, "noop 1", isNoopWrite = true)
            tracker.record("advance_code_editing", args, "noop 2", isNoopWrite = true)
            // Real change → reset the streak.
            assertIs<TurnGuardrails.LoopStatus.OK>(tracker.record("advance_code_editing", args, "Edited (+5/-2)", isNoopWrite = false))
            // Two more no-ops: below threshold again, no abort.
            assertIs<TurnGuardrails.LoopStatus.OK>(tracker.record("advance_code_editing", args, "noop 3", isNoopWrite = true))
            assertIs<TurnGuardrails.LoopStatus.OK>(tracker.record("advance_code_editing", args, "noop 4", isNoopWrite = true))
        }

        @Test
        fun `no-op writes on different files are independent`() {
            val tracker = TurnGuardrails.TurnRepetitionTracker(noopWriteAbortThreshold = 3)
            val a = mapOf<String, Any?>("path" to "a.md")
            val b = mapOf<String, Any?>("path" to "b.md")
            tracker.record("advance_code_editing", a, "noop", isNoopWrite = true) // a=1
            tracker.record("advance_code_editing", b, "noop", isNoopWrite = true) // b=1
            assertIs<TurnGuardrails.LoopStatus.OK>(tracker.record("advance_code_editing", a, "noop", isNoopWrite = true)) // a=2
            assertIs<TurnGuardrails.LoopStatus.OK>(tracker.record("advance_code_editing", b, "noop", isNoopWrite = true)) // b=2
            // a reaches 3 → abort; b is still at 2 and untouched.
            assertIs<TurnGuardrails.LoopStatus.ABORT>(tracker.record("advance_code_editing", a, "noop", isNoopWrite = true)) // a=3
        }

        @Test
        fun `non-no-op writes never trip the no-op abort`() {
            // Regression guard for the default path: a long run of REAL edits on one file (legitimate
            // iterative refactor) passes isNoopWrite=false every time and must never abort here.
            val tracker = TurnGuardrails.TurnRepetitionTracker(noopWriteAbortThreshold = 3)
            val args = mapOf<String, Any?>("path" to "src/Main.kt")
            repeat(20) {
                assertIs<TurnGuardrails.LoopStatus.OK>(
                    tracker.record("advance_code_editing", args, "Edited (+$it/-1)", isNoopWrite = false)
                )
            }
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

        @Test
        fun `ascii box-drawing column does not abort`() {
            // WHY: session 188eb64b — user explicitly asked for "a combined architectural
            // diagram (ASCII)". A vertical column of box-drawing chars produced 14 consecutive
            // "│" tokens and the detector killed the legitimate deliverable. A run of pure
            // symbols is STRUCTURE (table border / diagram), never a generation loop.
            val diagram = (1..14).joinToString(" ") { "│" }
            assertIs<TurnGuardrails.LoopStatus.OK>(
                TurnGuardrails.ContentChantingDetector.inspect(diagram)
            )
        }

        @Test
        fun `separator-rule symbols do not abort`() {
            // "=== === ===" rules / "| | |" markdown table spines are structure, not chants.
            val rule = ("=== ".repeat(20)).trim()
            assertIs<TurnGuardrails.LoopStatus.OK>(
                TurnGuardrails.ContentChantingDetector.inspect(rule)
            )
        }

        @Test
        fun `word chant interleaved with symbols still aborts`() {
            // The exemption is ONLY for pure-symbol phrases — a real word repeated must still
            // trip even if the chant phrase also carries punctuation.
            val chant = ("No. ".repeat(20)).trim()
            val status = TurnGuardrails.ContentChantingDetector.inspect(chant)
            assertIs<TurnGuardrails.LoopStatus.ABORT>(status)
        }
    }

    @Nested
    inner class ConsecutiveTextRepetitionTrackerTest {

        @Test
        fun `first text response stays OK`() {
            val tracker = TurnGuardrails.ConsecutiveTextRepetitionTracker()
            assertIs<TurnGuardrails.LoopStatus.OK>(
                tracker.record("Good, I found the circuit breaker code. Now let me search for retry.")
            )
        }

        @Test
        fun `two identical consecutive responses abort`() {
            // Test 4 pathology (session a28cfcaa iter 9 & 10): the model emitted the SAME
            // intent sentence twice in a row with no tool call. The tool-output tracker can
            // never see this (no tool ran); this tracker is the only thing that catches it.
            val tracker = TurnGuardrails.ConsecutiveTextRepetitionTracker()
            val text = "Good, I found the circuit breaker code. Now let me search for the retry mechanism."
            assertIs<TurnGuardrails.LoopStatus.OK>(tracker.record(text))
            val second = tracker.record(text)
            assertIs<TurnGuardrails.LoopStatus.ABORT>(second)
            assertTrue(
                second.reason.contains("identical text", ignoreCase = true),
                "abort reason should name the repeated-text failure mode, got: ${second.reason}"
            )
        }

        @Test
        fun `different consecutive responses stay OK and reset the run`() {
            val tracker = TurnGuardrails.ConsecutiveTextRepetitionTracker()
            assertIs<TurnGuardrails.LoopStatus.OK>(tracker.record("Reading the first file."))
            assertIs<TurnGuardrails.LoopStatus.OK>(tracker.record("Reading the second file."))
            // Back to the first text — counter restarted, so a single occurrence is still OK.
            assertIs<TurnGuardrails.LoopStatus.OK>(tracker.record("Reading the first file."))
        }

        @Test
        fun `whitespace and case differences still count as identical`() {
            // Normalisation must collapse whitespace and lowercase so a trivially re-rendered
            // repeat (e.g. extra spaces, different capitalisation) is still caught.
            val tracker = TurnGuardrails.ConsecutiveTextRepetitionTracker()
            assertIs<TurnGuardrails.LoopStatus.OK>(tracker.record("Let me check the file."))
            assertIs<TurnGuardrails.LoopStatus.ABORT>(tracker.record("let me   check   the FILE."))
        }

        @Test
        fun `blank text is ignored and never aborts`() {
            // Empty/blank assistant text is handled by other guards (empty-envelope recovery);
            // it must not be treated as a repeated chant.
            val tracker = TurnGuardrails.ConsecutiveTextRepetitionTracker()
            repeat(5) {
                assertIs<TurnGuardrails.LoopStatus.OK>(tracker.record("   "))
            }
        }

        @Test
        fun `two near-identical paraphrases abort even when not byte-identical`() {
            // Test 0066 (session qwen3.6:35b, PLAN): the model paraphrased the SAME intent sentence
            // on two consecutive no-tool-call iterations — differing only in 2 of 17 words. Exact-hash
            // matching missed it (different hashCode each time) so the turn churned to INCOMPLETE via
            // the guardian. Dice over token sets (~0.88 here) must recognise it as no-progress.
            val tracker = TurnGuardrails.ConsecutiveTextRepetitionTracker()
            val first = "Dobrze, standardowe skanowanie nie dało głębokich wyników. " +
                "Teraz podejmuję bardziej zaawansowane poszukiwania ukrytych elementów w kodzie źródłowym."
            val paraphrase = "Dobra, standardowe wyszukiwanie nie dało głębokich wyników. " +
                "Teraz podejmuję bardziej zaawansowane poszukiwania ukrytych elementów w kodzie źródłowym."
            assertIs<TurnGuardrails.LoopStatus.OK>(tracker.record(first))
            val second = tracker.record(paraphrase)
            assertIs<TurnGuardrails.LoopStatus.ABORT>(second)
            assertTrue(
                second.reason.contains("identical text", ignoreCase = true),
                "abort reason should name the repeated-text failure mode, got: ${second.reason}"
            )
        }

        @Test
        fun `genuinely different prose below the similarity threshold stays OK`() {
            // The fuzzy upgrade must not fire on two distinct actions that merely share boilerplate.
            // "the circuit breaker" vs "the retry handler" overlap is well below 0.85 Dice.
            val tracker = TurnGuardrails.ConsecutiveTextRepetitionTracker()
            assertIs<TurnGuardrails.LoopStatus.OK>(
                tracker.record("Good, now I will read the circuit breaker implementation in detail.")
            )
            assertIs<TurnGuardrails.LoopStatus.OK>(
                tracker.record("Next, I will inspect the database migration runner for ordering bugs.")
            )
        }

        @Test
        fun `a one-word change in a short sentence does not abort`() {
            // Short sentences must keep their strictness: a single distinct word is a real difference,
            // not a paraphrase. "first" vs "second" file is a legitimate two-step plan.
            val tracker = TurnGuardrails.ConsecutiveTextRepetitionTracker()
            assertIs<TurnGuardrails.LoopStatus.OK>(tracker.record("Reading the first file."))
            assertIs<TurnGuardrails.LoopStatus.OK>(tracker.record("Reading the second file."))
        }
    }
}
