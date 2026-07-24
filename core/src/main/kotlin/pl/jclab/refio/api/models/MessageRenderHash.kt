package pl.jclab.refio.api.models

import java.util.Objects

/**
 * Pure hashing of a [Message] for the chat render cache.
 *
 * The chat view caches one rendered panel per message id and rebuilds it only when the
 * message's hash changes. The hash therefore has to cover EVERY field that changes what the
 * bubble looks like - otherwise a stale panel survives a real change.
 *
 * Two hashes are exposed:
 *  - [content] - the full render hash. Any difference rebuilds the whole bubble.
 *  - [nonContent] - the same fields minus [Message.content]. When only the growing streamed
 *    content differs between two snapshots the caller can patch the live char counter in place
 *    instead of rebuilding, which keeps a code-editing stream from flickering several times a
 *    second.
 *
 * Lives in :core (not the plugin) so it can be unit-tested without a running IDE - the render
 * pipeline is otherwise only exercisable in a sandbox IDE.
 *
 * [isStreaming] and the agent-identity fields are part of [content] on purpose: when a stream
 * ends the content usually stays byte-identical and only [Message.isStreaming] flips false, so
 * without it in the hash the cache keeps showing the "Generating..." bubble after the stream is
 * already done. The agent-identity fields keep a bubble's cache tied to the agent it belongs to.
 */
object MessageRenderHash {

    fun content(message: Message): Int = Objects.hash(
        message.id,
        message.role,
        message.content,
        message.thinking,
        message.metadata,
        message.toolCallInfo,
        message.toolStreamContent,
        message.isToolStreaming,
        message.pendingApprovalSubtaskId,
        message.metrics,
        message.isStreaming,
        message.agentName,
        message.agentDepth,
        message.agentInstanceId,
    )

    fun nonContent(message: Message): Int = Objects.hash(
        message.id,
        message.role,
        message.thinking,
        message.metadata,
        message.toolCallInfo,
        message.toolStreamContent,
        message.isToolStreaming,
        message.pendingApprovalSubtaskId,
        message.metrics,
        message.isStreaming,
        message.agentName,
        message.agentDepth,
        message.agentInstanceId,
    )
}
