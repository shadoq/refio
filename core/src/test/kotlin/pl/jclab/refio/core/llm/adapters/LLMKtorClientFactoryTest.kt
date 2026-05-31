package pl.jclab.refio.core.llm.adapters

import io.ktor.client.plugins.HttpTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks the stream idle-timeout ceiling that prevents a dead LLM stream from hanging for the
 * full (possibly huge) request timeout.
 *
 * WHY: a `qwen3.5:122b` stream fell silent after the first chunk and hung 53 minutes until the
 * OS reset the TCP socket, because the socket idle timeout was tied 1:1 to `API_CALL_TIMEOUT`
 * (which the user had raised for slow big-model generation). The idle timeout must be bounded
 * independently of the total request timeout.
 */
class LLMKtorClientFactoryTest {

    @Test
    fun `short timeout is left untouched - never raised`() {
        // A caller using a short timeout must not be lengthened (no new false-abort risk).
        assertEquals(60_000L, LLMKtorClientFactory.resolveIdleTimeoutMs(60_000L))
        assertEquals(
            LLMKtorClientFactory.STREAM_IDLE_CEILING_MS,
            LLMKtorClientFactory.resolveIdleTimeoutMs(LLMKtorClientFactory.STREAM_IDLE_CEILING_MS)
        )
    }

    @Test
    fun `huge timeout is clamped to the ceiling`() {
        // The 53-min-hang scenario: API_CALL_TIMEOUT bumped to ~1h -> idle must still cap at ceiling.
        assertEquals(
            LLMKtorClientFactory.STREAM_IDLE_CEILING_MS,
            LLMKtorClientFactory.resolveIdleTimeoutMs(3_600_000L)
        )
    }

    @Test
    fun `infinite or non-positive falls back to the ceiling - never idles forever`() {
        assertEquals(
            LLMKtorClientFactory.STREAM_IDLE_CEILING_MS,
            LLMKtorClientFactory.resolveIdleTimeoutMs(HttpTimeout.INFINITE_TIMEOUT_MS)
        )
        assertEquals(
            LLMKtorClientFactory.STREAM_IDLE_CEILING_MS,
            LLMKtorClientFactory.resolveIdleTimeoutMs(0L)
        )
        assertEquals(
            LLMKtorClientFactory.STREAM_IDLE_CEILING_MS,
            LLMKtorClientFactory.resolveIdleTimeoutMs(-1L)
        )
    }

    @Test
    fun `ceiling is a few minutes - far above a cold big-model first-token gap, far below an hour`() {
        // Observed cold 122B first-token gap was ~125s; the 53-min hang was ~3194s. The ceiling
        // must sit comfortably between so it tolerates slow first tokens but kills dead streams.
        val ceiling = LLMKtorClientFactory.STREAM_IDLE_CEILING_MS
        assertEquals(true, ceiling in 180_000L..600_000L, "ceiling=${ceiling}ms out of the sane 3-10 min band")
    }
}
