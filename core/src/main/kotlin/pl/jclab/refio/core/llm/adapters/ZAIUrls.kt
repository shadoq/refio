package pl.jclab.refio.core.llm.adapters

/**
 * Known Z.AI base URLs and normalization.
 *
 * Z.AI has published several endpoints over time; we canonicalize to [DEFAULT] so that
 * configurations pointing at older URLs keep working without per-caller branching.
 */
object ZAIUrls {
    const val DEFAULT: String = "https://api.z.ai/api/coding/paas/v4"
    const val LEGACY: String = "https://api.z.ai/v1"
    const val GENERAL: String = "https://api.z.ai/api/paas/v4"

    fun normalize(baseUrl: String?): String {
        val trimmed = baseUrl?.trim()?.trimEnd('/')
        return when {
            trimmed.isNullOrEmpty() -> DEFAULT
            trimmed.equals(LEGACY, ignoreCase = true) -> DEFAULT
            trimmed.equals(GENERAL, ignoreCase = true) -> DEFAULT
            else -> trimmed
        }
    }
}
