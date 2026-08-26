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
import java.net.InetAddress
import java.net.UnknownHostException

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

    // A host name is attacker-supplied text, not an address. Under no-egress the user's whole code
    // context is sent to whatever the provider endpoint points at, and a project-level config file
    // (a cloned repo) can set that endpoint - so a name that merely LOOKS like an RFC1918 address
    // must not buy the local exemption.
    @Test
    fun `a hostname shaped like a private address is not local`() {
        val toPublic: (String) -> Array<InetAddress> = { arrayOf(InetAddress.getByName("203.0.113.7")) }

        assertFalse(NetworkPolicy.isLocalTarget("http://10.attacker.example.com:11434", toPublic))
        assertFalse(NetworkPolicy.isLocalTarget("http://192.168.evil.net:11434", toPublic))
        assertFalse(NetworkPolicy.isLocalTarget("http://127.0.0.1.attacker.example.com/", toPublic))
        assertFalse(NetworkPolicy.isLocalTarget("http://localhost.attacker.example.com/", toPublic))
    }

    // The endpoints users actually configure must keep working: literals resolve without any
    // network I/O, so this stays valid offline.
    @Test
    fun `loopback and private literals remain local`() {
        for (url in listOf(
            "http://localhost:11434",
            "http://127.0.0.1:11434",
            "http://127.1.2.3:11434",
            "http://[::1]:11434",
            "http://0.0.0.0:11434",
            "http://10.0.0.5:11434",
            "http://192.168.1.50:1234"
        )) {
            assertTrue(NetworkPolicy.isLocalTarget(url), "must stay local: $url")
        }
    }

    @Test
    fun `a public literal is not local`() {
        assertFalse(NetworkPolicy.isLocalTarget("https://8.8.8.8/v1/embeddings"))
        assertFalse(NetworkPolicy.isLocalTarget("http://203.0.113.7:11434"))
        assertFalse(NetworkPolicy.isLocalTarget("http://224.0.0.1:11434"))
    }

    // A name that genuinely points into the LAN keeps the exemption - this is the case the
    // old text match got wrong in the other direction (it blocked such names).
    @Test
    fun `a hostname resolving only to private records is local`() {
        val toLan: (String) -> Array<InetAddress> = {
            arrayOf(InetAddress.getByName("192.168.1.50"), InetAddress.getByName("10.0.0.5"))
        }

        assertTrue(NetworkPolicy.isLocalTarget("http://ollama.lan:11434", toLan))
    }

    // DNS rebinding: one hostile record among the answers is enough, because the HTTP client may
    // connect to any of them. Any public record disqualifies the whole target.
    @Test
    fun `a hostname resolving to both private and public records is not local`() {
        val mixed: (String) -> Array<InetAddress> = {
            arrayOf(InetAddress.getByName("10.0.0.5"), InetAddress.getByName("203.0.113.7"))
        }

        assertFalse(NetworkPolicy.isLocalTarget("http://rebinder.example:11434", mixed))
    }

    // Fail closed: an unresolvable or empty answer is no proof of locality, and the connection
    // that would follow cannot succeed either.
    @Test
    fun `an unresolvable hostname is not local`() {
        assertFalse(NetworkPolicy.isLocalTarget("http://ghost.example:11434") { throw UnknownHostException("ghost") })
        assertFalse(NetworkPolicy.isLocalTarget("http://ghost.example:11434") { emptyArray() })
    }

    @Test
    fun `a target without a parsable host is not local`() {
        assertFalse(NetworkPolicy.isLocalTarget(""))
        assertFalse(NetworkPolicy.isLocalTarget("not a url"))
        assertFalse(NetworkPolicy.isLocalTarget("ollama:11434"))
    }

    // End-to-end through the gate: the spoofed endpoint must be refused, not merely classified.
    @Test
    fun `no-egress blocks an endpoint whose hostname only looks private`() {
        val cfg = mockk<ConfigService>()
        every { cfg.getTyped(ConfigKeys.GENERAL_NO_EGRESS_ENABLED, any()) } returns true
        val policy = NetworkPolicy(cfg, resolveAll = { arrayOf(InetAddress.getByName("203.0.113.7")) })

        assertThrows(NoEgressViolationException::class.java) {
            policy.assertRemoteEgressAllowed("embeddings", "http://10.attacker.example.com:11434", "task-1")
        }
    }

    @Test
    fun `no-egress still allows a genuinely local endpoint`() {
        val cfg = mockk<ConfigService>()
        every { cfg.getTyped(ConfigKeys.GENERAL_NO_EGRESS_ENABLED, any()) } returns true
        val policy = NetworkPolicy(cfg, resolveAll = { arrayOf(InetAddress.getByName("192.168.1.50")) })

        policy.assertRemoteEgressAllowed("embeddings", "http://127.0.0.1:11434", "task-1")
        policy.assertRemoteEgressAllowed("embeddings", "http://ollama.lan:11434", "task-1")
    }

    // With the gate open nothing is classified, so no name lookup is paid for on the hot path.
    @Test
    fun `an open gate does not resolve the target`() {
        val cfg = mockk<ConfigService>()
        every { cfg.getTyped(ConfigKeys.GENERAL_NO_EGRESS_ENABLED, any()) } returns false
        val policy = NetworkPolicy(cfg, resolveAll = { throw AssertionError("resolver must not be called") })

        policy.assertRemoteEgressAllowed("embeddings", "https://api.openai.com/v1/embeddings", "task-1")
    }

    // The two gates share the notion of a private address but must keep opposite verdicts:
    // UrlPolicy blocks private targets (SSRF), NetworkPolicy exempts them (local-first).
    // Any future extraction of the shared address logic has to preserve this inversion.
    @Test
    fun `local classification is the inverse of the SSRF guard for private targets`() {
        val ssrfGuard = UrlPolicy()
        for (url in listOf("http://127.0.0.1:11434", "http://10.0.0.5:11434", "http://192.168.1.50:1234")) {
            assertTrue(NetworkPolicy.isLocalTarget(url), "no-egress must exempt $url")
            assertThrows(SecurityException::class.java, { ssrfGuard.validate(url) }, "SSRF guard must block $url")
        }
    }
}
