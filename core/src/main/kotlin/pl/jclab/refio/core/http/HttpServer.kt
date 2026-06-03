package pl.jclab.refio.core.http

import com.google.gson.JsonParser
import io.ktor.http.*
import io.ktor.serialization.gson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import pl.jclab.refio.core.api.*
import pl.jclab.refio.core.db.RagContentType
import pl.jclab.refio.core.db.TaskMode
import pl.jclab.refio.core.models.api.ChatRequest
import pl.jclab.refio.core.models.api.LLMParams
import pl.jclab.refio.core.utils.GsonInstance
import pl.jclab.refio.core.logging.dualLogger

private val logger = dualLogger("HttpServer")

/**
 * HTTP wrapper around CoreApiRouter.
 *
 * Exposes the in-process API over HTTP/JSON using Ktor Netty.
 * Provides SSE streaming for chat and agent turn endpoints.
 *
 * Lifecycle: call [startServer] to launch, [stopServer] to shut down.
 */
class HttpServer(
    private val router: CoreApiRouter,
    private val host: String = "127.0.0.1",
    private val port: Int = 8080
) {
    private var server: ApplicationEngine? = null
    private val gson = GsonInstance.gson

    /**
     * Start the embedded Netty HTTP server.
     * Non-blocking -- the server runs on its own thread pool.
     */
    fun startServer() {
        if (server != null) {
            logger.warn { "HTTP server already running" }
            return
        }

        logger.info { "Starting HTTP server on $host:$port" }

        server = embeddedServer(Netty, host = host, port = port) {
            configurePlugins()
            configureRouting()
        }.start(wait = false)

        logger.info { "HTTP server started on http://$host:$port" }
    }

    /**
     * Stop the HTTP server gracefully.
     */
    fun stopServer() {
        server?.let {
            logger.info { "Stopping HTTP server" }
            it.stop(gracePeriodMillis = 1000, timeoutMillis = 5000)
            server = null
            logger.info { "HTTP server stopped" }
        }
    }

    // ========== Plugin Configuration ==========

    private fun Application.configurePlugins() {
        install(ContentNegotiation) {
            gson {
                disableHtmlEscaping()
                serializeNulls()
            }
        }

        install(StatusPages) {
            exception<IllegalArgumentException> { call, cause ->
                logger.warn(cause) { "Bad request: ${cause.message}" }
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(error = "bad_request", message = cause.message ?: "Bad request")
                )
            }
            exception<IllegalStateException> { call, cause ->
                logger.warn(cause) { "Conflict/state error: ${cause.message}" }
                call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse(error = "invalid_state", message = cause.message ?: "Invalid state")
                )
            }
            exception<NoSuchElementException> { call, cause ->
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse(error = "not_found", message = cause.message ?: "Resource not found")
                )
            }
            exception<Throwable> { call, cause ->
                logger.error(cause) { "Internal server error: ${cause.message}" }
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse(error = "internal_error", message = cause.message ?: "Internal server error")
                )
            }
        }
    }

    // ========== Route Configuration ==========

    private fun Application.configureRouting() {
        routing {
            // Health
            get("/api/health") {
                call.respond(router.taskRouter.health())
            }

            // ---------- Tasks ----------
            taskRoutes()

            // ---------- Chat ----------
            chatRoutes()

            // ---------- Agent / Turn ----------
            agentRoutes()

            // ---------- Config ----------
            configRoutes()

            // ---------- RAG ----------
            ragRoutes()
        }
    }

    // ========== Task Routes ==========

    private fun Routing.taskRoutes() {
        post("/api/tasks") {
            val body = call.receiveText()
            val json = JsonParser.parseString(body).asJsonObject

            val request = CreateTaskRequest(
                name = json.get("name")?.asString ?: "Untitled",
                mode = TaskMode.valueOf(json.get("mode")?.asString?.uppercase() ?: "CHAT"),
                projectId = json.get("projectId")?.asString ?: LEGACY_PROJECT_ID,
                projectPath = json.get("projectPath")?.asString ?: LEGACY_PROJECT_PATH,
                readOnly = json.get("readOnly")?.asBoolean,
                requiresPlanApproval = json.get("requiresPlanApproval")?.asBoolean
            )

            val task = router.taskRouter.createTask(request)
            call.respond(HttpStatusCode.Created, task)
        }

        get("/api/tasks") {
            val projectId = call.request.queryParameters["projectId"]
            if (projectId != null) {
                val tasks = router.taskRouter.getTasksForProject(projectId)
                call.respond(ListTasksResponse(tasks = tasks, count = tasks.size))
            } else {
                call.respond(router.taskRouter.listTasks())
            }
        }

        get("/api/tasks/{id}") {
            val id = call.parameters["id"]
                ?: throw IllegalArgumentException("Missing task id")
            val task = router.taskRouter.getTask(id)
                ?: throw NoSuchElementException("Task not found: $id")
            call.respond(task)
        }

        put("/api/tasks/{id}") {
            val id = call.parameters["id"]
                ?: throw IllegalArgumentException("Missing task id")
            val request = call.receive<UpdateTaskRequest>()
            call.respond(router.taskRouter.updateTask(id, request))
        }

        delete("/api/tasks/{id}") {
            val id = call.parameters["id"]
                ?: throw IllegalArgumentException("Missing task id")
            router.taskRouter.deleteTask(id)
            call.respond(HttpStatusCode.NoContent)
        }
    }

    // ========== Chat Routes ==========

    private fun Routing.chatRoutes() {
        // Non-streaming chat
        post("/api/chat") {
            val acceptHeader = call.request.headers[HttpHeaders.Accept] ?: ""
            val body = call.receiveText()
            val json = JsonParser.parseString(body).asJsonObject

            val chatRequest = ChatRequest(
                taskId = json.get("taskId").asString,
                mode = TaskMode.valueOf(json.get("mode")?.asString?.uppercase() ?: "CHAT"),
                input = json.get("input").asString,
                params = LLMParams(
                    model = json.getAsJsonObject("params")?.get("model")?.asString,
                    provider = json.getAsJsonObject("params")?.get("provider")?.asString,
                    temperature = json.getAsJsonObject("params")?.get("temperature")?.asDouble,
                    maxTokens = json.getAsJsonObject("params")?.get("maxTokens")?.asInt
                )
            )

            if (acceptHeader.contains("text/event-stream")) {
                // SSE streaming response
                call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                    val streamCallback: StreamCallback = { chunk ->
                        val event = SseEvent(
                            type = if (chunk.isComplete) "complete" else "delta",
                            data = gson.toJson(
                                mapOf(
                                    "delta" to chunk.delta,
                                    "accumulated" to chunk.accumulated,
                                    "isComplete" to chunk.isComplete,
                                    "source" to chunk.source
                                )
                            )
                        )
                        // Write is called from coroutine context via channel
                        // SSE format: event: type\ndata: json\n\n
                        write("event: ${event.type}\n")
                        write("data: ${event.data}\n\n")
                        flush()
                    }

                    val response = router.chatRouter.chat(chatRequest, stream = true, onChunk = streamCallback)

                    // Send final response as "result" event
                    write("event: result\n")
                    write("data: ${gson.toJson(response)}\n\n")
                    flush()
                }
            } else {
                // Non-streaming JSON response
                val response = router.chatRouter.chat(chatRequest, stream = false)
                call.respond(response)
            }
        }

        post("/api/chat/summarize") {
            val body = call.receiveText()
            val json = JsonParser.parseString(body).asJsonObject
            val taskId = json.get("taskId").asString
            val response = router.chatRouter.summarizeConversation(taskId)
            call.respond(response)
        }

        get("/api/chat/{taskId}/messages") {
            val taskId = call.parameters["taskId"]
                ?: throw IllegalArgumentException("Missing taskId")
            call.respond(router.chatRouter.getMessages(taskId))
        }

        delete("/api/chat/{taskId}/messages/{messageId}") {
            @Suppress("UNUSED_VARIABLE")
            val _taskId = call.parameters["taskId"]
                ?: throw IllegalArgumentException("Missing taskId")
            val messageId = call.parameters["messageId"]
                ?: throw IllegalArgumentException("Missing messageId")
            val deleted = router.chatRouter.deleteMessage(messageId)
            if (deleted) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                throw NoSuchElementException("Message not found: $messageId")
            }
        }

        delete("/api/chat/{taskId}/messages") {
            val taskId = call.parameters["taskId"]
                ?: throw IllegalArgumentException("Missing taskId")
            router.chatRouter.clearHistory(taskId)
            call.respond(HttpStatusCode.NoContent)
        }
    }

    // ========== Agent Routes ==========

    private fun Routing.agentRoutes() {
        // Run a turn (Codex CLI-style turn loop)
        post("/api/agent/turn") {
            val acceptHeader = call.request.headers[HttpHeaders.Accept] ?: ""
            val body = call.receiveText()
            val json = JsonParser.parseString(body).asJsonObject

            val turnRequest = TurnRequest(
                taskId = json.get("taskId").asString,
                userInput = json.get("userInput").asString,
                mode = TaskMode.valueOf(json.get("mode")?.asString?.uppercase() ?: "AGENT"),
                executionMode = json.get("executionMode")?.asString?.let {
                    pl.jclab.refio.core.db.ExecutionMode.valueOf(it.uppercase())
                } ?: pl.jclab.refio.core.db.ExecutionMode.AUTO,
                model = json.get("model")?.asString,
                provider = json.get("provider")?.asString
            )

            if (acceptHeader.contains("text/event-stream")) {
                // SSE streaming for turn execution
                call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                    val streamCallback: StreamCallback = { chunk ->
                        val event = SseEvent(
                            type = if (chunk.isComplete) "complete" else "delta",
                            data = gson.toJson(
                                mapOf(
                                    "delta" to chunk.delta,
                                    "accumulated" to chunk.accumulated,
                                    "isComplete" to chunk.isComplete,
                                    "source" to chunk.source
                                )
                            )
                        )
                        write("event: ${event.type}\n")
                        write("data: ${event.data}\n\n")
                        flush()
                    }

                    val result = router.agentRouter.runTurn(turnRequest, streamCallback = streamCallback)

                    write("event: result\n")
                    write("data: ${gson.toJson(result)}\n\n")
                    flush()
                }
            } else {
                val result = router.agentRouter.runTurn(turnRequest)
                call.respond(result)
            }
        }
    }

    // ========== Config Routes ==========

    private fun Routing.configRoutes() {
        get("/api/config/models") {
            val provider = call.request.queryParameters["provider"]
            call.respond(router.configRouter.getModels(provider))
        }

        get("/api/config/models/default") {
            val operation = call.request.queryParameters["operation"]
                ?.let { ModelOperation.valueOf(it.uppercase()) }
                ?: ModelOperation.DEFAULT
            val taskId = call.request.queryParameters["taskId"]
            call.respond(router.configRouter.getDefaultModel(operation, taskId))
        }

        post("/api/config/models/default") {
            val request = call.receive<SetDefaultModelRequest>()
            val taskId = call.request.queryParameters["taskId"]
            call.respond(router.configRouter.setDefaultModel(request, taskId))
        }

        post("/api/config/test-connection") {
            val body = call.receiveText()
            val json = JsonParser.parseString(body).asJsonObject
            val provider = json.get("provider").asString
            val config = mutableMapOf<String, String>()
            json.getAsJsonObject("config")?.entrySet()?.forEach { (key, value) ->
                config[key] = value.asString
            }
            call.respond(router.configRouter.testProviderConnection(provider, config))
        }
    }

    // ========== RAG Routes ==========

    private fun Routing.ragRoutes() {
        get("/api/rag/search") {
            val query = call.request.queryParameters["query"]
                ?: throw IllegalArgumentException("Missing 'query' parameter")
            val model = call.request.queryParameters["model"] ?: "ollama/nomic-embed-text"
            val topK = call.request.queryParameters["topK"]?.toIntOrNull() ?: 5
            val contentType = call.request.queryParameters["contentType"]?.let {
                RagContentType.valueOf(it.uppercase())
            }

            val results = router.ragRouter.searchRag(query, model, topK, contentType)
            call.respond(mapOf("results" to results, "count" to results.size))
        }

        post("/api/rag/index") {
            router.ragRouter.indexProjectForRag()
            call.respond(mapOf("status" to "indexing_started", "message" to "Project indexing initiated"))
        }
    }
}

// ========== Supporting Models ==========

/**
 * Standard error response body.
 */
data class ErrorResponse(
    val error: String,
    val message: String
)

/**
 * Internal SSE event helper.
 */
private data class SseEvent(
    val type: String,
    val data: String
)
