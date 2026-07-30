package pl.jclab.refio.ui.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import kotlinx.coroutines.*
import pl.jclab.refio.core.context.ContextProviderRegistry
import pl.jclab.refio.core.context.ProviderType
import pl.jclab.refio.core.config.ConfigKeys as TypedConfigKeys
import pl.jclab.refio.core.services.ConfigKeyUtil
import pl.jclab.refio.core.services.ConfigService
import pl.jclab.refio.services.core.CoreConnectionManager
import pl.jclab.refio.core.logging.dualLogger
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import pl.jclab.refio.ui.theme.LCATheme
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.FlowLayout
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Unified Context Settings Panel
 *
 * Combines:
 * - Built-in Context Providers (view only)
 * - Index Settings (RAG indexing configuration)
 *
 * This panel replaces:
 * - ContextProvidersSettingsPanel
 * - IndexSettingsPanel
 */
class ContextSettingsPanel(
    private val project: Project,
    private val onSettingChanged: (section: String, key: String, value: Any) -> Unit
) : JBPanel<ContextSettingsPanel>(BorderLayout()) {

    private val logger = dualLogger("ContextSettingsPanel")
    private val cs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val coreManager = CoreConnectionManager.getInstance()
    private val ragProgressService = pl.jclab.refio.services.rag.RagProgressService.getInstance(project)

    // Index components
    private val ignorePathsTextArea: JBTextArea
    private val ignorePathsSourceLabel: JBLabel
    private val maxFileSizeField: JBTextField
    private val chunkSizeField: JBTextField
    private val indexProgressBar: JProgressBar
    private val indexStatusLabel: JLabel
    private val reindexButton: JButton
    private val stopIndexingButton: JButton
    private val generateEmbeddingsButton: JButton
    private val stopEmbeddingsButton: JButton
    private val clearIndexButton: JButton
    private val embeddingProgressBar: JProgressBar
    private val embeddingStatusLabel: JLabel
    private val ragSearchThresholdField: JBTextField
    private val ragSearchTopKField: JBTextField
    private val ragSearchSemanticWeightField: JBTextField
    private val ragSearchHybridEnabledCheckbox: JCheckBox
    private val ragSearchIncludeContextChunksCheckbox: JCheckBox
    private val chunkingStrategyCombo: JComboBox<String>
    private val bm25K1Field: JBTextField
    private val bm25BField: JBTextField
    private val embeddingModelField: JBTextField
    private val indexStatsLabel: JLabel
    private val defaultIgnorePathsText = pl.jclab.refio.core.config.ConfigKeys.RAG_IGNORED_DIRECTORIES.default.joinToString("\n")
    private var indexJob: Job? = null
    private var embeddingJob: Job? = null
    private var searchSettingsSaveJob: Job? = null
    private var isLoadingSearchSettings = false
    private val searchSettingsSaveDebounceMs = 300L

    init {
        border = LCATheme.emptyBorder()

        indexProgressBar = JProgressBar(0, 100).apply {
            isStringPainted = true
            string = "Ready"
        }
        indexStatusLabel = JLabel("Idle")
        reindexButton = JButton("Reindex Project").apply {
            toolTipText = "Scan project files and build search index"
            addActionListener { onReindex() }
        }
        stopIndexingButton = JButton("Stop Indexing").apply {
            toolTipText = "Cancel ongoing indexing"
            isEnabled = false
            addActionListener { onStopIndexing() }
        }
        clearIndexButton = JButton("Clear Index").apply {
            icon = AllIcons.General.Remove
            toolTipText = "Delete all indexed data for this project (including embeddings)"
            addActionListener { onClearIndex() }
        }

        ignorePathsTextArea = JBTextArea().apply {
            lineWrap = true
            wrapStyleWord = true
            text = defaultIgnorePathsText
        }
        ignorePathsSourceLabel = JBLabel("Source: Settings UI").apply {
            foreground = LCATheme.descriptionForeground
        }

        maxFileSizeField = JBTextField("2", 5)
        chunkSizeField = JBTextField("1024", 8)

        embeddingProgressBar = JProgressBar(0, 100).apply {
            isStringPainted = true
            string = "Ready"
        }
        embeddingStatusLabel = JLabel("Ready")
        generateEmbeddingsButton = JButton("Generate Embeddings").apply {
            icon = AllIcons.Actions.Lightning
            toolTipText = "Generate vector embeddings for semantic search"
            addActionListener { onGenerateEmbeddings() }
        }
        stopEmbeddingsButton = JButton("Stop Embeddings").apply {
            toolTipText = "Cancel ongoing embedding generation"
            isEnabled = false
            addActionListener { onStopEmbeddings() }
        }

        chunkingStrategyCombo = JComboBox(arrayOf("Semantic (recommended for code)", "Default (line-based)")).apply {
            selectedIndex = 0
        }
        embeddingModelField = JBTextField("ollama/nomic-embed-text", 25)

        ragSearchThresholdField = JBTextField("0.5", 6)
        ragSearchTopKField = JBTextField("5", 4)
        ragSearchSemanticWeightField = JBTextField("0.7", 4)
        ragSearchHybridEnabledCheckbox = JCheckBox("Enable hybrid search", false)
        ragSearchIncludeContextChunksCheckbox = JCheckBox("Include context chunks", false)
        bm25K1Field = JBTextField("1.5", 4)
        bm25BField = JBTextField("0.75", 4)
        setupSearchSettingsAutoSave()

        indexStatsLabel = JLabel("Loading statistics...")

        val form = settingsForm {
            group("Index status") {
                row {
                    cell(indexStatusLabel)
                    cell(indexProgressBar).align(AlignX.FILL).resizableColumn()
                }
                // Three buttons side by side overflow a docked tool window, so the destructive one
                // gets its own row.
                row {
                    cell(reindexButton)
                    cell(stopIndexingButton)
                }
                row {
                    cell(clearIndexButton)
                }
            }

            group("Ignore paths") {
                row {
                    cell(ignorePathsSourceLabel)
                }
                row {
                    cell(
                        JBScrollPane(ignorePathsTextArea).apply {
                            preferredSize = Dimension(0, JBUI.scale(120))
                            border = LCATheme.customLineBorder(LCATheme.borderColor, 1)
                        }
                    ).align(AlignX.FILL).resizableColumn()
                }.rowComment("Patterns for files and directories to exclude from indexing, one per line")
            }

            group("Indexing limits") {
                row("Max file size:") {
                    cell(maxFileSizeField)
                    label("MB")
                }.rowComment("Files larger than this are never indexed")
                row("Chunk size:") {
                    cell(chunkSizeField)
                    label("tokens")
                }.rowComment("Size of the text chunks stored for RAG")
            }

            group("Embeddings") {
                row {
                    cell(embeddingStatusLabel)
                    cell(embeddingProgressBar).align(AlignX.FILL).resizableColumn()
                }
                row {
                    cell(generateEmbeddingsButton)
                    cell(stopEmbeddingsButton)
                }
                row("Embedding model:") {
                    cell(embeddingModelField)
                }.rowComment("Model used to generate the vectors for semantic search")
                row("Chunking strategy:") {
                    cell(chunkingStrategyCombo)
                }.rowComment("Semantic chunking keeps code structure boundaries (classes, functions) intact")
            }

            group("Search") {
                row("Similarity threshold:") {
                    cell(ragSearchThresholdField)
                    label("0.0 - 1.0")
                }
                row("Default TopK:") {
                    cell(ragSearchTopKField)
                }
                row("Semantic weight:") {
                    cell(ragSearchSemanticWeightField)
                    label("0.0 - 1.0")
                }
                row {
                    cell(ragSearchHybridEnabledCheckbox)
                }
                row {
                    cell(ragSearchIncludeContextChunksCheckbox)
                }
                row("BM25 k1:") {
                    cell(bm25K1Field)
                }.rowComment("Term saturation, used in hybrid search (default 1.5)")
                row("BM25 b:") {
                    cell(bm25BField)
                }.rowComment("Length normalization, used in hybrid search (default 0.75)")
            }

            group("Index statistics") {
                row {
                    cell(indexStatsLabel)
                }
            }

            group("Context providers") {
                row {
                    cell(createBuiltInProvidersPanel()).align(AlignX.FILL).resizableColumn()
                }.rowComment("Always available with the @ syntax in prompts, for example @file or @diff")
            }
        }

        add(settingsScrollPane(form), BorderLayout.CENTER)

        // Subscribe to RAG progress updates
        cs.launch {
            ragProgressService.indexingProgress.collect { progress ->
                SwingUtilities.invokeLater {
                    updateIndexProgress(progress.percent, progress.status)
                }
            }
        }

        cs.launch {
            ragProgressService.embeddingProgress.collect { progress ->
                SwingUtilities.invokeLater {
                    updateEmbeddingProgress(progress.percent, progress.status)
                }
            }
        }

        loadRagSearchSettings()
    }

    // ==================== BUILT-IN PROVIDERS ====================

    private fun createBuiltInProvidersPanel(): JPanel {
        val providers = ContextProviderRegistry.getAllProviders().sortedBy { it.description.title }

        return settingsForm {
            providers.forEach { provider ->
                val desc = provider.description
                val typeIcon = when (desc.type) {
                    ProviderType.NORMAL -> AllIcons.Nodes.DataTables
                    ProviderType.QUERY -> AllIcons.Actions.Search
                    ProviderType.SUBMENU -> AllIcons.Nodes.Folder
                }
                row {
                    icon(typeIcon)
                    label("@${desc.title}").applyToComponent { font = font.deriveFont(Font.BOLD) }
                    label(desc.displayTitle)
                    comment(desc.description)
                }
            }
        }
    }


    private fun loadIndexStatistics() {
        val projectPath = project.basePath ?: return

        cs.launch {
            try {
                val projectRoot = java.nio.file.Paths.get(projectPath)
                val router = coreManager.getOrCreateProjectRouter(projectRoot)
                val stats = router.ragRouter.getRagStatistics()

                SwingUtilities.invokeLater {
                    indexStatsLabel.text = buildString {
                        append("<html>")
                        append("Total files indexed: <b>${stats.filesCount}</b><br>")
                        append("Total chunks: <b>${stats.chunksCount}</b><br>")
                        append("Total embeddings: <b>${stats.embeddingsCount}</b>")
                        append("</html>")
                    }
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to load index statistics" }
                SwingUtilities.invokeLater {
                    indexStatsLabel.text = "Failed to load statistics"
                }
            }
        }
    }

    // ==================== INDEX ACTIONS ====================

    private fun onReindex() {
        val projectPath = project.basePath
        if (projectPath == null) {
            JOptionPane.showMessageDialog(this, "Project path not found", "Error", JOptionPane.ERROR_MESSAGE)
            return
        }

        reindexButton.isEnabled = false
        stopIndexingButton.isEnabled = true
        clearIndexButton.isEnabled = false
        updateIndexProgress(0, "Starting indexing...")

        indexJob = cs.launch {
            try {
                val projectRoot = java.nio.file.Paths.get(projectPath)
                val router = coreManager.getOrCreateProjectRouter(projectRoot)
                val ignorePatterns = getIgnorePaths().toSet()

                router.ragRouter.indexProjectForRag(ignorePatterns = ignorePatterns) { progress ->
                    // Publish to service for centralized progress tracking
                    ragProgressService.updateIndexingProgress(progress.progressPercent, progress.statusMessage)
                }

                SwingUtilities.invokeLater {
                    updateIndexProgress(100, "Completed")
                }
            } catch (e: CancellationException) {
                logger.info { "Indexing cancelled by user" }
                SwingUtilities.invokeLater {
                    updateIndexProgress(0, "Cancelled")
                }
            } catch (e: Exception) {
                logger.error(e) { "Indexing failed" }
                SwingUtilities.invokeLater {
                    updateIndexProgress(0, "Failed: ${e.message}")
                    JOptionPane.showMessageDialog(
                        this@ContextSettingsPanel,
                        "Indexing failed: ${e.message}",
                        "Error",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            } finally {
                SwingUtilities.invokeLater {
                    reindexButton.isEnabled = true
                    stopIndexingButton.isEnabled = false
                    clearIndexButton.isEnabled = true
                }
                indexJob = null
            }
        }
    }

    private fun onGenerateEmbeddings() {
        val projectPath = project.basePath ?: return

        generateEmbeddingsButton.isEnabled = false
        stopEmbeddingsButton.isEnabled = true
        updateEmbeddingProgress(0, "Generating...")

        embeddingJob = cs.launch {
            try {
                val projectRoot = java.nio.file.Paths.get(projectPath)
                val router = coreManager.getOrCreateProjectRouter(projectRoot)

                val embeddingModel = router.configService.get(
                    "models.embedding_model",
                    pl.jclab.refio.core.db.ConfigScope.APP,
                    null
                ) ?: "ollama/nomic-embed-text"

                router.ragRouter.generateEmbeddings(model = embeddingModel) { progress ->
                    // Publish to service for centralized progress tracking
                    ragProgressService.updateEmbeddingProgress(progress.progressPercent, progress.statusMessage)
                }

                SwingUtilities.invokeLater {
                    updateEmbeddingProgress(100, "Completed")
                }
            } catch (e: CancellationException) {
                logger.info { "Embedding generation cancelled by user" }
                SwingUtilities.invokeLater {
                    updateEmbeddingProgress(0, "Cancelled")
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to generate embeddings" }
                SwingUtilities.invokeLater {
                    updateEmbeddingProgress(0, "Failed")
                    JOptionPane.showMessageDialog(
                        this@ContextSettingsPanel,
                        "Failed: ${e.message}",
                        "Error",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            } finally {
                SwingUtilities.invokeLater {
                    generateEmbeddingsButton.isEnabled = true
                    stopEmbeddingsButton.isEnabled = false
                }
                embeddingJob = null
            }
        }
    }

    private fun onClearIndex() {
        val projectPath = project.basePath ?: return

        cs.launch {
            try {
                indexJob?.cancel()
                embeddingJob?.cancel()
                val projectRoot = java.nio.file.Paths.get(projectPath)
                val router = coreManager.getOrCreateProjectRouter(projectRoot)
                router.ragRouter.clearRagIndex()

                SwingUtilities.invokeLater {
                    ragProgressService.reset()
                    updateIndexProgress(0, "Index cleared")
                    updateEmbeddingProgress(0, "Embeddings cleared")
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to clear RAG index" }
                SwingUtilities.invokeLater {
                    JOptionPane.showMessageDialog(
                        this@ContextSettingsPanel,
                        "Failed: ${e.message}",
                        "Error",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }
        }
    }

    private fun onStopIndexing() {
        indexJob?.cancel()
    }

    private fun onStopEmbeddings() {
        embeddingJob?.cancel()
    }

    private fun updateIndexProgress(progress: Int, status: String) {
        indexProgressBar.value = progress
        indexProgressBar.string = "$progress%"
        indexStatusLabel.text = status
    }

    private fun updateEmbeddingProgress(progress: Int, status: String) {
        embeddingProgressBar.value = progress
        embeddingProgressBar.string = "$progress%"
        embeddingStatusLabel.text = status
    }

    private fun setupSearchSettingsAutoSave() {
        val changeListener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = onSearchSettingsChanged()
            override fun removeUpdate(e: DocumentEvent?) = onSearchSettingsChanged()
            override fun changedUpdate(e: DocumentEvent?) = onSearchSettingsChanged()
        }

        ragSearchThresholdField.document.addDocumentListener(changeListener)
        ragSearchTopKField.document.addDocumentListener(changeListener)
        ragSearchSemanticWeightField.document.addDocumentListener(changeListener)
        ragSearchHybridEnabledCheckbox.addActionListener { onSearchSettingsChanged() }
        ragSearchIncludeContextChunksCheckbox.addActionListener { onSearchSettingsChanged() }
    }

    private fun onSearchSettingsChanged() {
        if (isLoadingSearchSettings) return

        searchSettingsSaveJob?.cancel()
        searchSettingsSaveJob = cs.launch {
            delay(searchSettingsSaveDebounceMs)
            saveSearchSettings(showSuccessDialog = false)
        }
    }

    private fun saveSearchSettings(showSuccessDialog: Boolean) {
        val threshold = ragSearchThresholdField.text.trim().toFloatOrNull()
        if (threshold == null || threshold < 0.0f || threshold > 1.0f) {
            logger.warn { "Invalid similarity threshold value: ${ragSearchThresholdField.text}" }
            return
        }

        val topK = ragSearchTopKField.text.trim().toIntOrNull()
        if (topK == null || topK <= 0) {
            logger.warn { "Invalid topK value: ${ragSearchTopKField.text}" }
            return
        }

        val semanticWeight = ragSearchSemanticWeightField.text.trim().toFloatOrNull()
        if (semanticWeight == null || semanticWeight < 0.0f || semanticWeight > 1.0f) {
            logger.warn { "Invalid semantic weight value: ${ragSearchSemanticWeightField.text}" }
            return
        }

        val hybridEnabled = ragSearchHybridEnabledCheckbox.isSelected
        val includeContextChunks = ragSearchIncludeContextChunksCheckbox.isSelected

        SwingUtilities.invokeLater {
            val (thresholdSection, thresholdKey) = ConfigKeyUtil.split(
                pl.jclab.refio.core.config.ConfigKeys.RAG_SEARCH_SIMILARITY_THRESHOLD.key
            )
            onSettingChanged(thresholdSection, thresholdKey, threshold)

            val (topKSection, topKKey) = ConfigKeyUtil.split(pl.jclab.refio.core.config.ConfigKeys.RAG_SEARCH_TOP_K.key)
            onSettingChanged(topKSection, topKKey, topK)

            val (hybridSection, hybridKey) = ConfigKeyUtil.split(pl.jclab.refio.core.config.ConfigKeys.RAG_SEARCH_HYBRID_ENABLED.key)
            onSettingChanged(hybridSection, hybridKey, hybridEnabled)

            val (weightSection, weightKey) = ConfigKeyUtil.split(pl.jclab.refio.core.config.ConfigKeys.RAG_SEARCH_SEMANTIC_WEIGHT.key)
            onSettingChanged(weightSection, weightKey, semanticWeight)

            val (contextSection, contextKey) = ConfigKeyUtil.split(
                pl.jclab.refio.core.config.ConfigKeys.RAG_SEARCH_INCLUDE_CONTEXT_CHUNKS.key
            )
            onSettingChanged(contextSection, contextKey, includeContextChunks)
        }

        if (showSuccessDialog) {
            JOptionPane.showMessageDialog(
                this,
                "RAG search settings saved.",
                "Saved",
                JOptionPane.INFORMATION_MESSAGE
            )
        }
    }

    private fun getIgnorePaths(): List<String> {
        return ignorePathsTextArea.text
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    // ==================== RELOAD / CLEANUP ====================

    fun reload() {
        loadIgnorePaths()
        loadRagSearchSettings()
        loadIndexStatistics()
    }

    fun dispose() {
        cs.cancel()
    }

    // ==================== HELPERS ====================

    private fun createSectionPanel(title: String, content: JPanel): JPanel {
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = LCATheme.createSettingsBorder(title)
            add(content, BorderLayout.CENTER)
        }
    }

    private fun loadRagSearchSettings() {
        val projectPath = project.basePath ?: return

        cs.launch {
            try {
                val router = coreManager.getOrCreateProjectRouter(java.nio.file.Paths.get(projectPath))
                val configService = router.configService

                val threshold = configService.getTyped(TypedConfigKeys.RAG_SEARCH_SIMILARITY_THRESHOLD)
                val topK = configService.getTyped(TypedConfigKeys.RAG_SEARCH_TOP_K)
                val hybridEnabled = configService.getTyped(TypedConfigKeys.RAG_SEARCH_HYBRID_ENABLED)
                val semanticWeight = configService.getTyped(TypedConfigKeys.RAG_SEARCH_SEMANTIC_WEIGHT)
                val includeContextChunks = configService.getTyped(TypedConfigKeys.RAG_SEARCH_INCLUDE_CONTEXT_CHUNKS)

                SwingUtilities.invokeLater {
                    isLoadingSearchSettings = true
                    ragSearchThresholdField.text = threshold.toString()
                    ragSearchTopKField.text = topK.toString()
                    ragSearchSemanticWeightField.text = semanticWeight.toString()
                    ragSearchHybridEnabledCheckbox.isSelected = hybridEnabled
                    ragSearchIncludeContextChunksCheckbox.isSelected = includeContextChunks
                    isLoadingSearchSettings = false
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to load RAG search settings" }
            }
        }
    }

    private fun loadIgnorePaths() {
        val projectPath = project.basePath ?: return
        val ignoreFile = java.nio.file.Paths.get(projectPath).resolve(pl.jclab.refio.core.utils.AiIgnoreMatcher.FILE_NAME)

        if (java.nio.file.Files.exists(ignoreFile)) {
            try {
                val lines = java.nio.file.Files.readAllLines(ignoreFile)
                ignorePathsTextArea.text = lines.joinToString("\n")
                ignorePathsTextArea.isEditable = false
                ignorePathsSourceLabel.text = "Source: .aiignore (read-only)"
            } catch (e: Exception) {
                logger.warn(e) { "Failed to read ${ignoreFile.fileName}" }
                ignorePathsTextArea.text = defaultIgnorePathsText
                ignorePathsTextArea.isEditable = true
                ignorePathsSourceLabel.text = "Source: Settings UI (failed to read .aiignore)"
            }
        } else {
            ignorePathsTextArea.text = defaultIgnorePathsText
            ignorePathsTextArea.isEditable = true
            ignorePathsSourceLabel.text = "Source: Settings UI"
        }
    }
}
