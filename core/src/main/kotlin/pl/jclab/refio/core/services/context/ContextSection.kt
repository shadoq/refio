package pl.jclab.refio.core.services.context

/**
 * Priority of a context section. Determines order of inclusion
 * and which sections are dropped first when budget is exceeded.
 *
 * CRITICAL — always included, never dropped (e.g. current user query)
 * HIGH — included unless budget is severely constrained
 * NORMAL — included if budget allows, dropped before HIGH
 * LOW — first to be dropped, supplementary information
 */
enum class ContextPriority {
    CRITICAL,
    HIGH,
    NORMAL,
    LOW
}

enum class ContextSection(val defaultPriority: ContextPriority) {
    SYSTEM_PROMPT(ContextPriority.CRITICAL),
    TOOL_DESCRIPTIONS(ContextPriority.CRITICAL),
    WORKING_MEMORY(ContextPriority.HIGH),
    AGENT_PLANS(ContextPriority.HIGH),
    PROJECT_CONTEXT(ContextPriority.HIGH),
    PROJECT_INSTRUCTIONS(ContextPriority.HIGH),
    RECENT_WORK(ContextPriority.NORMAL),
    USER_CONTEXT(ContextPriority.HIGH),
    RAG_FRAGMENTS(ContextPriority.NORMAL),
    CONVERSATION(ContextPriority.NORMAL),
    REFERENCE(ContextPriority.LOW);

    /**
     * Context layer classification for caching and incremental building.
     * - STABLE: project info, conventions, key files — cached, invalidated on project file change
     * - ACCUMULATED: working memory, modified files — grows across turns
     * - EPHEMERAL: current query, RAG, user refs — rebuilt every turn
     */
    val contextLayer: ContextLayer
        get() = when (this) {
            SYSTEM_PROMPT, TOOL_DESCRIPTIONS, PROJECT_CONTEXT, PROJECT_INSTRUCTIONS, REFERENCE -> ContextLayer.STABLE
            WORKING_MEMORY, RECENT_WORK, AGENT_PLANS -> ContextLayer.ACCUMULATED
            USER_CONTEXT, RAG_FRAGMENTS, CONVERSATION -> ContextLayer.EPHEMERAL
        }
}

enum class ContextLayer {
    STABLE,
    ACCUMULATED,
    EPHEMERAL
}
