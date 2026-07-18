package pl.jclab.refio.core.llm

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ModelRegistryTest {

    @Test
    fun `a provider whose fetch failed keeps the models it had before`() {
        val previous = mapOf(
            "zai" to listOf(model("glm-5.1", "zai")),
            "openai" to listOf(model("gpt-4.1", "openai"))
        )

        // zai timed out (e.g. its request gate was busy with a running turn), openai answered.
        val merged = mergeProviderModels(
            previous = previous,
            fetched = mapOf("zai" to null, "openai" to listOf(model("gpt-4.1", "openai")))
        )

        assertEquals(listOf("glm-5.1"), merged["zai"]?.map { it.id })
    }

    @Test
    fun `a provider that reports no models drops its previous ones`() {
        val previous = mapOf("zai" to listOf(model("glm-5.1", "zai")))

        // An empty answer is the provider saying its models are gone (key removed, endpoint changed).
        val merged = mergeProviderModels(previous = previous, fetched = mapOf("zai" to emptyList()))

        assertEquals(emptyList(), merged["zai"])
    }

    @Test
    fun `a newly reachable provider is added to the cache`() {
        val merged = mergeProviderModels(
            previous = mapOf("openai" to listOf(model("gpt-4.1", "openai"))),
            fetched = mapOf("zai" to listOf(model("glm-5.1", "zai")))
        )

        assertEquals(setOf("openai", "zai"), merged.keys)
    }

    private fun model(id: String, provider: String) = ModelConfig(
        id = id,
        name = id,
        provider = provider,
        capabilities = listOf("CHAT_COMPLETION"),
        maxContext = 128000,
        costPer1mInput = 0.0,
        costPer1mOutput = 0.0
    )
}
