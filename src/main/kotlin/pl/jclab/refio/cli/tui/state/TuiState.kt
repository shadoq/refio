package pl.jclab.refio.cli.tui.state

/**
 * Unified TUI state — all data needed to render the full UI.
 * Pure data classes, no Compose/Swing dependencies.
 */
data class TuiState(
    val screen: TuiScreen = TuiScreen.MAIN,
    val activeTab: TuiTab = TuiTab.CHAT,
    val messages: List<TuiChatMessage> = emptyList(),
    val isStreaming: Boolean = false,
    val agents: List<TuiAgentState> = emptyList(),
    val steps: List<TuiStep> = emptyList(),
    val contextSections: List<TuiContextSection> = emptyList(),
    val logs: List<TuiLogEntry> = emptyList(),
    val apiLogs: List<TuiApiLogEntry> = emptyList(),
    val debugInfo: TuiDebugInfo = TuiDebugInfo(),
    val pendingApprovals: List<TuiPendingApproval> = emptyList(),
    val sessions: List<TuiSessionEntry> = emptyList(),
    val mode: String = "CHAT",
    val model: String? = null,
    val executionMode: String = "AUTO", // AUTO or INTERACTIVE
    val thinkingEnabled: Boolean = false,
    val noEgressEnabled: Boolean = false,
    val inputBuffer: String = "",
    val totalCostUsd: Double = 0.0,
    val totalTokens: Long = 0,
    val scrollOffset: Int = 0,
    val settingsTab: Int = 0,
    val autocompleteVisible: Boolean = false,
    val autocompleteCandidates: List<String> = emptyList(),
    val autocompleteSelectedIndex: Int = 0,
    val cursorPosition: Int = 0
)

enum class TuiScreen { MAIN, HISTORY, SETTINGS }

enum class TuiTab(val label: String) {
    CHAT("Chat"),
    STEPS("Steps"),
    CONTEXT("Context"),
    RAG("RAG"),
    LOGS("Logs"),
    DEBUG("Debug"),
    API_LOGS("API")
}

data class TuiChatMessage(
    val id: String,
    val timestamp: Long,
    val role: String,
    val content: String,
    val agentId: String? = null,
    val agentName: String? = null,
    val agentColorIndex: Int? = null,
    val isStreaming: Boolean = false,
    val messageType: TuiMessageType = TuiMessageType.TEXT,
    val tokensIn: Int = 0,
    val tokensOut: Int = 0,
    val costUsd: Double = 0.0
)

enum class TuiMessageType {
    TEXT,
    AGENT_STARTED,
    AGENT_COMPLETED,
    AGENT_FAILED,
    DATA_EXCHANGE,
    APPROVAL_REQUEST,
    ARTIFACT
}

data class TuiAgentState(
    val id: String,
    val name: String,
    val status: String,
    val colorIndex: Int,
    val currentPhase: String? = null,
    val dependsOn: List<String> = emptyList(),
    val tokensUsed: Long = 0,
    val costUsd: Double = 0.0
)

data class TuiStep(
    val id: String,
    val name: String,
    val status: String,
    val details: String = "",
    val expanded: Boolean = false
)

data class TuiContextSection(
    val name: String,
    val category: String,
    val tokensUsed: Int,
    val tokensMax: Int,
    val percentage: Double = 0.0,
    val colorIndex: Int = 0, // index into a palette for token bar visualization
    val content: String? = null // truncated content preview
)

data class TuiLogEntry(
    val timestamp: String,
    val level: String,
    val message: String
)

data class TuiApiLogEntry(
    val timestamp: String,
    val provider: String,
    val model: String,
    val tokensIn: Long,
    val tokensOut: Long,
    val costUsd: Double
)

data class TuiDebugInfo(
    val sessionId: String = "",
    val mode: String = "CHAT",
    val model: String = "default",
    val status: String = "IDLE",
    val tokensIn: Long = 0,
    val tokensOut: Long = 0,
    val costUsd: Double = 0.0,
    val messageCount: Int = 0,
    val connected: Boolean = false,
    val dbPath: String = ""
)

data class TuiPendingApproval(
    val id: String,
    val agentId: String,
    val agentName: String,
    val action: String,
    val risk: String,
    val details: Map<String, String> = emptyMap()
)

data class TuiSessionEntry(
    val id: String,
    val name: String,
    val mode: String,
    val status: String,
    val tokensIn: Int,
    val tokensOut: Int,
    val costUsd: Double,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false
)
