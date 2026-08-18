package pl.jclab.refio.core.llm

/**
 * Finish reasons Refio produces itself, for states no provider reports.
 *
 * Providers use their own vocabulary ("stop", "length", "tool_calls", "end_turn", "load", ...);
 * values here are chosen not to collide with any of them.
 */
object StreamFinishReason {

    /**
     * The response stream ended without its protocol terminator (`[DONE]`, `message_stop`,
     * `done=true`) after content had already been streamed, so the text we hold is a prefix of
     * the answer rather than a complete reply.
     *
     * Deliberately distinct from "length": the cause is a dropped connection, not an exhausted
     * output budget, and only the latter is worth reporting to the user as a limit problem.
     * A partial reply is still returned to the caller — recording the cut-off is what lets the
     * turn tell an unparseable envelope apart from a model that simply answered in prose.
     */
    const val TRUNCATED = "truncated"
}
