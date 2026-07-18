package pl.jclab.refio.core.agents.events

import java.util.concurrent.ConcurrentHashMap

/**
 * Session-scoped lookup of per-agent inboxes, keyed by (sessionId, agentName).
 *
 * Launchers (MultiAgentRunner today, future interactive TUI or plugin UI) populate
 * the registry when an agent starts and clear the entry when it completes.
 * [pl.jclab.refio.core.services.AgentTurnLoop] and [pl.jclab.refio.core.tools.implementations.AnswerMessageTool]
 * read it without caring who the launcher was — the registry is the stable seam.
 */
class AgentInboxRegistry {
    data class Key(val sessionId: String, val agentName: String)

    private val inboxes = ConcurrentHashMap<Key, AgentMessageInbox>()

    fun register(inbox: AgentMessageInbox) {
        inboxes[Key(inbox.sessionId, inbox.agentName)] = inbox
    }

    fun unregister(sessionId: String, agentName: String) {
        inboxes.remove(Key(sessionId, agentName))
    }

    fun find(sessionId: String, agentName: String): AgentMessageInbox? =
        inboxes[Key(sessionId, agentName)]

    /** Used by SendMessageTool to fail fast on unknown peer names. */
    fun isRegistered(sessionId: String, agentName: String): Boolean =
        find(sessionId, agentName) != null

    /** Diagnostic helper — listing peers in a session for error messages. */
    fun listAgents(sessionId: String): List<String> =
        inboxes.keys.filter { it.sessionId == sessionId }.map { it.agentName }
}
