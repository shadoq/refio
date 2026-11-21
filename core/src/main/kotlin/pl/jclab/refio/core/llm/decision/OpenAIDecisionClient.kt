package pl.jclab.refio.core.llm.decision

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import pl.jclab.refio.core.llm.calculateCost
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.core.security.NetworkPolicy
import pl.jclab.refio.core.utils.GsonInstance.gson

private val logger = dualLogger("OpenAIDecisionClient")

/**
 * OpenAI Decisions API (`POST /v1/decisions`, model `gpt-6-luna`). Same idea as Ollama's
 * `/v1/systemone`, different JSON: questions and answers are arrays and a yes/no question is a
 * `predicate`. Only input tokens are billed.
 */
class OpenAIDecisionClient(
    private val apiKeyProvider: () -> String?,
    private val networkPolicy: NetworkPolicy?,
    private val url: String = DECISIONS_URL,
    private val httpClient: HttpClient = defaultHttpClient()
) : DecisionClient, AutoCloseable {

    override suspend fun askYesNo(
        model: String,
        state: String,
        questions: Map<String, YesNoQuestion>,
        taskId: String?,
        source: String
    ): DecisionResult {
        networkPolicy?.assertRemoteEgressAllowed("decision model", url, taskId)
        val apiKey = apiKeyProvider()?.takeIf { it.isNotBlank() }
            ?: throw DecisionCallException("OpenAI API key is not configured (Settings -> Providers -> OpenAI)")

        val requestJson = gson.toJson(buildRequest(model, state, questions))
        val start = System.currentTimeMillis()
        val response = try {
            httpClient.post(url) {
                header("Authorization", "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(requestJson)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val latency = (System.currentTimeMillis() - start).toInt()
            logger.apiError(PROVIDER, model, url, requestJson, null, e, latency, taskId = taskId, source = source)
            throw DecisionCallException("OpenAI decision call failed: ${e.message}", cause = e)
        }
        val body = response.bodyAsText()
        val latencyMs = System.currentTimeMillis() - start

        if (!response.status.isSuccess()) {
            val error = DecisionCallException(
                "OpenAI decision model $model returned HTTP ${response.status.value}: ${body.take(300)}",
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
            costUsd = calculateCost(PROVIDER, model, result.inputTokens, 0),
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
        const val PROVIDER = "openai"
        const val DECISIONS_URL = "https://api.openai.com/v1/decisions"
        private const val REQUEST_TIMEOUT_MS = 60_000L

        private fun defaultHttpClient() = HttpClient(CIO) {
            engine {
                requestTimeout = REQUEST_TIMEOUT_MS
                endpoint {
                    connectTimeout = 5_000
                    socketTimeout = REQUEST_TIMEOUT_MS
                }
            }
        }

        internal fun buildRequest(model: String, state: String, questions: Map<String, YesNoQuestion>): Map<String, Any> =
            mapOf(
                "model" to model,
                "input" to state,
                "questions" to questions.map { (name, q) ->
                    mapOf("name" to name, "type" to "predicate", "instructions" to q.instructions)
                }
            )

        internal fun parseResponse(body: String, expected: Set<String>, latencyMs: Long): DecisionResult {
            val root = parseObject(body)
            val answers = root.getAsJsonArray("answers")
                ?: throw DecisionCallException("OpenAI decision response has no answers: ${body.take(200)}")
            val byName = answers.filter { it.isJsonObject }.map { it.asJsonObject }
                .associateBy { it.get("name")?.asString }
            val probabilities = expected.associateWith { name ->
                val answer = byName[name]
                    ?: throw DecisionCallException("OpenAI decision response has no answer for '$name'")
                val type = answer.get("type")?.asString
                if (type != "predicate") {
                    throw DecisionCallException("OpenAI decision answer for '$name' is '$type', not a probability")
                }
                answer.get("probability")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
                    ?: throw DecisionCallException("OpenAI decision answer for '$name' has no probability")
            }
            val inputTokens = root.getAsJsonObject("usage")?.get("input_tokens")?.asInt ?: 0
            return DecisionResult(probabilities, inputTokens, latencyMs)
        }

        private fun parseObject(body: String): JsonObject =
            try {
                JsonParser.parseString(body).asJsonObject
            } catch (e: Exception) {
                throw DecisionCallException("Decision response is not a JSON object: ${body.take(200)}", cause = e)
            }
    }
}
