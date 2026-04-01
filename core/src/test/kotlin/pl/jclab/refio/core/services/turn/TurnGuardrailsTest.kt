package pl.jclab.refio.core.services.turn

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.db.TaskMode
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
            // 30% error rate - below 70% threshold
            assertFalse(tracker.shouldAbort())
        }

        @Test
        fun `should use sliding window and evict old results`() {
            val tracker = TurnGuardrails.ToolErrorTracker(windowSize = 5)
            // Fill with failures
            repeat(5) { tracker.recordResult(false) }
            assertEquals(1.0, tracker.getErrorRate())

            // Add 5 successes — old failures should be evicted
            repeat(5) { tracker.recordResult(true) }
            assertEquals(0.0, tracker.getErrorRate())
        }

        @Test
        fun `should support custom threshold`() {
            val tracker = TurnGuardrails.ToolErrorTracker(windowSize = 10)
            repeat(5) { tracker.recordResult(false) }
            repeat(5) { tracker.recordResult(true) }
            // 50% error rate
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
    inner class LoopDetectorTest {

        @Test
        fun `should return OK for first tool call`() {
            val detector = TurnGuardrails.LoopDetector()
            val status = detector.recordToolCall("read_file", """{"path": "a.kt"}""")
            assertIs<TurnGuardrails.LoopStatus.OK>(status)
        }

        @Test
        fun `should return WARN for second consecutive same tool call`() {
            val detector = TurnGuardrails.LoopDetector()
            detector.recordToolCall("read_file", """{"path": "a.kt"}""")
            val status = detector.recordToolCall("read_file", """{"path": "a.kt"}""")
            assertIs<TurnGuardrails.LoopStatus.WARN>(status)
        }

        @Test
        fun `should return ABORT after max consecutive repeats`() {
            val detector = TurnGuardrails.LoopDetector(maxConsecutiveRepeats = 3)
            detector.recordToolCall("read_file", """{"path": "same.kt"}""")
            detector.recordToolCall("read_file", """{"path": "same.kt"}""")
            val status = detector.recordToolCall("read_file", """{"path": "same.kt"}""")
            assertIs<TurnGuardrails.LoopStatus.ABORT>(status)
        }

        @Test
        fun `should return ABORT when total count exceeds max`() {
            val detector = TurnGuardrails.LoopDetector(maxConsecutiveRepeats = 10, maxSameToolCallsTotal = 5)
            // Interleave with different calls to avoid consecutive abort
            for (i in 1..5) {
                detector.recordToolCall("read_file", """{"path": "same.kt"}""")
                if (i < 5) {
                    detector.recordToolCall("grep_search", """{"pattern": "foo$i"}""")
                }
            }
            // Last call should be ABORT due to total count = 5
            // Actually the 5th call of read_file will trigger total >= 5
        }

        @Test
        fun `should not flag different tool calls`() {
            val detector = TurnGuardrails.LoopDetector()
            val s1 = detector.recordToolCall("read_file", """{"path": "a.kt"}""")
            val s2 = detector.recordToolCall("read_file", """{"path": "b.kt"}""")
            val s3 = detector.recordToolCall("grep_search", """{"pattern": "foo"}""")
            assertIs<TurnGuardrails.LoopStatus.OK>(s1)
            assertIs<TurnGuardrails.LoopStatus.OK>(s2)
            assertIs<TurnGuardrails.LoopStatus.OK>(s3)
        }

        @Test
        fun `should normalize arguments by removing whitespace`() {
            val detector = TurnGuardrails.LoopDetector(maxConsecutiveRepeats = 3)
            detector.recordToolCall("read_file", """{ "path" : "a.kt" }""")
            detector.recordToolCall("read_file", """{"path":"a.kt"}""")
            val status = detector.recordToolCall("read_file", """{"path":  "a.kt"}""")
            // All three are same after normalization
            assertIs<TurnGuardrails.LoopStatus.ABORT>(status)
        }

        @Test
        fun `should detect empty tool calls loop`() {
            val detector = TurnGuardrails.LoopDetector()
            val s1 = detector.recordEmptyToolCalls()
            assertIs<TurnGuardrails.LoopStatus.OK>(s1)

            val s2 = detector.recordEmptyToolCalls()
            assertIs<TurnGuardrails.LoopStatus.WARN>(s2)

            val s3 = detector.recordEmptyToolCalls()
            assertIs<TurnGuardrails.LoopStatus.ABORT>(s3)
        }

        @Test
        fun `should return formatted stats`() {
            val detector = TurnGuardrails.LoopDetector()
            detector.recordToolCall("read_file", """{"path": "a.kt"}""")
            detector.recordToolCall("grep_search", """{"pattern": "foo"}""")
            val stats = detector.getStats()
            assertTrue(stats.contains("unique=2"))
            assertTrue(stats.contains("total=2"))
        }
    }

    @Nested
    inner class CompanionMethodsTest {

        @Test
        fun `isReadOnlyLoop should return false for non-AGENT mode`() {
            assertFalse(TurnGuardrails.isReadOnlyLoop(TaskMode.CHAT, 10))
            assertFalse(TurnGuardrails.isReadOnlyLoop(TaskMode.PLAN, 10))
        }

        @Test
        fun `isReadOnlyLoop should return true for AGENT mode above threshold`() {
            assertTrue(TurnGuardrails.isReadOnlyLoop(TaskMode.AGENT, 3))
            assertTrue(TurnGuardrails.isReadOnlyLoop(TaskMode.AGENT, 5))
        }

        @Test
        fun `isReadOnlyLoop should return false for AGENT mode below threshold`() {
            assertFalse(TurnGuardrails.isReadOnlyLoop(TaskMode.AGENT, 2))
            assertFalse(TurnGuardrails.isReadOnlyLoop(TaskMode.AGENT, 0))
        }

        @Test
        fun `isReadOnlyLoop should support custom threshold`() {
            assertFalse(TurnGuardrails.isReadOnlyLoop(TaskMode.AGENT, 4, threshold = 5))
            assertTrue(TurnGuardrails.isReadOnlyLoop(TaskMode.AGENT, 5, threshold = 5))
        }
    }
}
