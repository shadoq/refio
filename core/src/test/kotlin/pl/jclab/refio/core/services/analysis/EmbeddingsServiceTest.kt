package pl.jclab.refio.core.services.analysis

import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.EmbeddingProvider
import java.security.MessageDigest
import kotlin.test.assertEquals

class EmbeddingsServiceTest {

    private fun newService(): EmbeddingsService =
        EmbeddingsService(mockk<ConfigService>(relaxed = true)) { mockk<EmbeddingProvider>(relaxed = true) }

    private fun referenceSha256Hex(payload: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `cacheKey returns the correct SHA-256 of provider model and text`() {
        val service = newService()
        val key = service.cacheKey("ollama", "nomic-embed", "hello world")
        assertEquals(referenceSha256Hex("ollama|nomic-embed|hello world"), key)
    }

    @Test
    fun `cacheKey is thread-safe under concurrency and never corrupts the hash`() = runBlocking {
        // The cache key is the identity of a cached embedding. EmbeddingsService used one shared
        // MessageDigest and generate() hashed off the mutex, so concurrent calls interleaved on the
        // stateful, non-thread-safe digest and produced wrong keys (cache poisoning: the embedding
        // for one text returned for another, or spurious misses). The key must be a pure function of
        // its inputs: every concurrent computation must equal the independently-computed SHA-256.
        val service = newService()
        val provider = "ollama"
        val model = "nomic-embed"
        val inputs = (0 until 2000).map { "text-$it" }
        val expected = inputs.associateWith { text -> referenceSha256Hex("$provider|$model|$text") }

        val computed = inputs.map { text ->
            async(Dispatchers.Default) { text to service.cacheKey(provider, model, text) }
        }.awaitAll().toMap()

        inputs.forEach { text ->
            assertEquals(expected[text], computed[text], "hash mismatch for '$text' (shared-digest race)")
        }
    }
}
