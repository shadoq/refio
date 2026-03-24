package pl.jclab.refio.ui.components.rag

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import pl.jclab.refio.ui.theme.LCATheme
import pl.jclab.refio.core.db.RagContentType
import pl.jclab.refio.core.config.ConfigKeys
import pl.jclab.refio.services.core.CoreConnectionManager
import pl.jclab.refio.services.logging.dualLogger
import pl.jclab.refio.services.session.SessionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import java.awt.BorderLayout
import java.awt.Frame
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.*
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
    private val lastRefreshLabel = JBLabel("")
    private var isRefreshing = false

    // RAG Search UI
    private val searchQueryField = JBTextField()
    private val searchButton = JButton("🔍 Search")
    private lateinit var searchResultsArea: JTextArea

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
                add(embeddingStatsLabel)
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

        // Listen to session changes
        cs.launch {
            sessionManager.activeSession.collectLatest { session ->
                session?.let {
                    logger.debug { "Active session changed: ${it.id}" }
                    SwingUtilities.invokeLater {
                        refreshData()
                    }
                }
            }
        }

        // Initial load
        refreshData()
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
                val indexedFiles = router.getRagIndexedFiles()
                val statistics = router.getRagStatistics()

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
                percentage >= 100 -> "green"
                percentage >= 50 -> "orange"
                else -> "red"
            }

            "<html><font color='$color'>Embeddings: ${statistics.embeddingsCount}/${statistics.chunksCount} ($percentage%)</font>" +
                if (missing > 0) " - <font color='red'>$missing chunks missing embeddings</font>" else ""
        } else {
            ""
        }

        embeddingStatsLabel.text = embeddingStatsText
        logger.debug { "Stats updated: $statsText | Embeddings: $embeddingStatsText" }
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
                val chunks = router.getRagChunksForFile(filePath)

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

    private fun createSearchPanel(): JComponent {
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("RAG Search Test"),
                LCATheme.paddedBorder(8)
            )

            // Search input panel
            val inputPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
                border = LCATheme.paddedBorder(0, 0, 8, 0)

                searchQueryField.emptyText.text = "Enter search query to test RAG..."
                add(searchQueryField, BorderLayout.CENTER)

                searchButton.addActionListener { performSearch() }
                add(searchButton, BorderLayout.EAST)
            }
            add(inputPanel, BorderLayout.NORTH)

            // Results area
            searchResultsArea = JTextArea().apply {
                isEditable = false
                font = Font("Monospaced", Font.PLAIN, 11)
                lineWrap = true
                wrapStyleWord = true
                text = "Search results will appear here..."
            }

            val scrollPane = JBScrollPane(searchResultsArea).apply {
                preferredSize = java.awt.Dimension(800, 150)
            }
            add(scrollPane, BorderLayout.CENTER)
        }
    }

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
            searchResultsArea.text = "Searching..."
        }

        cs.launch {
            try {
                val router = coreManager.getOrCreateProjectRouter(java.nio.file.Paths.get(projectRoot))

                // Get configured embedding model (default to Ollama)
                val embeddingModel = router.getConfigService().get(
                    "models.embedding_model",
                    pl.jclab.refio.core.db.ConfigScope.APP,
                    null
                ) ?: "ollama/nomic-embed-text"

                val defaultTopK = router.getConfigService().getTyped(ConfigKeys.RAG_SEARCH_TOP_K)
                val results = router.searchRag(
                    query = query,
                    model = embeddingModel,
                    topK = defaultTopK
                )

                SwingUtilities.invokeLater {
                    if (results.isEmpty()) {
                        searchResultsArea.text = "No results found.\n\nPossible reasons:\n" +
                            "- No embeddings generated yet (click 'Generate Embeddings')\n" +
                            "- Query doesn't match indexed content\n" +
                            "- Similarity threshold too high"
                    } else {
                        val resultsText = buildString {
                            append("Found ${results.size} result(s):\n\n")
                            results.forEachIndexed { index, result ->
                                append("${index + 1}. ")
                                append("${result.filePath}")
                                if (result.startLine != null && result.endLine != null) {
                                    append(" (lines ${result.startLine}-${result.endLine})")
                                }
                                append(" - Similarity: ${String.format("%.2f", result.similarity)}\n")
                                append("   ${result.content.take(200).replace("\n", " ")}...\n\n")
                            }
                        }
                        searchResultsArea.text = resultsText
                        searchResultsArea.caretPosition = 0
                    }

                    searchButton.isEnabled = true
                    searchButton.text = "🔍 Search"
                }
            } catch (e: Exception) {
                logger.error(e) { "RAG search failed" }
                SwingUtilities.invokeLater {
                    searchResultsArea.text = "Search failed:\n${e.message}\n\n" +
                        "Possible reasons:\n" +
                        "- No embeddings generated yet\n" +
                        "- Embedding model not configured\n" +
                        "- API key missing (if using OpenAI)"
                    searchButton.isEnabled = true
                    searchButton.text = "🔍 Search"
                }
            }
        }
    }

    fun dispose() {
        cs.cancel()
    }
}

// DTOs moved to pl.jclab.refio.core.api.RagModels for platform independence
// Import aliases for backward compatibility
typealias RagIndexedFileDto = pl.jclab.refio.core.api.RagIndexedFileDto
typealias RagStatisticsDto = pl.jclab.refio.core.api.RagStatisticsDto
typealias RagChunkDto = pl.jclab.refio.core.api.RagChunkDto
