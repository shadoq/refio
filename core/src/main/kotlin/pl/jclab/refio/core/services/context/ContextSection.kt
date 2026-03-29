package pl.jclab.refio.core.services.context

enum class ContextSection {
    SYSTEM_PROMPT,
    TOOL_DESCRIPTIONS,
    WORKING_MEMORY,
    PROJECT_CONTEXT,
    PROJECT_INSTRUCTIONS,
    RECENT_WORK,
    USER_CONTEXT,
    RAG_FRAGMENTS,
    CONVERSATION,
    REFERENCE;

    /**
     * Context layer classification for caching and incremental building.
     * - STABLE: project info, conventions, key files — cached, invalidated on project file change
     * - ACCUMULATED: working memory, modified files — grows across turns
     * - EPHEMERAL: current query, RAG, user refs — rebuilt every turn
     */
    val contextLayer: ContextLayer
        get() = when (this) {
            SYSTEM_PROMPT, TOOL_DESCRIPTIONS, PROJECT_CONTEXT, PROJECT_INSTRUCTIONS, REFERENCE -> ContextLayer.STABLE
            WORKING_MEMORY, RECENT_WORK -> ContextLayer.ACCUMULATED
            USER_CONTEXT, RAG_FRAGMENTS, CONVERSATION -> ContextLayer.EPHEMERAL
        }
}

enum class ContextLayer {
    STABLE,
    ACCUMULATED,
    EPHEMERAL
}
