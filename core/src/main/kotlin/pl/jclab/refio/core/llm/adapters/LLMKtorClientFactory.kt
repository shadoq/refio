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
 *   `socketTimeoutMillis` resetuje się per chunk — wykrywa tylko martwe połączenia).
 *
 * @param socketTimeoutMs socket timeout (reset per chunk). Typowo `configService.get(API_CALL_TIMEOUT) * 1000`.
 * @param adapterLogger logger per-adapter do którego Ktor logging deleguje.
 */
internal object LLMKtorClientFactory {

    fun create(socketTimeoutMs: Long, adapterLogger: DualLogger): HttpClient =
        HttpClient(CIO) {
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
                socketTimeoutMillis = socketTimeoutMs
            }
        }
}
