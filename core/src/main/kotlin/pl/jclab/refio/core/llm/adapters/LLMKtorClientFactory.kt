package pl.jclab.refio.core.llm.adapters

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger as KtorLogger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.HttpHeaders
import io.ktor.serialization.gson.gson
import pl.jclab.refio.core.logging.DualLogger

/**
 * Fabryka [HttpClient] dla adapterów OpenAI-compatible (OpenAI, OpenRouter, LMStudio, GenericOpenAI).
 *
 * Eliminuje duplikację ~30 LOC per adapter:
 * - ContentNegotiation z Gson (pretty print, serialize nulls),
 * - Logging z auth headers sanitizacją i delegacją do per-adapter [DualLogger],
 * - HttpTimeout skonfigurowany pod streaming (`requestTimeoutMillis = INFINITE`,
 *   `socketTimeoutMillis` resetuje się per chunk — wykrywa tylko martwe połączenia),
 *   z sufitem [STREAM_IDLE_CEILING_MS] na czas bezczynności (idle), niezależnym od total-timeoutu.
 *
 * @param socketTimeoutMs żądany socket idle timeout (reset per chunk). Typowo
 *   `configService.get(API_CALL_TIMEOUT) * 1000` — clampowany do [STREAM_IDLE_CEILING_MS]
 *   przez [resolveIdleTimeoutMs], żeby duży total-timeout nie przekładał się na godzinny
 *   zwis martwego strumienia.
 * @param adapterLogger logger per-adapter do którego Ktor logging deleguje.
 */
internal object LLMKtorClientFactory {

    /**
     * Upper bound on the socket *idle* timeout — the maximum allowed gap between two
     * stream chunks (Ktor `socketTimeoutMillis` resets on every chunk, so this bounds
     * inactivity, NOT total generation time, which stays `requestTimeoutMillis=INFINITE`).
     *
     * Why a ceiling: callers derive `socketTimeoutMs` from `API_CALL_TIMEOUT`, which a user
     * legitimately raises for slow big-model GENERATION (a 122B local model takes 200s+).
     * That accidentally raised the idle timeout to the same huge value, so when a stream went
     * silent the dead connection hung for the full timeout — observed: a `qwen3.5:122b` stream
     * fell silent after the first chunk and hung **53 minutes** until the OS reset the TCP
     * socket (`SocketException: Connection reset`, latency=3193838ms). A healthy stream emits
     * tokens within seconds; even a cold 122B model's first-token gap was ~125s — so any gap
     * beyond 5 minutes means the connection is dead, not slow. Decoupling idle-detection from
     * the total timeout makes a dead stream fail fast (~5 min) instead of hanging for ~an hour.
     * Bump this only if a genuinely larger model shows false aborts on a >5-min first-token gap.
     */
    const val STREAM_IDLE_CEILING_MS = 300_000L

    /**
     * Resolve the effective socket idle timeout: clamp the requested value to
     * [STREAM_IDLE_CEILING_MS]. Non-positive / `INFINITE` requests fall back to the ceiling
     * (a stream must never be allowed to idle forever). Never RAISES a caller's value, so it
     * cannot introduce a new false-abort for callers that already use a short timeout.
     */
    fun resolveIdleTimeoutMs(requestedSocketTimeoutMs: Long): Long =
        if (requestedSocketTimeoutMs <= 0) STREAM_IDLE_CEILING_MS
        else requestedSocketTimeoutMs.coerceAtMost(STREAM_IDLE_CEILING_MS)

    fun create(socketTimeoutMs: Long, adapterLogger: DualLogger): HttpClient {
        val idleTimeoutMs = resolveIdleTimeoutMs(socketTimeoutMs)
        if (idleTimeoutMs < socketTimeoutMs) {
            adapterLogger.debug {
                "[KTOR] Clamped stream idle (socket) timeout ${socketTimeoutMs}ms → ${idleTimeoutMs}ms " +
                    "(ceiling) — total request time stays unbounded; this only caps the max gap between " +
                    "stream chunks so a dead stream aborts fast instead of hanging."
            }
        }
        return HttpClient(CIO) {
            install(ContentNegotiation) {
                gson {
                    setPrettyPrinting()
                    serializeNulls()
                }
            }
            install(Logging) {
                level = LogLevel.INFO
                logger = object : KtorLogger {
                    override fun log(message: String) {
                        adapterLogger.debug { message }
                    }
                }
                sanitizeHeader { header ->
                    header.equals(HttpHeaders.Authorization, ignoreCase = true) ||
                        header.equals("x-api-key", ignoreCase = true) ||
                        header.equals("x-goog-api-key", ignoreCase = true)
                }
            }
            install(HttpTimeout) {
                requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
                connectTimeoutMillis = 30_000L
                socketTimeoutMillis = idleTimeoutMs
            }
        }
    }
}
