package pl.jclab.refio.core.errors

/**
 * Recognises the provider-side failure where the server, not the model, could not parse the
 * model's tool call.
 *
 * Ollama renders function calls through a chat template and returns HTTP 500 when the model closes
 * a tag in the wrong place. The whole call is lost: no content, no tool calls, no raw text to
 * salvage. The same model calls tools correctly on a simpler prompt, so this is template fragility
 * on longer answers, not a capability gap.
 *
 * It matters that this is told apart from an ordinary 500: an ordinary one is worth retrying,
 * while this one is deterministic for the same input, so retrying it just spends the backoff and
 * arrives at the same error. The useful move is to drop the native channel and ask again in the
 * text contract.
 */
object NativeToolTemplateError {

    private val SIGNATURES = listOf("xml syntax error", "element <parameter>")

    private const val MAX_CAUSE_DEPTH = 5

    /** True when [error] or any of its causes is a tool-template parse failure. */
    fun matches(error: Throwable?): Boolean {
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth < MAX_CAUSE_DEPTH) {
            val msg = cause.message?.lowercase() ?: ""
            if (SIGNATURES.any { msg.contains(it) }) return true
            cause = cause.cause
            depth++
        }
        return false
    }
}
