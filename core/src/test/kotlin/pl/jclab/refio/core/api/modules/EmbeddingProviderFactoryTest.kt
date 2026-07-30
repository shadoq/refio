package pl.jclab.refio.core.api.modules

import io.mockk.every
import io.mockk.mockk
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.errors.RefioError
import pl.jclab.refio.core.services.OllamaEmbeddingProvider
import pl.jclab.refio.core.services.OpenAICompatibleEmbeddingProvider
import pl.jclab.refio.core.services.OpenAIEmbeddingProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `models.embedding_model` decides where the indexed project content is sent, so getting this
 * mapping wrong is a data-egress problem, not a configuration inconvenience.
 *
 * Regression: an unrecognized provider id used to log a warning and fall through to OpenAI, so
 * `lmstudio/jina-v5` silently uploaded the project to api.openai.com whenever a key was present.
 */
class EmbeddingProviderFactoryTest {

    @Test
    fun `an unrecognized provider fails instead of quietly sending the project to OpenAI`() {
        val factory = EmbeddingProviderFactory(config())

        val error = assertFailsWith<RefioError.ProviderNotConfigured> {
            factory.create("lmstudio/jina-embeddings-v5")
        }

        assertTrue(
            error.message!!.contains("lmstudio"),
            "the error has to name the provider the user actually configured"
        )
    }

    @Test
    fun `a self-hosted embeddings endpoint is used when configured`() {
        val factory = EmbeddingProviderFactory(config(embeddingsBaseUrl = "http://localhost:8081/v1"))

        val provider = factory.create("openai_compatible/jina-embeddings-v5")

        assertTrue(provider is OpenAICompatibleEmbeddingProvider)
    }

    @Test
    fun `selecting the self-hosted endpoint without a base URL names the missing key`() {
        val factory = EmbeddingProviderFactory(config())

        val error = assertFailsWith<RefioError.ProviderNotConfigured> {
            factory.create("openai_compatible/jina-embeddings-v5")
        }

        assertTrue(
            error.message!!.contains(ConfigKeys.PROVIDER_EMBEDDINGS_BASE_URL.key),
            "an actionable error has to say which key to set, got: ${error.message}"
        )
    }

    @Test
    fun `the built-in providers still resolve as before`() {
        val factory = EmbeddingProviderFactory(config())

        assertTrue(factory.create("ollama/nomic-embed-text") is OllamaEmbeddingProvider)
        assertTrue(factory.create("openai/text-embedding-3-small") is OpenAIEmbeddingProvider)
        assertTrue(factory.create("nomic-embed-text") is OllamaEmbeddingProvider, "bare Ollama model name")
        assertTrue(factory.create("text-embedding-3-small") is OpenAIEmbeddingProvider, "bare OpenAI model name")
    }

    @Test
    fun `resolve splits the provider prefix from the model name`() {
        val factory = EmbeddingProviderFactory(config())

        assertEquals("openai_compatible" to "jina-embeddings-v5", factory.resolve("openai_compatible/jina-embeddings-v5"))
        assertEquals("ollama" to "nomic-embed-text", factory.resolve("ollama/nomic-embed-text"))
    }

    private fun config(embeddingsBaseUrl: String? = null) = mockk<pl.jclab.refio.core.services.ConfigService>().also {
        every { it.getTyped(ConfigKeys.PROVIDER_OLLAMA_ENDPOINT) } returns "http://localhost:11434"
        every { it.getTyped(ConfigKeys.PROVIDER_EMBEDDINGS_BASE_URL) } returns embeddingsBaseUrl
        every { it.getTyped(ConfigKeys.PROVIDER_EMBEDDINGS_API_KEY) } returns null
    }
}
