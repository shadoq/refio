package pl.jclab.refio.core.services.turn

import pl.jclab.refio.core.utils.GsonInstance

/**
 * Types of the SYSTEM rows the harness writes to steer the agent. A row with one of these types in
 * its metadata is shown to the model as a user message prefixed with `[harness]`: providers drop the
 * system role from the message list, so an untyped steering row was stored but never read.
 */
object HarnessMessageTypes {
    const val GUARDIAN_NUDGE = "guardian_nudge"
    const val FORMAT_NUDGE = "format_nudge"
    const val VERIFICATION_FEEDBACK = "verification_feedback"
    const val INTERJECTION_NOTICE = "interjection_notice"
    const val TOOL_REJECTION = "tool_rejection"
    const val PEER_RESPONSE = "peer_response"
    const val HANDOFF_NOTE = "handoff_note"
    const val LONG_TASK_ROUND = "long_task_round"

    const val PREFIX = "[harness]"

    private val ALL = setOf(
        GUARDIAN_NUDGE, FORMAT_NUDGE, VERIFICATION_FEEDBACK, INTERJECTION_NOTICE,
        TOOL_REJECTION, PEER_RESPONSE, HANDOFF_NOTE, LONG_TASK_ROUND
    )

    /** The `type` field of a row's JSON metadata, or null when the metadata is not such JSON. */
    fun typeOf(metadata: String?): String? {
        if (metadata.isNullOrBlank() || !metadata.trimStart().startsWith("{")) return null
        return runCatching { GsonInstance.gson.fromJson(metadata, Map::class.java)?.get("type") as? String }.getOrNull()
    }

    fun isHarnessMessage(metadata: String?): Boolean = typeOf(metadata) in ALL

    fun metadataFor(type: String, extra: Map<String, Any?> = emptyMap()): String =
        GsonInstance.gson.toJson(linkedMapOf<String, Any?>("type" to type) + extra)
}
