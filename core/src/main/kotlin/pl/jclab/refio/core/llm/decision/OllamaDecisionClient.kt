package pl.jclab.refio.core.llm.decision

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.security.NetworkPolicy
import pl.jclab.refio.core.services.OllamaRequestGate
import pl.jclab.refio.core.utils.GsonInstance.gson

private val logger = dualLogger("OllamaDecisionClient")

/**
 * Decision models served by Ollama through `POST /v1/systemone` (Ollama 0.35+). Such a model has
 * only the `decision` capability: `/api/chat` answers it with HTTP 400, so it cannot go through
 * the chat adapter.
 *
 * The call shares [OllamaRequestGate] with chat requests to the same endpoint, so a judge call
 * waits for the main model instead of competing with it for the GPU.
 */
class OllamaDecisionClient(
    private val endpoint: String,
    private val keepAliveSeconds: Int?,
    private val networkPolicy: NetworkPolicy?,
    private val httpClient: HttpClient = defaultHttpClient()
) : DecisionClient, AutoCloseable {

    override suspend fun askYesNo(
        model: String,
        state: String,
        questions: Map<String, YesNoQuestion>,
        taskId: String?,
        source: String
    ): DecisionResult {
        val url = endpoint.trimEnd('/') + SYSTEMONE_PATH
        networkPolicy?.assertRemoteEgressAllowed("decision model", url, taskId)

        val requestJson = gson.toJson(buildRequest(model, state, questions, keepAliveSeconds))
        val start = System.currentTimeMillis()
        val response = try {
            OllamaRequestGate.withPermit(endpoint) {
                httpClient.post(url) {
                    contentType(ContentType.Application.Json)
                    setBody(requestJson)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val latency = (System.currentTimeMillis() - start).toInt()
            logger.apiError(PROVIDER, model, url, requestJson, null, e, latency, taskId = taskId, source = source)
            throw DecisionCallException("Ollama decision call to $url failed: ${e.message}", cause = e)
        }
        val body = response.bodyAsText()
        val latencyMs = System.currentTimeMillis() - start

        if (!response.status.isSuccess()) {
            val error = DecisionCallException(
                "Ollama decision model $model returned HTTP ${response.status.value}: ${errorText(body)}",
                httpStatus = response.status.value
            )
            logger.apiError(PROVIDER, model, url, requestJson, response.status.value, error, latencyMs.toInt(), taskId = taskId, source = source)
            throw error
        }

        val result = parseResponse(body, questions.keys, latencyMs)
        logger.apiResponse(
            provider = PROVIDER,
            model = model,
            endpoint = url,
            requestJson = requestJson,
            responseJson = body,
            httpStatus = response.status.value,
            inputTokens = result.inputTokens,
            outputTokens = 0,
            costUsd = 0.0,
            latencyMs = latencyMs.toInt(),
            taskId = taskId,
            source = source
        )
        return result
    }

    override fun close() {
        httpClient.close()
    }

    companion object {
        const val PROVIDER = "ollama"
        const val SYSTEMONE_PATH = "/v1/systemone"
        private const val REQUEST_TIMEOUT_MS = 120_000L
        private const val CONNECT_TIMEOUT_MS = 2_000L

        private fun defaultHttpClient() = HttpClient(CIO) {
            engine {
                // A cold model load can take tens of seconds; a warm call takes about one.
                requestTimeout = REQUEST_TIMEOUT_MS
                endpoint {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    socketTimeout = REQUEST_TIMEOUT_MS
                }
            }
        }

        internal fun buildRequest(
            model: String,
            state: String,
            questions: Map<String, YesNoQuestion>,
            keepAliveSeconds: Int?
        ): Map<String, Any> = buildMap {
            put("model", model)
            put("state", state)
            put("questions", questions.mapValues { (_, q) -> mapOf("type" to "noul", "instructions" to q.instructions) })
            if (keepAliveSeconds != null) {
                put("keep_alive", "${keepAliveSeconds}s")
            }
        }

        internal fun parseResponse(body: String, expected: Set<String>, latencyMs: Long): DecisionResult {
            val root = parseObject(body)
            val answers = root.getAsJsonObject("answers")
                ?: throw DecisionCallException("Ollama decision response has no answers: ${body.take(200)}")
            val probabilities = expected.associateWith { name ->
                val answer = answers.get(name)?.takeIf { it.isJsonObject }?.asJsonObject
                answer?.get("noul")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
                    ?: throw DecisionCallException("Ollama decision response has no yes/no answer for '$name'")
            }
            val inputTokens = root.getAsJsonObject("usage")?.get("input_tokens")?.asInt ?: 0
            return DecisionResult(probabilities, inputTokens, latencyMs)
        }

        /** The `error` field of an Ollama error body, or the body itself when it is not that shape. */
        internal fun errorText(body: String): String =
            runCatching { JsonParser.parseString(body).asJsonObject.get("error")?.asString }.getOrNull()
                ?: body.take(300)

        private fun parseObject(body: String): JsonObject =
            try {
                JsonParser.parseString(body).asJsonObject
            } catch (e: Exception) {
                throw DecisionCallException("Decision response is not a JSON object: ${body.take(200)}", cause = e)
            }
    }
}
