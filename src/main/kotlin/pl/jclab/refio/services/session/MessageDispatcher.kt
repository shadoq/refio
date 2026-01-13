package pl.jclab.refio.services.session

import pl.jclab.refio.api.models.Message
import pl.jclab.refio.core.api.CoreApiRouter
import pl.jclab.refio.services.logging.dualLogger
import java.util.UUID

class MessageDispatcher(
    private val projectRouter: CoreApiRouter,
    private val stateManager: SessionStateManager
) {

    private val logger = dualLogger("MessageDispatcher")

    suspend fun loadMessages() {
        val currentSession = stateManager.getActiveSession() ?: return
        try {
            logger.info { "[MESSAGES] loadMessages start: taskId=${currentSession.id}" }

            val existingSystemMessages = stateManager.messages.value.filter { msg ->
                msg.role == "system" && msg.taskId == currentSession.id
            }
            val response = projectRouter.getMessages(currentSession.id)
            logger.info { "[MESSAGES] loadMessages response: taskId=${currentSession.id}, count=${response.count}" }

            val dbMessages = response.messages.map { coreMsg ->
                Message(
                    id = coreMsg.id,
                    taskId = coreMsg.taskId,
                    role = coreMsg.role,
                    content = coreMsg.content,
                    tokensIn = coreMsg.tokensIn,
                    tokensOut = coreMsg.tokensOut,
                    costUsd = coreMsg.cost,
                    createdAt = coreMsg.createdAt,
                    metadata = coreMsg.metadata
                )
            }

            val dbMessageIds = dbMessages.map { it.id }.toSet()
            val inMemoryOnlySystemMessages = existingSystemMessages.filterNot { it.id in dbMessageIds }
            val allMessages = (dbMessages + inMemoryOnlySystemMessages).sortedBy { it.createdAt }

            val currentMessages = stateManager.messages.value
            if (!areMessagesEqual(currentMessages, allMessages)) {
                stateManager.updateMessages { allMessages }
                logger.info {
                    "[MESSAGES] Loaded: db=${dbMessages.size}, inMemory=${inMemoryOnlySystemMessages.size}, " +
                        "taskId=${currentSession.id}"
                }
            } else {
                logger.debug { "[MESSAGES] Unchanged, skipping update (count=${allMessages.size})" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to load messages" }
        }
    }

    suspend fun answerQuestion(questionId: String, answer: String) {
        logger.info {
            "[MESSAGES] Answering orchestrator question: questionId=$questionId, answerChars=${answer.length}"
        }

        try {
            val currentSession = stateManager.getActiveSession()
                ?: throw IllegalStateException("No active session")

            val userMessage = Message(
                id = UUID.randomUUID().toString(),
                taskId = currentSession.id,
                role = "user",
                content = answer,
                createdAt = System.currentTimeMillis()
            )
            stateManager.appendMessage(userMessage)

            projectRouter.userInteraction.provideResponse(questionId, answer)

            logger.info { "[MESSAGES] Answer recorded, orchestration will resume" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to answer question: $questionId" }
            throw e
        }
    }

    private fun areMessagesEqual(current: List<Message>, new: List<Message>): Boolean {
        if (current.size != new.size) return false
        return current.zip(new).all { (a, b) ->
            a.id == b.id &&
                a.role == b.role &&
                a.content == b.content &&
                a.tokensIn == b.tokensIn &&
                a.tokensOut == b.tokensOut &&
                a.costUsd == b.costUsd &&
                a.createdAt == b.createdAt &&
                a.metadata == b.metadata &&
                a.isStreaming == b.isStreaming
        }
    }
}
