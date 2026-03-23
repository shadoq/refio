package pl.jclab.refio.core.services.analysis

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.core.services.EmbeddingProvider
import pl.jclab.refio.core.services.monitoring.GlobalMetrics
import pl.jclab.refio.core.logging.dualLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.time.Duration
import kotlin.text.Charsets

class EmbeddingsService(
    private val configService: ConfigService,
    private val providerFactory: (String) -> EmbeddingProvider
) {

    companion object {
        private const val DEFAULT_CACHE_SIZE = ConfigService.DEFAULT_RAG_EMBEDDING_CACHE_SIZE
    }

    private val logger = dualLogger("EmbeddingsService")
    private val mutex = Mutex()
    private val digest = MessageDigest.getInstance("SHA-256")
    private val cache: Cache<String, FloatArray> = Caffeine.newBuilder()
        .maximumSize(DEFAULT_CACHE_SIZE.toLong())
        .expireAfterAccess(Duration.ofMinutes(30))
        .build()

    suspend fun generate(
        text: String,
        providerOverride: String? = null,
        modelOverride: String? = null
    ): FloatArray {
        val (modelId, providerId) = resolveProviderModel(providerOverride, modelOverride)
        val key = cacheKey(providerId, modelId, text)

        mutex.withLock {
            cache.getIfPresent(key)?.let { cached ->
                GlobalMetrics.recordCacheAccess("embeddings", hit = true)
                logger.info { "Embedding cache hit (provider=$providerId, model=$modelId)" }
                return cached
            }
        }

        GlobalMetrics.recordCacheAccess("embeddings", hit = false)
        val provider = providerFactory(providerId.lowercase())
        val embedding = provider.generateEmbedding(text, modelId)

        mutex.withLock {
            cache.put(key, embedding)
            GlobalMetrics.recordCacheSize("embeddings", cache.estimatedSize().toInt())
        }

        return embedding
    }

    suspend fun generateBatch(
        texts: List<String>,
        providerOverride: String? = null,
        modelOverride: String? = null
    ): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()

        val (modelId, providerId) = resolveProviderModel(providerOverride, modelOverride)
        val provider = providerFactory(providerId.lowercase())

        val results = MutableList(texts.size) { FloatArray(0) }
        val misses = mutableListOf<Pair<Int, String>>()

        mutex.withLock {
            texts.forEachIndexed { index, text ->
                val key = cacheKey(providerId, modelId, text)
                val cached = cache.getIfPresent(key)
                if (cached != null) {
                    results[index] = cached
                } else {
                    misses.add(index to key)
                }
            }
        }

        if (misses.isEmpty()) {
            return results
        }

        misses.forEach { (index, key) ->
            val text = texts[index]
            val embedding = provider.generateEmbedding(text, modelId)
            results[index] = embedding
            mutex.withLock { cache.put(key, embedding) }
        }

        return results
    }

    private fun resolveProviderModel(
        providerOverride: String?,
        modelOverride: String?
    ): Pair<String, String> {
        if (!providerOverride.isNullOrBlank() && !modelOverride.isNullOrBlank()) {
            return modelOverride to providerOverride
        }

        val configured = configService.getEmbeddingModel()
        val split = configured.split("/")
        return if (split.size == 2) {
            split[1] to split[0]
        } else {
            split[0] to "ollama"
        }
    }

    private fun cacheKey(provider: String, model: String, text: String): String {
        val payload = "$provider|$model|$text"
        val hash = digest.digest(payload.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
