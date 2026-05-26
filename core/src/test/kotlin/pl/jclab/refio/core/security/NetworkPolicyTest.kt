package pl.jclab.refio.core.security

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.llm.NoEgressViolationException
import pl.jclab.refio.core.services.ConfigService

class NetworkPolicyTest {

    @Test
    fun `allows egress when no-egress disabled`() {
        val cfg = mockk<ConfigService>()
        every { cfg.getTyped(ConfigKeys.GENERAL_NO_EGRESS_ENABLED, any()) } returns false
        val policy = NetworkPolicy(cfg)

        assertFalse(policy.isNoEgressEnabled("task-1"))
        policy.assertEgressAllowed("test_tool", "https://example.com", "task-1")
    }

    @Test
    fun `blocks egress and includes tool name and target in message`() {
        val cfg = mockk<ConfigService>()
        every { cfg.getTyped(ConfigKeys.GENERAL_NO_EGRESS_ENABLED, any()) } returns true
        val policy = NetworkPolicy(cfg)

        assertTrue(policy.isNoEgressEnabled("task-1"))
        val ex = assertThrows(NoEgressViolationException::class.java) {
            policy.assertEgressAllowed("web_search", "https://example.com", "task-1")
        }
        assertTrue(ex.message!!.contains("web_search"))
        assertTrue(ex.message!!.contains("https://example.com"))
    }

    @Test
    fun `failure to read config defaults to allow`() {
        val cfg = mockk<ConfigService>()
        every { cfg.getTyped(ConfigKeys.GENERAL_NO_EGRESS_ENABLED, any()) } throws RuntimeException("boom")
        val policy = NetworkPolicy(cfg)

        assertFalse(policy.isNoEgressEnabled())
        policy.assertEgressAllowed("test_tool", "https://example.com")
    }

    @Test
    fun `taskIdProvider is consulted when caller passes none`() {
        val cfg = mockk<ConfigService>()
        every { cfg.getTyped(ConfigKeys.GENERAL_NO_EGRESS_ENABLED, "ambient-task") } returns true
        every { cfg.getTyped(ConfigKeys.GENERAL_NO_EGRESS_ENABLED, null) } returns false
        val policy = NetworkPolicy(cfg, taskIdProvider = { "ambient-task" })

        assertEquals(true, policy.isNoEgressEnabled())
    }
}
