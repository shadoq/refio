package pl.jclab.refio.ui.settings

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import pl.jclab.refio.ui.theme.LCATheme
import pl.jclab.refio.core.db.DocIndexingStatus
import pl.jclab.refio.core.db.DocumentationSource
import pl.jclab.refio.services.core.CoreConnectionManager
import pl.jclab.refio.core.logging.dualLogger
import kotlinx.coroutines.*
import java.awt.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.*
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

/**
 * Documentation Settings Panel
 * Manages external documentation URLs and their indexing
 */
class DocsSettingsPanel(
    private val project: Project,
    private val onSettingChanged: (section: String, key: String, value: Any) -> Unit
) : JBPanel<DocsSettingsPanel>(BorderLayout()) {

    private val logger = dualLogger("DocsSettingsPanel")
    private lateinit var docsTable: JBTable
    private lateinit var urlField: JBTextField
    private val addButton: JButton
    private val addFileButton: JButton
    private val reindexButton: JButton
    private val deleteButton: JButton

    private val cs = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val coreManager = CoreConnectionManager.getInstance()

    // Track indexing progress in memory (docId -> progressPercent)
    private val indexingProgress = mutableMapOf<Int, Int>()
    // Track embedding progress in memory (docId -> progressPercent)
    private val embeddingProgress = mutableMapOf<Int, Int>()

    init {
        border = LCATheme.createSettingsBorder("Documentation")

        // Initialize buttons
        addButton = JButton("Add Documentation")
        addFileButton = JButton("Add Local File")
        reindexButton = JButton("Reindex Selected")
        deleteButton = JButton("Delete")

        // Main content
        val contentPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = LCATheme.paddedBorder(8, 0, 0, 0)

            // Add URL panel at top
            val addUrlPanel = createAddUrlPanel()
            add(addUrlPanel, BorderLayout.NORTH)

            // Table with documentation list
            val tablePanel = createDocsTable()
            add(tablePanel, BorderLayout.CENTER)

            // Buttons panel at bottom
            val buttonsPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT)).apply {
                reindexButton.addActionListener { onReindexSelected() }
                deleteButton.addActionListener { onDeleteSelected() }
                add(reindexButton)
                add(deleteButton)
            }
            add(buttonsPanel, BorderLayout.SOUTH)
        }

        // Wrap contentPanel in scroll pane for small screens
        val scrollPane = JBScrollPane(contentPanel).apply {
            border = LCATheme.emptyBorder()
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        add(scrollPane, BorderLayout.CENTER)

        // Load documentation sources from backend
        loadDocumentation()
    }

    private fun createAddUrlPanel(): JPanel {
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = LCATheme.paddedBorder(0, 0, 16, 0)

            // Description
            val descLabel = JLabel("<html><font color='gray'>Add documentation URLs or local files to index and search</font></html>")
            add(descLabel, BorderLayout.NORTH)

            // URL input panel
            val inputPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
                border = LCATheme.paddedBorder(8, 0, 0, 0)

                urlField = JBTextField()
                urlField.emptyText.text = "Enter documentation URL (e.g., https://docs.example.com)"
                add(urlField, BorderLayout.CENTER)

                addButton.addActionListener { onAddUrl() }
                add(addButton, BorderLayout.EAST)
            }
            add(inputPanel, BorderLayout.CENTER)

            val localFilePanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT)).apply {
                border = LCATheme.paddedBorder(8, 0, 0, 0)
                addFileButton.addActionListener { onAddLocalFiles() }
                add(addFileButton)
            }
            add(localFilePanel, BorderLayout.SOUTH)
        }
    }

    private fun createDocsTable(): JComponent {
        val columnNames = arrayOf("Source", "Status", "Last Indexed", "Files", "ID")

        // Start with empty data, will be loaded from backend
        val data = arrayOf<Array<String>>()

        docsTable = JBTable(object : DefaultTableModel(data, columnNames) {
            override fun isCellEditable(row: Int, column: Int): Boolean {
                return false // All cells read-only
            }

            override fun getColumnClass(column: Int): Class<*> {
                return String::class.java
            }
        })

        // Set column widths
        docsTable.columnModel.getColumn(0).preferredWidth = 350 // Source
        docsTable.columnModel.getColumn(1).preferredWidth = 100 // Status
        docsTable.columnModel.getColumn(2).preferredWidth = 150 // Last Indexed
        docsTable.columnModel.getColumn(3).preferredWidth = 80  // Files
        docsTable.columnModel.getColumn(4).minWidth = 0
        docsTable.columnModel.getColumn(4).maxWidth = 0
        docsTable.columnModel.getColumn(4).preferredWidth = 0

        // Custom renderer for Status column to show colored badges
        val statusColumn = docsTable.columnModel.getColumn(1)
        statusColumn.cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable?,
                value: Any?,
                isSelected: Boolean,
                hasFocus: Boolean,
                row: Int,
                column: Int
            ): Component {
                val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)

                if (value is String) {
                    when (value) {
                        "Indexed" -> {
                            foreground = Color(0, 150, 0) // Green
                        }
                        "Indexing..." -> {
                            foreground = JBColor.BLUE
                        }
                        "Failed" -> {
                            foreground = JBColor.RED
                        }
                        else -> {
                            foreground = when {
                                value.startsWith("Indexing") || value.startsWith("Embedding") -> JBColor.BLUE
                                else -> JBColor.GRAY
                            }
                        }
                    }
                }

                return component
            }
        }

        docsTable.setShowGrid(true)
        docsTable.gridColor = JBColor.LIGHT_GRAY
        docsTable.rowHeight = 28

        return JScrollPane(docsTable).apply {
            preferredSize = Dimension(700, 300)
            border = LCATheme.customLineBorder(LCATheme.grayColor, 1)
        }
    }

    /**
     * Load documentation sources from backend
     */
    private fun loadDocumentation() {
        val projectRoot = project.basePath ?: return

        cs.launch {
            try {
                val router = coreManager.getOrCreateProjectRouter(java.nio.file.Paths.get(projectRoot))
                val docSources = router.ragRouter.getDocumentationSources()

                SwingUtilities.invokeLater {
                    populateDocsTable(docSources)
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to load documentation sources" }
            }
        }
    }

    /**
     * Populate table with documentation sources
     */
    private fun populateDocsTable(sources: List<DocumentationSource>) {
        val tableModel = docsTable.model as DefaultTableModel
        tableModel.rowCount = 0

        sources.forEach { source ->
            val displayName = displayNameFor(source)
            val status = when (source.status) {
                DocIndexingStatus.INDEXED -> "Indexed"
                DocIndexingStatus.INDEXING -> {
                    // Show progress if available
                    val percent = indexingProgress[source.id]
                    val embedPercent = embeddingProgress[source.id]
                    when {
                        percent != null -> "Indexing ($percent%)"
                        embedPercent != null -> "Embedding ($embedPercent%)"
                        else -> "Indexing..."
                    }
                }
                DocIndexingStatus.FAILED -> "Failed"
                DocIndexingStatus.PENDING -> "Pending"
                DocIndexingStatus.PAUSED -> "Paused"
            }

            // If embeddings are still running after indexing, reflect it even when status is Indexed
            val statusWithEmbedding = if (source.status == DocIndexingStatus.INDEXED) {
                val embedPercent = embeddingProgress[source.id]
                if (embedPercent != null && embedPercent < 100) {
                    "Embedding ($embedPercent%)"
                } else {
                    status
                }
            } else {
                status
            }

            val lastIndexed = source.lastIndexed?.let {
                formatTimestamp(it)
            } ?: "N/A"

            val files = when {
                source.pagesIndexed > 0 -> source.pagesIndexed.toString()
                else -> "N/A"
            }

            tableModel.addRow(arrayOf(
                displayName,
                statusWithEmbedding,
                lastIndexed,
                files,
                source.id.toString()
            ))
        }
    }

    /**
     * Handle add URL button click
     */
    private fun onAddUrl() {
        val url = urlField.text.trim()
        if (url.isEmpty()) {
            JOptionPane.showMessageDialog(
                this,
                "Please enter a URL",
                "Invalid URL",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }

        // Validate URL
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            JOptionPane.showMessageDialog(
                this,
                "URL must start with http:// or https://",
                "Invalid URL",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }

        logger.debug { "Add documentation URL: $url" }

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

                // Add documentation source
                val docSource = router.ragRouter.addDocumentationSource(url)

                // Clear input field and refresh table immediately
                SwingUtilities.invokeLater {
                    urlField.text = ""
                    loadDocumentation()  // Show document immediately with status
                }

                launch {
                    startIndexing(router, docSource)
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to add documentation" }
                SwingUtilities.invokeLater {
                    JOptionPane.showMessageDialog(
                        this@DocsSettingsPanel,
                        "Failed to add documentation: ${e.message}",
                        "Error",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }
        }
    }

    /**
     * Handle reindex selected button click
     */
    private fun onReindexSelected() {
        val docId = getSelectedDocId()
        if (docId == null) {
            JOptionPane.showMessageDialog(
                this,
                "Please select a documentation entry",
                "No Selection",
                JOptionPane.INFORMATION_MESSAGE
            )
            return
        }

        logger.debug { "Reindex documentation: $docId" }

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

                // Find doc source by URL
                val docSource = router.ragRouter.getDocumentationSources()
                    .find { it.id == docId }
                    ?: throw IllegalArgumentException("Documentation not found")

                // Delete old index
                router.ragRouter.deleteDocumentationIndex(docSource.id)

                // Refresh table immediately to show "Indexing..." status
                SwingUtilities.invokeLater {
                    loadDocumentation()
                }

                // Reindex in separate coroutine
                launch {
                    startIndexing(router, docSource)
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to reindex documentation" }
                SwingUtilities.invokeLater {
                    JOptionPane.showMessageDialog(
                        this@DocsSettingsPanel,
                        "Reindexing failed: ${e.message}",
                        "Error",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }
        }
    }

    /**
     * Handle delete selected button click
     */
    private fun onDeleteSelected() {
        val docId = getSelectedDocId()
        if (docId == null) {
            JOptionPane.showMessageDialog(
                this,
                "Please select a documentation entry",
                "No Selection",
                JOptionPane.INFORMATION_MESSAGE
            )
            return
        }

        val displayName = docsTable.getValueAt(docsTable.selectedRow, 0) as String
        val result = JOptionPane.showConfirmDialog(
            this,
            "Delete documentation:\n$displayName\n\nThis will remove all indexed pages.",
            "Confirm Deletion",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )

        if (result == JOptionPane.YES_OPTION) {
            logger.debug { "Delete documentation: $docId" }

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

                    router.ragRouter.deleteDocumentationSource(docId)

                    SwingUtilities.invokeLater {
                        loadDocumentation()
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to delete documentation" }
                    SwingUtilities.invokeLater {
                        JOptionPane.showMessageDialog(
                            this@DocsSettingsPanel,
                            "Deletion failed: ${e.message}",
                            "Error",
                            JOptionPane.ERROR_MESSAGE
                        )
                    }
                }
            }
        }
    }

    /**
     * Format timestamp for display
     */
    private fun formatTimestamp(ms: Long): String {
        val date = Instant.ofEpochMilli(ms)
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(date)
    }

    /**
     * Reload settings from backend
     */
    fun reload() {
        loadDocumentation()
    }

    private fun onAddLocalFiles() {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, true).apply {
            title = "Select Documentation Files"
            description = "Choose local documentation files (.txt, .md, .pdf)"
            withFileFilter { file ->
                val ext = file.extension?.lowercase()
                ext == "txt" || ext == "md" || ext == "pdf"
            }
        }

        val selectedFiles = FileChooser.chooseFiles(descriptor, project, null)
        if (selectedFiles.isEmpty()) {
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

        cs.launch {
            try {
                val router = coreManager.getOrCreateProjectRouter(java.nio.file.Paths.get(projectRoot))

                selectedFiles.forEach { file ->
                    val docSource = router.ragRouter.addDocumentationFile(file.path)
                    SwingUtilities.invokeLater { loadDocumentation() }
                    launch { startIndexing(router, docSource) }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to add local documentation files" }
                SwingUtilities.invokeLater {
                    JOptionPane.showMessageDialog(
                        this@DocsSettingsPanel,
                        "Failed to add local documentation files: ${e.message}",
                        "Error",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }
        }
    }

    private suspend fun startIndexing(
        router: pl.jclab.refio.core.api.CoreApiRouter,
        docSource: DocumentationSource
    ) {
        var lastRefreshPercent = 0
        router.ragRouter.indexDocumentation(docSource.id)
            .collect { progress ->
                logger.info { "Indexing progress: ${progress.progressPercent}% - ${progress.statusMessage}" }

                indexingProgress[docSource.id] = progress.progressPercent

                val currentPercent = (progress.progressPercent / 10) * 10
                if (currentPercent > lastRefreshPercent || progress.progressPercent >= 100) {
                    lastRefreshPercent = currentPercent
                    SwingUtilities.invokeLater { loadDocumentation() }
                }

                if (progress.progressPercent >= 100) {
                    indexingProgress.remove(docSource.id)
                }
            }

        logger.info { "Starting embedding generation for documentation: ${docSource.url}" }

        val embeddingModel = router.configService.getEmbeddingModel()

        router.ragRouter.generateEmbeddings(
            model = embeddingModel
        ) { embProgress ->
            logger.info { "Embedding progress: ${embProgress.progressPercent}% - ${embProgress.statusMessage}" }
            embeddingProgress[docSource.id] = embProgress.progressPercent
            if (embProgress.progressPercent >= 100) {
                embeddingProgress.remove(docSource.id)
            }
            SwingUtilities.invokeLater { loadDocumentation() }
        }
        logger.info { "Embedding generation completed for documentation: ${docSource.url}" }
    }

    private fun getSelectedDocId(): Int? {
        val selectedRow = docsTable.selectedRow
        if (selectedRow < 0) return null
        val raw = docsTable.getValueAt(selectedRow, 4) as? String ?: return null
        return raw.toIntOrNull()
    }

    private fun displayNameFor(source: DocumentationSource): String {
        return if (source.sourceType == pl.jclab.refio.core.db.DocSourceType.FILE) {
            source.filePath?.let { java.nio.file.Paths.get(it).fileName?.toString() } ?: source.url
        } else {
            source.title ?: source.url
        }
    }
}
