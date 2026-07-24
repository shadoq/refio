package pl.jclab.refio.api.models

/**
 * Decides, for a chat transcript, which messages start a new subagent group and therefore render
 * an "Agent: <name>" header above their bubble.
 *
 * A header is shown on the FIRST message of each contiguous run that belongs to the same agent
 * invocation. Runs are keyed by [Message.agentInstanceId] (falling back to [Message.agentName] for
 * legacy rows without an instance id) so two separate invocations of the same subagent stay visually
 * separate, and any non-agent message in between resets the run.
 *
 * This is a PURE function of the ordered list. The previous implementation tracked the "current
 * group" in mutable renderer state that was mutated as bubbles were drawn - but cached bubbles were
 * returned WITHOUT going through the renderer, so the tracked group drifted out of sync with the
 * actual list and headers appeared out of order (or vanished) on partial re-renders. Deriving the
 * decision from the list up-front removes that whole class of bug and makes it unit-testable without
 * a running IDE.
 */
object AgentGrouping {

    /** The grouping key for a message, or null when it does not belong to any subagent run. */
    fun groupKey(message: Message): String? =
        message.agentName?.let { name -> message.agentInstanceId ?: name }

    /**
     * One flag per message, aligned by index with [messages]: true when the message should render an
     * agent header (it opens a new run), false otherwise.
     */
    fun showHeaderFlags(messages: List<Message>): List<Boolean> {
        var previousKey: String? = null
        return messages.map { message ->
            val key = groupKey(message)
            val show = key != null && key != previousKey
            previousKey = key
            show
        }
    }
}
