package pl.jclab.refio.core.context.mcp

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Encodes the rule that decides whether a connected MCP server contributes
 * agent-callable tools. This rule used to live inline in MCPManager and gated
 * tool registration silently, so a CONTEXT-mode server (e.g. the context7 preset)
 * connected fine yet the agent never received a tool — with no signal why.
 */
class MCPToolExposureTest {

    private fun config(
        toolsEnabled: Boolean = true,
        exposure: MCPToolsExposureMode? = MCPToolsExposureMode.TOOLS
    ) = MCPServerConfig(
        id = "context7",
        type = MCPServerType.HTTP_SSE,
        toolsEnabled = toolsEnabled,
        toolsExposureMode = exposure
    )

    private val toolsCapable = MCPServerCapabilities(tools = true)

    @Test
    fun `exposes agent tools when TOOLS mode and server advertises tools`() {
        assertTrue(MCPToolExposure.exposesAgentTools(config(), toolsCapable))
        assertNull(MCPToolExposure.agentToolUnavailableReason(config(), toolsCapable))
    }

    @Test
    fun `null exposure defaults to TOOLS`() {
        assertTrue(MCPToolExposure.exposesAgentTools(config(exposure = null), toolsCapable))
    }

    @Test
    fun `CONTEXT mode does not expose agent tools and explains why`() {
        val cfg = config(exposure = MCPToolsExposureMode.CONTEXT)
        assertFalse(MCPToolExposure.exposesAgentTools(cfg, toolsCapable))
        val reason = MCPToolExposure.agentToolUnavailableReason(cfg, toolsCapable)
        assertTrue(reason!!.contains("CONTEXT"), "reason should name the mode: $reason")
        assertTrue(reason.contains("@context7"), "reason should hint the @-mention: $reason")
    }

    @Test
    fun `disabled tools are not exposed`() {
        val cfg = config(toolsEnabled = false)
        assertFalse(MCPToolExposure.exposesAgentTools(cfg, toolsCapable))
        assertTrue(MCPToolExposure.agentToolUnavailableReason(cfg, toolsCapable)!!.contains("toolsEnabled"))
    }

    @Test
    fun `TOOLS mode without tools capability is not exposed`() {
        assertFalse(MCPToolExposure.exposesAgentTools(config(), MCPServerCapabilities(tools = false)))
        assertFalse(MCPToolExposure.exposesAgentTools(config(), null))
        assertTrue(MCPToolExposure.agentToolUnavailableReason(config(), null)!!.contains("capability"))
    }

    @Test
    fun `context7 preset is agent-callable by default`() {
        val preset = MCPServerPresets.getById("context7")
        requireNotNull(preset) { "context7 preset must exist" }
        val cfg = preset.build(null)
        assertEquals(MCPToolsExposureMode.TOOLS, cfg.toolsExposureMode)
        assertTrue(MCPToolExposure.exposesAgentTools(cfg, toolsCapable))
    }
}
