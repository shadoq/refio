package pl.jclab.refio.core.debug

import java.util.concurrent.ConcurrentHashMap

/**
 * Whether the turn had to give up the provider's tool-calling channel part-way through, and why.
 *
 * A turn that starts on the native channel and finishes on the text contract does more work per
 * step and looks slower for no visible reason. Without this, a comparison reads that slowdown as
 * the agent being worse rather than as one provider request having failed.
 *
 * Reasons are short, stable tokens rather than sentences, because they are meant to be counted.
 */
object TurnNativeToolsTracker {

    /** The server could not parse the model's tool call and rejected the whole request. */
    const val REASON_TEMPLATE_PARSE_ERROR = "TEMPLATE_PARSE_ERROR"

    /** The model answered on the native channel with no content and no calls at all. */
    const val REASON_EMPTY_NATIVE_RESPONSE = "EMPTY_NATIVE_RESPONSE"

    /** The provider said outright that it does not support tool schemas. */
    const val REASON_TOOLS_UNSUPPORTED = "TOOLS_UNSUPPORTED"

    /** A completion guardian re-entered the turn and the loop retried on the text contract. */
    const val REASON_GUARDIAN_REENTRY = "GUARDIAN_REENTRY"

    private val degradations = ConcurrentHashMap<String, String>()

    /**
     * Note that [taskId] fell off the native channel. First reason wins: what knocked the turn off
     * the channel is the cause, and anything after it is a consequence of running without it.
     */
    fun recordDegradation(taskId: String, reason: String) {
        degradations.putIfAbsent(taskId, reason)
    }

    /** Why [taskId] left the native channel, or null when it never did. */
    fun degradationFor(taskId: String): String? = degradations[taskId]

    /** Test-only: forget all recorded degradations. */
    fun reset() {
        degradations.clear()
    }
}
