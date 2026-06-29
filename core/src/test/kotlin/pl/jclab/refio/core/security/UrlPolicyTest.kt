package pl.jclab.refio.core.security

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * UrlPolicy is the SSRF guard for outbound tools. The business rule under test: internal targets
 * (loopback, private, link-local) are refused by default, and the `security.allow_loopback` opt-in
 * lifts that for trusted local setups (the e2e fixture server) - but never for multicast, and never
 * for the scheme allow-list. IP literals are used so resolution stays offline and deterministic.
 */
class UrlPolicyTest {

    @Test
    fun `loopback and private targets are blocked by default`() {
        val policy = UrlPolicy() // default: opt-in off
        for (url in listOf(
            "http://127.0.0.1:8080/data.json",
            "http://192.168.1.10/admin",
            "http://10.0.0.5/",
            "http://169.254.169.254/latest/meta-data", // link-local (cloud metadata SSRF)
        )) {
            assertThrows(SecurityException::class.java, { policy.validate(url) }, "must block $url")
        }
    }

    @Test
    fun `public targets are allowed`() {
        UrlPolicy().validate("http://8.8.8.8/")
        UrlPolicy().validate("https://93.184.216.34/") // a routable public address
    }

    @Test
    fun `opt-in permits loopback and private targets for a local fixture server`() {
        val policy = UrlPolicy(allowLoopback = { true })
        policy.validate("http://127.0.0.1:8723/data.json")
        policy.validate("http://192.168.1.10/")
    }

    @Test
    fun `multicast stays blocked even with the loopback opt-in`() {
        val policy = UrlPolicy(allowLoopback = { true })
        assertThrows(SecurityException::class.java) { policy.validate("http://224.0.0.1/") }
    }

    @Test
    fun `non-http schemes are rejected regardless of the opt-in`() {
        val policy = UrlPolicy(allowLoopback = { true })
        assertThrows(IllegalArgumentException::class.java) { policy.validate("ftp://127.0.0.1/x") }
        assertThrows(SecurityException::class.java) { policy.validate("notaurl") }
    }
}
