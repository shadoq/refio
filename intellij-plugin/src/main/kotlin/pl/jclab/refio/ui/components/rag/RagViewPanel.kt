package pl.jclab.refio.ui.components.rag

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import pl.jclab.refio.ui.theme.LCATheme
import pl.jclab.refio.core.db.RagContentType
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.services.core.CoreConnectionManager
import pl.jclab.refio.core.logging.dualLogger
import pl.jclab.refio.services.session.SessionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Frame
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

private val logger = dualLogger("RagViewPanel")

/**
 * Panel do przeglądania zaindeksowanych plików i chunków RAG.
 *
 * Pokazuje:
 * - Listę zaindeksowanych plików dla aktywnego projektu (project root)
 * - Liczbę chunków per plik
 * - Status embeddingów
 * - Możliwość podglądu chunków
 * - Statystyki RAG
 *
 * Izolacja: Każdy projekt (project root) ma własne indeksy.
 */
class RagViewPanel(private val project: Project) : JBPanel<RagViewPanel>(BorderLayout()) {

    private val cs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessionManager = SessionManager.getInstance(project)
    private val coreManager = CoreConnectionManager.getInstance()

    private lateinit var filesTable: JBTable
    private val refreshButton = JButton("🔄 Refresh")
    private val viewChunksButton = JButton("📄 View Chunks")
    private val statsLabel = JBLabel("Loading...")
    private val embeddingStatsLabel = JBLabel("")
    private val generateEmbeddingsButton = JButton("Generate missing embeddings").apply {
        toolTipText = "Generate embeddings for chunks that are missing them"
        isVisible = false
    }
    private val lastRefreshLabel = JBLabel("")
    private var isRefreshing = false
    private var refreshDebounceJob: Job? = null
    private val refreshDebounceMs = 1500L

    // RAG Search UI
    private val fileNavigationService = pl.jclab.refio.ui.components.chat.FileNavigationService(project)
    private val searchQueryField = JBTextField()
    private val searchButton = JButton("🔍 Search")
    private val searchResultsModel = DefaultListModel<RagSearchResultDto>()
    private lateinit var searchResultsList: JBList<RagSearchResultDto>
    private lateinit var searchStatusLabel: JBLabel
    private lateinit var searchContentPanel: JPanel
    private lateinit var searchToggleLabel: JBLabel
    private var isSearchExpanded = false

    init {
        border = LCATheme.paddedBorder(16)

        // Header
        val headerPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            val titleLabel = JBLabel("RAG Index - Indexed Files").apply {
                font = font.deriveFont(Font.BOLD, 14f)
            }
            add(titleLabel, BorderLayout.WEST)

            val buttonsPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                refreshButton.addActionListener { refreshData(updateButton = true) }
                viewChunksButton.addActionListener { viewSelectedChunks() }

                add(refreshButton)
                add(viewChunksButton)
            }
            add(buttonsPanel, BorderLayout.EAST)
        }
        add(headerPanel, BorderLayout.NORTH)

        // Stats panel with embeddings status
        val statsPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = LCATheme.paddedBorder(8, 0)

            val leftPanel = JBPanel<JBPanel<*>>().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(statsLabel)
                embeddingStatsLabel.font = embeddingStatsLabel.font.deriveFont(Font.PLAIN, 11f)
                val embeddingRow = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                    add(embeddingStatsLabel)
                    generateEmbeddingsButton.addActionListener { generateMissingEmbeddings() }
                    add(generateEmbeddingsButton)
                }
                add(embeddingRow)
            }

            val rightPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                lastRefreshLabel.font = lastRefreshLabel.font.deriveFont(Font.PLAIN, 11f)
                lastRefreshLabel.foreground = JBColor.GRAY
                add(lastRefreshLabel)
            }

            add(leftPanel, BorderLayout.WEST)
            add(rightPanel, BorderLayout.EAST)
        }

        // Files table
        val tablePanel = createFilesTable()

        // RAG Search panel
        val searchPanel = createSearchPanel()

        val contentPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(statsPanel, BorderLayout.NORTH)
            add(tablePanel, BorderLayout.CENTER)
            add(searchPanel, BorderLayout.SOUTH)
        }

        add(contentPanel, BorderLayout.CENTER)

        // Listen to session changes — debounce because session updates fire ~6×/min
        // (mode toggles, model selects, message sends). Burst refreshes were observed
        // 6× in 10 minutes for the same project; coalesce into one refresh after
        // refreshDebounceMs of quiet.
        cs.launch {
            sessionManager.activeSession.collectLatest { session ->
                session?.let {
                    logger.debug { "Active session changed: ${it.id}" }
                    scheduleDebouncedRefresh()
                }
            }
        }

        // Initial load — direct, no debounce.
        refreshData()
    }

    private fun scheduleDebouncedRefresh() {
        refreshDebounceJob?.cancel()
        refreshDebounceJob = cs.launch {
            delay(refreshDebounceMs)
            SwingUtilities.invokeLater { refreshData() }
        }
    }

    private fun createFilesTable(): JComponent {
        val columnNames = arrayOf("File Path", "Chunks", "Embeddings", "Size", "Content Type", "Last Indexed")

        filesTable = JBTable(object : DefaultTableModel(columnNames, 0) {
            override fun isCellEditable(row: Int, column: Int) = false
            override fun getColumnClass(column: Int) = String::class.java
        })

        filesTable.setShowGrid(true)
        filesTable.gridColor = JBColor.LIGHT_GRAY
        filesTable.rowHeight = 28

        filesTable.columnModel.getColumn(0).preferredWidth = 400
        filesTable.columnModel.getColumn(0).cellRenderer = MiddleEllipsisCellRenderer()
        filesTable.columnModel.getColumn(1).preferredWidth = 80
        filesTable.columnModel.getColumn(2).preferredWidth = 100
        filesTable.columnModel.getColumn(3).preferredWidth = 80
        filesTable.columnModel.getColumn(4).preferredWidth = 120
        filesTable.columnModel.getColumn(5).preferredWidth = 150

        return JBScrollPane(filesTable).apply {
            border = LCATheme.customLineBorder(JBColor.GRAY, 1)
        }
    }

    private fun refreshData(updateButton: Boolean = false) {
        if (isRefreshing) {
            logger.debug { "Refresh already in progress, skipping" }
            return
        }

        val projectRoot = project.basePath
        if (projectRoot == null) {
            SwingUtilities.invokeLater {
                statsLabel.text = "No project root"
                (filesTable.model as DefaultTableModel).rowCount = 0
                lastRefreshLabel.text = ""
            }
            return
        }

        logger.info { "Starting RAG index refresh for projectRoot=$projectRoot" }

        // Visual feedback: disable button and change text only if manually triggered
        SwingUtilities.invokeLater {
            isRefreshing = true
            if (updateButton) {
                refreshButton.isEnabled = false
                refreshButton.text = "⏳ Refreshing..."
            }
            statsLabel.text = "Loading..."
        }

        cs.launch {
            try {
                val router = coreManager.getOrCreateProjectRouter(java.nio.file.Paths.get(projectRoot))
                val indexedFiles = router.ragRouter.getRagIndexedFiles()
                val statistics = router.ragRouter.getRagStatistics()

                logger.debug { "RAG data loaded: ${indexedFiles.size} files, ${statistics.filesCount} in stats" }

                SwingUtilities.invokeLater {
                    updateTable(indexedFiles)
                    updateStats(statistics)

                    // Update last refresh timestamp
                    val now = java.time.LocalDateTime.now()
                    val formatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
                    lastRefreshLabel.text = "Last refresh: ${now.format(formatter)}"

                    logger.info { "RAG index refresh completed successfully" }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to load RAG index" }
                SwingUtilities.invokeLater {
                    statsLabel.text = "Error loading index: ${e.message}"
                    lastRefreshLabel.text = "Failed to refresh"
                }
            } finally {
                SwingUtilities.invokeLater {
                    isRefreshing = false
                    if (updateButton) {
                        refreshButton.isEnabled = true
                        refreshButton.text = "🔄 Refresh"
                    }
                }
            }
        }
    }

    private fun updateTable(files: List<RagIndexedFileDto>) {
        logger.debug { "Updating table with ${files.size} files" }

        val tableModel = filesTable.model as DefaultTableModel
        tableModel.rowCount = 0

        files.forEach { file ->
            tableModel.addRow(arrayOf(
                file.filePath,
                file.chunksCount.toString(),
                "${file.embeddingsCount} / ${file.chunksCount}",
                formatFileSize(file.fileSize),
                file.contentType.name,
                formatTimestamp(file.indexedAt)
            ))
        }

        logger.debug { "Table updated successfully, rows: ${tableModel.rowCount}" }
    }

    private fun updateStats(statistics: RagStatisticsDto) {
        logger.debug { "Updating stats: files=${statistics.filesCount}, chunks=${statistics.chunksCount}, embeddings=${statistics.embeddingsCount}" }

        val statsText = if (statistics.filesCount == 0) {
            "No indexed files (use RAG Indexer to add documents)"
        } else {
            "Files: ${statistics.filesCount} | Chunks: ${statistics.chunksCount}"
        }

        statsLabel.text = statsText

        // Update embedding stats separately
        val embeddingStatsText = if (statistics.chunksCount > 0) {
            val percentage = (statistics.embeddingsCount * 100) / statistics.chunksCount
            val missing = statistics.chunksCount - statistics.embeddingsCount

            val color = when {
                percentage >= 100 -> "#" + ColorUtil.toHex(LCATheme.successColor)
                percentage >= 50 -> "#" + ColorUtil.toHex(LCATheme.warningColor)
                else -> "#" + ColorUtil.toHex(LCATheme.errorColor)
            }
            val errorHex = "#" + ColorUtil.toHex(LCATheme.errorColor)

            "<html><font color='$color'>Embeddings: ${statistics.embeddingsCount}/${statistics.chunksCount} ($percentage%)</font>" +
                if (missing > 0) " - <font color='$errorHex'>$missing chunks missing embeddings</font>" else ""
        } else {
            ""
        }

        // Append circuit-breaker warnings (e.g. Ollama unreachable → embeddings disabled).
        // Previously these were silent in the UI; users only saw "RAG disabled" with no clue why.
        val openCircuits = pl.jclab.refio.core.services.EmbeddingCircuitBreaker.getNonClosedCircuits()
        val breakerText = if (openCircuits.isNotEmpty()) {
            val parts = openCircuits.joinToString(", ") { snap ->
                val cooldownSec = (snap.cooldownRemainingMs / 1000).coerceAtLeast(0)
                "${snap.providerKey} ${snap.state}" +
                    if (snap.state == "OPEN" && cooldownSec > 0) " (retry in ${cooldownSec}s)" else ""
            }
            "<br><font color='#${ColorUtil.toHex(LCATheme.errorColor)}'>⚠ Embedding provider: $parts</font>"
        } else ""

        val combined = if (embeddingStatsText.startsWith("<html>")) {
            embeddingStatsText.removeSuffix("</html>") + breakerText + (if (breakerText.isNotBlank()) "</html>" else "")
        } else if (breakerText.isNotBlank()) {
            "<html>$breakerText</html>"
        } else {
            embeddingStatsText
        }

        embeddingStatsLabel.text = combined
        generateEmbeddingsButton.isVisible =
            statistics.chunksCount > 0 && statistics.embeddingsCount < statistics.chunksCount
        logger.debug { "Stats updated: $statsText | Embeddings: $combined" }
    }

    /**
     * Generate embeddings for chunks that are missing them.
     * Uses the same service path as the Generate Embeddings button in Context settings.
     */
    private fun generateMissingEmbeddings() {
        val projectRoot = project.basePath ?: return

        generateEmbeddingsButton.isEnabled = false
        generateEmbeddingsButton.text = "Generating..."

        cs.launch {
            try {
                val router = coreManager.getOrCreateProjectRouter(java.nio.file.Paths.get(projectRoot))

                val embeddingModel = router.configService.get(
                    "models.embedding_model",
                    pl.jclab.refio.core.db.ConfigScope.APP,
                    null
                ) ?: "ollama/nomic-embed-text"

                router.ragRouter.generateEmbeddings(model = embeddingModel)

                SwingUtilities.invokeLater { refreshData() }
            } catch (e: Exception) {
                logger.error(e) { "Failed to generate missing embeddings" }
                SwingUtilities.invokeLater {
                    JOptionPane.showMessageDialog(
                        this@RagViewPanel,
                        "Failed to generate embeddings: ${e.message}",
                        "Error",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            } finally {
                SwingUtilities.invokeLater {
                    generateEmbeddingsButton.isEnabled = true
                    generateEmbeddingsButton.text = "Generate missing embeddings"
                }
            }
        }
    }

    private fun viewSelectedChunks() {
        val selectedRow = filesTable.selectedRow
        if (selectedRow < 0) {
            JOptionPane.showMessageDialog(
                this,
                "Please select a file to view its chunks",
                "No Selection",
                JOptionPane.INFORMATION_MESSAGE
            )
            return
        }

        val filePath = filesTable.getValueAt(selectedRow, 0) as String

        logger.info { "View chunks for file: $filePath" }

        val projectRoot = project.basePath
        if (projectRoot == null) {
            JOptionPane.showMessageDialog(
                this,
                "Project path not found",
                "Error",
                JOptionPane.ERROR_MESSAGE
            )
            return
        }

        cs.launch {
            try {
                val router = coreManager.getOrCreateProjectRouter(java.nio.file.Paths.get(projectRoot))
                val chunks = router.ragRouter.getRagChunksForFile(filePath)

                SwingUtilities.invokeLater {
                    showChunksDialog(filePath, chunks)
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to load chunks for file: $filePath" }
                SwingUtilities.invokeLater {
                    JOptionPane.showMessageDialog(
                        this@RagViewPanel,
                        "Failed to load chunks: ${e.message}",
                        "Error",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }
        }
    }

    private fun showChunksDialog(filePath: String, chunks: List<RagChunkDto>) {
        val dialog = JDialog(SwingUtilities.getWindowAncestor(this) as? Frame, "Chunks: $filePath", true)
        dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE

        val textArea = JTextArea().apply {
            isEditable = false
            font = Font("Monospaced", Font.PLAIN, 12)
            lineWrap = true
            wrapStyleWord = true

            val content = buildString {
                chunks.forEachIndexed { index, chunk ->
                    append("=".repeat(80))
                    append("\nChunk ${index + 1}/${chunks.size}")
                    if (chunk.startLine != null && chunk.endLine != null) {
                        append(" (lines ${chunk.startLine}-${chunk.endLine})")
                    }
                    append("\n")
                    append("=".repeat(80))
                    append("\n\n")
                    append(chunk.content)
                    append("\n\n")
                }
            }

            text = content
            caretPosition = 0
        }

        val scrollPane = JBScrollPane(textArea)
        dialog.contentPane = scrollPane
        dialog.setSize(800, 600)
        dialog.setLocationRelativeTo(this)
        dialog.isVisible = true
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            else -> "${bytes / (1024 * 1024)}MB"
        }
    }

    private fun formatTimestamp(ms: Long): String {
        val date = java.time.Instant.ofEpochMilli(ms)
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(java.time.ZoneId.systemDefault())
            .format(date)
    }

    /**
     * Collapsible "RAG Search" section: a lightweight tool to test what the retriever returns
     * for a query. Collapsed by default so it does not read as a debug console; results are shown
     * as a list of clickable file paths (click opens the file) instead of monospaced text.
     */
    private fun createSearchPanel(): JComponent {
        searchToggleLabel = JBLabel(AllIcons.General.ArrowRight)
        val titleLabel = JBLabel("RAG Search").apply {
            font = font.deriveFont(Font.BOLD, 12f)
        }
        val headerRow = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            add(searchToggleLabel)
            add(titleLabel)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = toggleSearchSection()
            })
        }

        // Search input panel
        val inputPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = LCATheme.paddedBorder(0, 0, 8, 0)

            searchQueryField.emptyText.text = "Enter search query to test RAG..."
            searchQueryField.addActionListener { performSearch() }
            add(searchQueryField, BorderLayout.CENTER)

            searchButton.addActionListener { performSearch() }
            add(searchButton, BorderLayout.EAST)
        }

        searchStatusLabel = JBLabel("").apply {
            font = font.deriveFont(Font.PLAIN, 11f)
            foreground = JBColor.GRAY
            isVisible = false
        }

        // Results as a list of clickable file paths - click opens the file in the editor.
        searchResultsList = JBList(searchResultsModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = SearchResultCellRenderer()
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val index = locationToIndex(e.point)
                    if (index < 0 || index >= searchResultsModel.size()) return
                    val result = searchResultsModel.getElementAt(index)
                    fileNavigationService.openFileReference(result.filePath)
                }
            })
        }
        val resultsScroll = JBScrollPane(searchResultsList).apply {
            preferredSize = Dimension(800, 150)
        }

        val centerPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(searchStatusLabel, BorderLayout.NORTH)
            add(resultsScroll, BorderLayout.CENTER)
        }

        searchContentPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = LCATheme.paddedBorder(4, 0, 0, 0)
            add(inputPanel, BorderLayout.NORTH)
            add(centerPanel, BorderLayout.CENTER)
            isVisible = false
        }

        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, LCATheme.borderColor),
                LCATheme.paddedBorder(8, 0, 0, 0)
            )
            add(headerRow, BorderLayout.NORTH)
            add(searchContentPanel, BorderLayout.CENTER)
        }
    }

    private fun toggleSearchSection() {
        isSearchExpanded = !isSearchExpanded
        searchToggleLabel.icon = if (isSearchExpanded) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
        searchContentPanel.isVisible = isSearchExpanded
        searchContentPanel.revalidate()
        searchContentPanel.repaint()
    }

    private fun setSearchStatus(text: String) {
        searchStatusLabel.text = text
        searchStatusLabel.isVisible = text.isNotBlank()
    }

    /**
     * Renders a single search hit: file path (bold), optional line range, similarity, and a
     * short single-line snippet. Full path is shown in the tooltip.
     */
    private inner class SearchResultCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            val result = value as? RagSearchResultDto ?: return component
            val label = component as JLabel

            val lineRange = if (result.startLine != null && result.endLine != null) {
                " (lines ${result.startLine}-${result.endLine})"
            } else ""
            val snippet = result.content.take(160).replace("\n", " ").trim()
            val snippetHex = "#" + ColorUtil.toHex(JBColor.GRAY)
            val similarity = String.format("%.2f", result.similarity)

            label.text = "<html><b>${escapeHtml(result.filePath)}</b>$lineRange" +
                " - $similarity<br><font color='$snippetHex'>${escapeHtml(snippet)}</font></html>"
            label.toolTipText = result.filePath
            label.border = BorderFactory.createEmptyBorder(4, 6, 4, 6)
            return label
        }
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun performSearch() {
        val query = searchQueryField.text.trim()
        if (query.isEmpty()) {
            JOptionPane.showMessageDialog(
                this,
                "Please enter a search query",
                "Empty Query",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }

        val projectRoot = project.basePath
        if (projectRoot == null) {
            JOptionPane.showMessageDialog(
                this,
                "Project path not found",
                "Error",
                JOptionPane.ERROR_MESSAGE
            )
            return
        }

        logger.info { "Performing RAG search: $query" }

        SwingUtilities.invokeLater {
            searchButton.isEnabled = false
            searchButton.text = "⏳ Searching..."
            searchResultsModel.clear()
            setSearchStatus("Searching...")
        }

        cs.launch {
            try {
                val router = coreManager.getOrCreateProjectRouter(java.nio.file.Paths.get(projectRoot))

                // Get configured embedding model (default to Ollama)
                val embeddingModel = router.configService.get(
                    "models.embedding_model",
                    pl.jclab.refio.core.db.ConfigScope.APP,
                    null
                ) ?: "ollama/nomic-embed-text"

                val defaultTopK = router.configService.getTyped(ConfigKeys.RAG_SEARCH_TOP_K)
                val results = router.ragRouter.searchRag(
                    query = query,
                    model = embeddingModel,
                    topK = defaultTopK
                )

                SwingUtilities.invokeLater {
                    searchResultsModel.clear()
                    if (results.isEmpty()) {
                        setSearchStatus(
                            "No results found. No embeddings generated yet, query does not match " +
                                "indexed content, or the similarity threshold is too high."
                        )
                    } else {
                        setSearchStatus("Found ${results.size} result(s) - click a path to open the file.")
                        results.forEach { searchResultsModel.addElement(it) }
                    }

                    searchButton.isEnabled = true
                    searchButton.text = "🔍 Search"
                }
            } catch (e: Exception) {
                logger.error(e) { "RAG search failed" }
                SwingUtilities.invokeLater {
                    searchResultsModel.clear()
                    setSearchStatus(
                        "Search failed: ${e.message}. Check that embeddings are generated, the " +
                            "embedding model is configured, and any required API key is set."
                    )
                    searchButton.isEnabled = true
                    searchButton.text = "🔍 Search"
                }
            }
        }
    }

    fun dispose() {
        cs.cancel()
    }

    /**
     * Renders long paths with a middle ellipsis (keeps head and tail) and shows
     * the full path in the tooltip.
     */
    private class MiddleEllipsisCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
        ): java.awt.Component {
            val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val fullText = value?.toString() ?: ""
            toolTipText = fullText.ifEmpty { null }

            val availableWidth = table.columnModel.getColumn(column).width - insets.left - insets.right
            text = middleEllipsis(fullText, getFontMetrics(font), availableWidth)
            return component
        }

        private fun middleEllipsis(text: String, fm: java.awt.FontMetrics, availableWidth: Int): String {
            if (text.isEmpty() || availableWidth <= 0 || fm.stringWidth(text) <= availableWidth) return text

            val ellipsis = "..."
            var head = text.length / 2
            var tail = text.length - head
            while (head + tail > 0) {
                val candidate = text.take(head) + ellipsis + text.takeLast(tail)
                if (fm.stringWidth(candidate) <= availableWidth) return candidate
                if (head >= tail) head-- else tail--
            }
            return ellipsis
        }
    }
}

// DTOs moved to pl.jclab.refio.core.api.RagModels for platform independence
// Import aliases for backward compatibility
typealias RagIndexedFileDto = pl.jclab.refio.core.api.RagIndexedFileDto
typealias RagStatisticsDto = pl.jclab.refio.core.api.RagStatisticsDto
typealias RagChunkDto = pl.jclab.refio.core.api.RagChunkDto
typealias RagSearchResultDto = pl.jclab.refio.core.api.RagSearchResultDto
