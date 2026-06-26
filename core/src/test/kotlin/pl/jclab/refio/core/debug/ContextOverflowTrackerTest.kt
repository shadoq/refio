package pl.jclab.refio.core.debug

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContextOverflowTrackerTest {

    @BeforeTest
    @AfterTest
    fun clean() {
        ContextOverflowTracker.reset()
    }

    @Test
    fun `tasks start without overflow`() {
        assertFalse(ContextOverflowTracker.didOverflow("t1"))
    }

    @Test
    fun `markOverflow is recorded per task and isolated`() {
        ContextOverflowTracker.markOverflow("t1")
        assertTrue(ContextOverflowTracker.didOverflow("t1"))
        assertFalse(ContextOverflowTracker.didOverflow("t2"))
    }
}
