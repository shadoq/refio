package pl.jclab.refio.core.context.mcp

/**
 * Pure decision: does a connected MCP server contribute agent-callable tools, and if not, why?
 *
 * Extracted from [MCPManager] so the rule is unit-testable without the singleton / DB / network.
 * Previously this gate lived inline at the registration call site and failed silently: a
 * CONTEXT-mode server (e.g. the `context7` preset) would connect successfully yet never expose
 * a `mcp_<id>_*` tool, and nothing told the user why the agent "couldn't use it".
 *
 * Agent tools are registered only when ALL hold:
 *  - `toolsEnabled = true`
 *  - `toolsExposureMode = TOOLS` — CONTEXT-mode servers are consumed via `@serverId` context, not tools
 *  - the server advertised the `tools` capability during `initialize`
 */
object MCPToolExposure {

    /** True when the server should contribute agent-callable tools. */
    fun exposesAgentTools(config: MCPServerConfig, capabilities: MCPServerCapabilities?): Boolean =
        agentToolUnavailableReason(config, capabilities) == null

    /**
     * Human-readable reason the server exposes no agent tools, or null when it does.
     * Used both to gate registration and to surface a fail-loud signal (log + settings UI).
     */
    fun agentToolUnavailableReason(config: MCPServerConfig, capabilities: MCPServerCapabilities?): String? {
        if (!config.toolsEnabled) {
            return "toolsEnabled=false"
        }
        val mode = config.toolsExposureMode ?: MCPToolsExposureMode.TOOLS
        if (mode != MCPToolsExposureMode.TOOLS) {
            return "exposure mode is $mode — agent tools are only registered in TOOLS mode " +
                "(use @${config.id} as a context source, or switch exposure to TOOLS so the agent can call it)"
        }
        if (capabilities?.tools != true) {
            return "server did not advertise the 'tools' capability during initialize"
        }
        return null
    }
}
