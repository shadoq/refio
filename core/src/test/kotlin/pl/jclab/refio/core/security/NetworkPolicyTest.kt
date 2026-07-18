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

    // The gate must not silently fail open: a user who explicitly enabled no-egress must not
    // have the guarantee voided by a transient config read error mid-session.
    @Test
    fun `config read failure falls back to the last successfully read value`() {
        val cfg = mockk<ConfigService>()
        every { cfg.getTyped(ConfigKeys.GENERAL_NO_EGRESS_ENABLED, any()) } returns true
        val policy = NetworkPolicy(cfg)
        assertTrue(policy.isNoEgressEnabled("task-1"))

        every { cfg.getTyped(ConfigKeys.GENERAL_NO_EGRESS_ENABLED, any()) } throws RuntimeException("boom")
        assertTrue(policy.isNoEgressEnabled("task-1"))
        assertThrows(NoEgressViolationException::class.java) {
            policy.assertEgressAllowed("test_tool", "https://example.com", "task-1")
        }
    }

    // With no successful read ever, the safe direction for an egress gate is closed (blocked),
    // even though the config DEFAULT is no-egress disabled - an unreadable config is an
    // abnormal state, not the default state.
    @Test
    fun `config read failure with no prior read fails closed`() {
        val cfg = mockk<ConfigService>()
        every { cfg.getTyped(ConfigKeys.GENERAL_NO_EGRESS_ENABLED, any()) } throws RuntimeException("boom")
        val policy = NetworkPolicy(cfg)

        assertTrue(policy.isNoEgressEnabled())
        assertThrows(NoEgressViolationException::class.java) {
            policy.assertEgressAllowed("test_tool", "https://example.com")
        }
    }

    // Recovery: once the config becomes readable again, the live value wins over the fallback.
    @Test
    fun `config becoming readable again overrides the fail-closed fallback`() {
        val cfg = mockk<ConfigService>()
        every { cfg.getTyped(ConfigKeys.GENERAL_NO_EGRESS_ENABLED, any()) } throws RuntimeException("boom")
        val policy = NetworkPolicy(cfg)
        assertTrue(policy.isNoEgressEnabled())

        every { cfg.getTyped(ConfigKeys.GENERAL_NO_EGRESS_ENABLED, any()) } returns false
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
