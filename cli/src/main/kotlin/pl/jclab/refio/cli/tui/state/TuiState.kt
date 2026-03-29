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
    val subtasks: List<TuiSubtask> = emptyList(),
    val activePlan: TuiPlan? = null,
    val isPaused: Boolean = false,
    val pendingPlanApproval: TuiPlanApproval? = null,
    val selectedStepIndex: Int = 0,
    val contextSections: List<TuiContextSection> = emptyList(),
    val logs: List<TuiLogEntry> = emptyList(),
    val apiLogs: List<TuiApiLogEntry> = emptyList(),
    val debugInfo: TuiDebugInfo = TuiDebugInfo(),
    val pendingApprovals: List<TuiPendingApproval> = emptyList(),
    val sessions: List<TuiSessionEntry> = emptyList(),
    val activeSessionId: String? = null,
    val selectedHistoryIndex: Int = 0,
    val historyFilter: String = "*", // *, CHAT, PLAN, AGENT
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
    val cursorPosition: Int = 0,
    val executionStatus: String = "Idle",
    val coreConnected: Boolean = true,
    val contextUsedTokens: Int = 0,
    val contextMaxTokens: Int = 128000,
    val sessionTokensIn: Long = 0,
    val sessionTokensOut: Long = 0,
    val ragIndexingProgress: Double = -1.0, // -1 = not indexing, 0..1 = progress
    val ragIndexingStatus: String = "",
    val agentFilter: String? = null, // null = show all, "agent-name" = filter to specific agent
    val modelSelectorVisible: Boolean = false,
    val modelSelectorCandidates: List<String> = emptyList(),
    val modelSelectorIndex: Int = 0,
    val pendingQuestionId: String? = null,
    val pendingQuestionOptions: List<String> = emptyList(),
    val settingsSelectedField: Int = 0,
    val settingsEditingField: String? = null,
    val settingsEditBuffer: String = "",
    val ragIndexedFiles: List<TuiRagFile> = emptyList(),
    val apiLogsFilter: String? = null, // null = show all, or provider name
    val selectedApiLogIndex: Int = 0,
    val apiLogDetailVisible: Boolean = false,
    val ragSelectedFileIndex: Int = 0,
    val ragSearchQuery: String = "",
    val ragSearchResults: List<String> = emptyList(),
    val selectedMessageIndex: Int = -1, // -1 = none selected (copies last), >=0 = specific message
    val selectedContextIndex: Int = 0,
    val pastedContent: String? = null, // non-null when large paste detected, shows marker
    // File browser state
    val helpScrollOffset: Int = 0,
    val fileBrowserPath: String = "",
    val fileBrowserEntries: List<TuiFileEntry> = emptyList(),
    val fileBrowserSelectedIndex: Int = 0,
    val fileBrowserShowHidden: Boolean = false,
    // Logs view state
    val logsPaused: Boolean = false,
    val selectedLogIndex: Int = 0,
    val logDetailVisible: Boolean = false,
    val logsFilter: String? = null, // null = show all, or level name (DEBUG, INFO, WARN, ERROR)
    // Context detail view state
    val contextDetailVisible: Boolean = false,
    val contextDetailScrollOffset: Int = 0,
    // API log detail scroll
    val apiLogDetailScrollOffset: Int = 0,
    // Panel focus indicator
    val panelFocused: Boolean = false,
    // Content viewer overlay state (used for file viewer, log detail, API log detail, debug detail)
    val fileViewerVisible: Boolean = false,
    val fileViewerPath: String = "",     // title of the viewer
    val fileViewerContent: String = "",
    val fileViewerScrollOffset: Int = 0,
    val fileViewerShowLineNumbers: Boolean = true,  // false for log/debug/API detail views
    val fileViewerAllowAddContext: Boolean = true,   // false for non-file content
    // Debug panel scroll
    val debugScrollOffset: Int = 0
)

data class TuiRagFile(
    val filePath: String,
    val chunks: Int,
    val embeddings: Int,
    val sizeBytes: Long
)

enum class TuiScreen { MAIN, HISTORY, SETTINGS, HELP }

enum class TuiTab(val label: String, val fKey: Int? = null) {
    CHAT("Chat"),
    STEPS("Steps"),
    CONTEXT("Context"),
    RAG("RAG"),
    LOGS("Logs"),
    DEBUG("Debug"),
    API_LOGS("API"),
    FILES("Files", fKey = 8)
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
    val costUsd: Double = 0.0,
    val toolName: String? = null,
    val metadata: Map<String, Any?> = emptyMap()
)

enum class TuiMessageType {
    TEXT,
    AGENT_STARTED,
    AGENT_COMPLETED,
    AGENT_FAILED,
    DATA_EXCHANGE,
    APPROVAL_REQUEST,
    ARTIFACT,
    TOOL_CALL,
    PLAN,
    EXECUTION_SUMMARY,
    ORCHESTRATOR_QUESTION
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
    val id: String = "",
    val timestamp: String,
    val provider: String,
    val model: String,
    val tokensIn: Long,
    val tokensOut: Long,
    val costUsd: Double,
    val latencyMs: Int = 0,
    val httpStatus: Int? = null,
    val source: String? = null,
    val errorType: String? = null,
    val errorMessage: String? = null,
    val endpoint: String = "",
    val requestPayload: String = "",
    val responsePayload: String = "",
    val taskId: String? = null,
    val subtaskId: String? = null
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

data class TuiSubtask(
    val id: String,
    val name: String,
    val description: String = "",
    val status: String = "NEW", // NEW, PENDING, APPROVED, RUNNING, COMPLETED, FAILED, SKIPPED
    val toolName: String? = null,
    val toolArgs: String? = null,
    val result: String? = null,
    val error: String? = null,
    val tokensIn: Long = 0,
    val tokensOut: Long = 0,
    val costUsd: Double = 0.0,
    val order: Int = 0,
    val model: String? = null,
    val provider: String? = null,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val resultSummary: String? = null
)

data class TuiPlan(
    val taskId: String,
    val steps: List<TuiSubtask>,
    val totalReadSteps: Int = 0,
    val totalWriteSteps: Int = 0
)

data class TuiPlanApproval(
    val taskId: String,
    val plan: TuiPlan,
    val isVisible: Boolean = true
)

data class TuiFileEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val lastModified: Long = 0,
    val isSymlink: Boolean = false
)
