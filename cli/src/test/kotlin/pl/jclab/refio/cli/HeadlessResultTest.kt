package pl.jclab.refio.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.db.TaskStatus

class HeadlessResultTest {

    // A delivered turn is the only success: a CI step gating on the exit code must see 0 here.
    @Test
    fun `successful turn exits zero`() {
        assertEquals(HeadlessExit.SUCCESS, HeadlessExit.forStatus(TaskStatus.SUCCESS))
    }

    // A failed turn must not be hidden behind a zero exit code (the previous always-0 bug).
    @Test
    fun `failed turn exits non-zero`() {
        assertNotEquals(HeadlessExit.SUCCESS, HeadlessExit.forStatus(TaskStatus.FAILED))
    }

    // INCOMPLETE means the agent stopped without delivering the request - not a success for CI.
    @Test
    fun `incomplete turn exits non-zero because the request was not delivered`() {
        assertNotEquals(HeadlessExit.SUCCESS, HeadlessExit.forStatus(TaskStatus.INCOMPLETE))
    }

    // --no-egress is a hard switch: headless must surface it through the key the egress gate
    // and the LLM callers actually read, otherwise the run still reaches the cloud.
    @Test
    fun `no-egress flag forces the egress config key on`() {
        val result = withNoEgress(emptyMap(), noEgress = true)
        assertEquals("true", result[ConfigKeys.GENERAL_NO_EGRESS_ENABLED.key])
    }

    // Without the flag we must not inject the key, so the configured / UI default still wins.
    @Test
    fun `without the flag the egress key is left untouched`() {
        val result = withNoEgress(emptyMap(), noEgress = false)
        assertFalse(result.containsKey(ConfigKeys.GENERAL_NO_EGRESS_ENABLED.key))
    }

    // The no-egress override must not clobber other run-scope overrides (e.g. --config).
    @Test
    fun `no-egress override preserves existing overrides`() {
        val base = mapOf("agent.max_iterations" to "80")
        val result = withNoEgress(base, noEgress = true)
        assertEquals("80", result["agent.max_iterations"])
        assertEquals("true", result[ConfigKeys.GENERAL_NO_EGRESS_ENABLED.key])
    }
}
