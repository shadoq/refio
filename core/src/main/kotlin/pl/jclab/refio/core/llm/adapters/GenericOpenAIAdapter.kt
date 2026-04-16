package pl.jclab.refio.core.llm.adapters

import io.ktor.client.HttpClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.core.errors.RefioError
import pl.jclab.refio.core.services.ConfigService

/**
 * Open, generic OpenAI-compatible chat adapter for:
 * - `provider: custom_openai` (any OpenAI-compatible endpoint),
 * - `provider: zai` via the [ZAIAdapter] subclass (adds rate-limit serialization).
 *
 * Most logic lives in [OpenAICompatibleAdapter]; this class only supplies the
 * per-provider config keys, enforces `api_key` when required, and (for `zai`)
 * serializes requests through a mutex with retry after HTTP 429.
 */
open class GenericOpenAIAdapter(
    model: String,
    providerName: String = "generic_openai",
    configService: ConfigService? = null,
    taskId: String? = null,
    subtaskId: String? = null,
    source: String? = null,
    private val baseUrlOverride: String? = null,
    private val apiKeyOverride: String? = null,
    requireApiKey: Boolean = false,
    private val defaultBaseUrl: String? = null,
    httpClientOverride: HttpClient? = null,
) : OpenAICompatibleAdapter(
    model = model,
    providerName = providerName,
    configService = configService,
    taskId = taskId,
    subtaskId = subtaskId,
    source = source,
    requireApiKey = requireApiKey,
    httpClientOverride = httpClientOverride,
) {

    companion object {
        private const val ZAI_COOLDOWN_MS = 5_000L
        private const val ZAI_RATE_LIMIT_RETRY_DELAY_MS = 15_000L
        private val zaiRequestMutex = Mutex()
        private var zaiNextAllowedAtMs: Long = 0L
    }

    override fun resolveBaseUrl(): String {
        val configured = baseUrlOverride?.takeIf { it.isNotBlank() }
            ?: when (providerName) {
                "zai" -> configService?.getTyped(ConfigKeys.PROVIDER_ZAI_BASE_URL)
                else -> configService?.getTyped(ConfigKeys.PROVIDER_CUSTOM_OPENAI_BASE_URL)
            }
            ?: when (providerName) {
                "zai" -> System.getProperty("ZAI_BASE_URL") ?: System.getenv("ZAI_BASE_URL")
                else -> System.getProperty("CUSTOM_OPENAI_BASE_URL") ?: System.getenv("CUSTOM_OPENAI_BASE_URL")
            }
            ?: defaultBaseUrl

        return configured?.trimEnd('/')
            ?: throw RefioError.ProviderNotConfigured(providerName, "base_url")
    }

    override fun resolveApiKey(): String? {
        val key = apiKeyOverride?.takeIf { it.isNotBlank() }
            ?: when (providerName) {
                "zai" -> configService?.getTyped(ConfigKeys.PROVIDER_ZAI_API_KEY)
                else -> configService?.getTyped(ConfigKeys.PROVIDER_CUSTOM_OPENAI_API_KEY)
            }
            ?: when (providerName) {
                "zai" -> System.getProperty("ZAI_API_KEY") ?: System.getenv("ZAI_API_KEY")
                else -> System.getProperty("CUSTOM_OPENAI_API_KEY") ?: System.getenv("CUSTOM_OPENAI_API_KEY")
            }

        if (requireApiKey && key.isNullOrBlank()) {
            throw RefioError.ProviderNotConfigured(providerName, "api_key")
        }
        return key
    }

    override suspend fun <T> withProviderRateLimit(endpoint: String, block: suspend () -> T): T {
        if (providerName != "zai") return block()
        return zaiRequestMutex.withLock {
            val now = System.currentTimeMillis()
            val waitMs = (zaiNextAllowedAtMs - now).coerceAtLeast(0L)
            if (waitMs > 0) {
                logger.info { "[ZAI] Waiting ${waitMs}ms before next request: $endpoint" }
                delay(waitMs)
            }
            try {
                block()
            } finally {
                zaiNextAllowedAtMs = System.currentTimeMillis() + ZAI_COOLDOWN_MS
            }
        }
    }

    override suspend fun <T> executeWithRateLimitRetry(endpoint: String, block: suspend () -> T): T {
        if (providerName != "zai") return block()
        return try {
            block()
        } catch (e: RefioError.LLMRateLimit) {
            logger.warn {
                "[ZAI] Rate limit hit for $endpoint. Waiting ${ZAI_RATE_LIMIT_RETRY_DELAY_MS}ms before retry"
            }
            zaiNextAllowedAtMs = maxOf(
                zaiNextAllowedAtMs,
                System.currentTimeMillis() + ZAI_RATE_LIMIT_RETRY_DELAY_MS
            )
            delay(ZAI_RATE_LIMIT_RETRY_DELAY_MS)
            block()
        }
    }

    override fun mapHttpError(httpStatus: Int, rawBody: String): RefioError {
        val parsed = parseProviderError(rawBody)
        val message = parsed.message ?: rawBody
        val businessCode = parsed.code
        val finalMessage = if (providerName == "zai") {
            buildZAIErrorMessage(httpStatus, businessCode, message)
        } else {
            message
        }
        return when (httpStatus) {
            401, 403 -> RefioError.LLMAuthentication(providerName, model, IllegalStateException(finalMessage))
            429 -> RefioError.LLMRateLimit(providerName, null, IllegalStateException(finalMessage))
            434 -> RefioError.LLMAuthentication(providerName, model, IllegalStateException(finalMessage))
            else -> RefioError.LLMError(providerName, model, IllegalStateException(finalMessage))
        }
    }

    internal fun buildZAIErrorMessage(httpStatus: Int, businessCode: String?, message: String): String {
        val normalized = businessCode?.trim()
        val detail = when (normalized) {
            "1000", "1001", "1002", "1003", "1004" -> "Authentication failed or token expired"
            "1110" -> "Account is inactive"
            "1111" -> "Account does not exist"
            "1112", "1121" -> "Account has been locked"
            "1113" -> "Account balance exhausted"
            "1120" -> "Unable to access account temporarily"
            "1210", "1213", "1214", "1215" -> "Invalid request parameters"
            "1211" -> "Model does not exist"
            "1212" -> "Model does not support this API method"
            "1220" -> "No permission to access this API"
            "1221" -> "API has been taken offline"
            "1222" -> "API does not exist"
            "1230" -> "API call process error"
            "1231" -> "An identical request is already in progress"
            "1234" -> "Network error on provider side"
            "1301" -> "Request blocked by safety policy"
            "1302" -> "API concurrency limit exceeded"
            "1303" -> "API frequency limit exceeded"
            "1304" -> "Daily API call limit reached"
            "1305" -> "API rate limit triggered"
            "1308" -> "Usage limit reached"
            "1309" -> "GLM Coding Plan expired"
            "1310" -> "Weekly or monthly limit exhausted"
            else -> when (httpStatus) {
                401, 403 -> "Authentication failure or token timeout"
                429 -> "Rate limit or account quota restriction"
                434 -> "No API permission"
                else -> null
            }
        }

        return buildString {
            if (!normalized.isNullOrBlank()) {
                append("Z.AI error ")
                append(normalized)
                append(": ")
            }
            if (!detail.isNullOrBlank()) {
                append(detail)
                if (message.isNotBlank() && !message.contains(detail, ignoreCase = true)) {
                    append(". ")
                }
            }
            if (message.isNotBlank()) {
                append(message)
            } else if (detail.isNullOrBlank()) {
                append("HTTP ")
                append(httpStatus)
            }
        }
    }

}
