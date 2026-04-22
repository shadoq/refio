package pl.jclab.refio.core.llm

import pl.jclab.refio.core.logging.dualLogger
import java.util.concurrent.ConcurrentHashMap

private val logger = dualLogger("NativeToolsFallbackTracker")

/**
 * In-memory registry of models that failed native function-calling in the current process run.
 *
 * When a model returns a provider error indicating tools are not supported, it is added here.
 * Subsequent requests in the same process skip the native path for that model automatically.
 *
 * Not persisted — a process restart gives each model a fresh chance.
 */
object NativeToolsFallbackTracker {
    private val fallbackModels = ConcurrentHashMap.newKeySet<String>()

    fun isFallback(modelId: String): Boolean = modelId in fallbackModels

    fun markFallback(modelId: String, reason: String) {
        if (fallbackModels.add(modelId)) {
            logger.warn { "[NATIVE_TOOLS_FALLBACK] Marking $modelId as fallback: $reason" }
        }
    }

    fun getFallbackSet(): Set<String> = fallbackModels.toSet()

    fun clear() {
        fallbackModels.clear()
    }
}
