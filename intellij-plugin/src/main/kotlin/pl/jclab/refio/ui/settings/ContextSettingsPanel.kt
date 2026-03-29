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
import pl.jclab.refio.services.logging.dualLogger
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
    private lateinit var ignorePathsTextArea: JBTextArea
    private lateinit var ignorePathsSourceLabel: JBLabel
    private lateinit var maxFileSizeField: JBTextField
    private lateinit var chunkSizeField: JBTextField
    private lateinit var indexProgressBar: JProgressBar
    private lateinit var indexStatusLabel: JLabel
    private lateinit var reindexButton: JButton
    private lateinit var stopIndexingButton: JButton
    private lateinit var generateEmbeddingsButton: JButton
    private lateinit var stopEmbeddingsButton: JButton
    private lateinit var clearIndexButton: JButton
    private lateinit var embeddingProgressBar: JProgressBar
    private lateinit var embeddingStatusLabel: JLabel
    private lateinit var ragSearchThresholdField: JBTextField
    private lateinit var ragSearchTopKField: JBTextField
    private lateinit var ragSearchSemanticWeightField: JBTextField
    private lateinit var ragSearchHybridEnabledCheckbox: JCheckBox
    private lateinit var ragSearchIncludeContextChunksCheckbox: JCheckBox
    private lateinit var chunkingStrategyCombo: JComboBox<String>
    private lateinit var bm25K1Field: JBTextField
    private lateinit var bm25BField: JBTextField
    private lateinit var embeddingModelField: JBTextField
    private lateinit var indexStatsLabel: JLabel
    private val defaultIgnorePathsText = ConfigService.DEFAULT_RAG_IGNORED_DIRECTORIES.joinToString("\n")
    private var indexJob: Job? = null
    private var embeddingJob: Job? = null
    private var searchSettingsSaveJob: Job? = null
    private var isLoadingSearchSettings = false
    private val searchSettingsSaveDebounceMs = 300L

    init {
        border = LCATheme.paddedBorder(LCATheme.margin)

        val contentPanel = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)

            // Section 1: Index Status
            add(createSectionPanel("Index Status", createIndexStatusPanel()))
            add(Box.createVerticalStrut(LCATheme.spacingLg))

            // Section 2: Embedding Management
            add(createSectionPanel("Embedding Management", createEmbeddingsSection()))
            add(Box.createVerticalStrut(LCATheme.spacingLg))

            // Section 3: Chunking & Embedding
            add(createSectionPanel("Chunking & Embedding", createChunkingEmbeddingSection()))
            add(Box.createVerticalStrut(LCATheme.spacingLg))

            // Section 4: Search Settings
            add(createSectionPanel("Search Settings", createSearchSettingsSection()))
            add(Box.createVerticalStrut(LCATheme.spacingLg))

            // Section 5: Index Statistics
            add(createSectionPanel("Index Statistics", createIndexStatisticsSection()))
            add(Box.createVerticalStrut(LCATheme.spacingLg))

            // Section 6: Providers
            add(createSectionPanel("Providers", createBuiltInProvidersPanel()))
        }

        val scrollPane = JBScrollPane(contentPanel).apply {
            border = LCATheme.emptyBorder()
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        }

        add(scrollPane, BorderLayout.CENTER)

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
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = LCATheme.paddedBorder(LCATheme.padding)

            // Description
            val description = JBLabel(
                "<html>These providers are always available for use with the @ syntax in prompts.<br>" +
                        "Example: @file, @open, @clipboard, @diff, etc.</html>"
            ).apply {
                foreground = LCATheme.descriptionForeground
                border = LCATheme.paddedBorder(0, 0, 8, 0)
                isEnabled = false
            }
            add(description, BorderLayout.NORTH)

            // Providers list
            val providersListPanel = JBPanel<JBPanel<*>>().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)

                val providers = ContextProviderRegistry.getAllProviders()
                    .sortedBy { it.description.title }

                providers.forEach { provider ->
                    val desc = provider.description
                    val typeIcon = when (desc.type) {
                        ProviderType.NORMAL -> AllIcons.Nodes.DataTables
                        ProviderType.QUERY -> AllIcons.Actions.Search
                        ProviderType.SUBMENU -> AllIcons.Nodes.Folder
                    }

                    add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
                        add(JLabel(typeIcon))
                        add(JLabel("@${desc.title}").apply {
                            font = font.deriveFont(Font.BOLD)
                            isEnabled = false
                        })
                        add(JLabel(desc.displayTitle))
                        add(JLabel("- ${desc.description}").apply {
                            foreground = LCATheme.descriptionForeground
                            isEnabled = false
                        })
                    })
                }
                isEnabled = false
            }
            add(providersListPanel, BorderLayout.CENTER)

            isEnabled = false
        }
    }

    // ==================== INDEX SETTINGS ====================

    private fun createIndexStatusPanel(): JPanel {
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            val contentPanel = JBPanel<JBPanel<*>>().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)

                // Indexing Status
                add(createIndexStatusSection())
                add(Box.createVerticalStrut(16))

                // Ignore Paths
                add(createIgnorePathsSection())
                add(Box.createVerticalStrut(16))

                // Index Settings
                add(createIndexConfigSection())
            }

            add(contentPanel, BorderLayout.CENTER)
        }
    }

    private fun createIndexStatusSection(): JPanel {
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

        return JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)

            add(JBLabel("Indexing Status:").apply {
                font = LCATheme.boldFont
            })
            add(Box.createVerticalStrut(8))

            add(JBPanel<JBPanel<*>>(BorderLayout()).apply {
                add(indexStatusLabel, BorderLayout.WEST)
                add(indexProgressBar, BorderLayout.CENTER)
            })
            add(Box.createVerticalStrut(8))
            add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT)).apply {
                add(reindexButton)
                add(stopIndexingButton)
                add(clearIndexButton)
            })
        }
    }

    private fun createIgnorePathsSection(): JPanel {
        ignorePathsTextArea = JBTextArea().apply {
            lineWrap = true
            wrapStyleWord = true
            text = defaultIgnorePathsText
        }
        ignorePathsSourceLabel = JBLabel("Source: Settings UI").apply {
            foreground = LCATheme.descriptionForeground
        }

        return JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)

            add(JBLabel("Ignore Paths:").apply {
                font = LCATheme.boldFont
            })
            add(JBLabel("Enter patterns for files and directories to exclude (one per line)").apply {
                foreground = LCATheme.descriptionForeground
            })
            add(Box.createVerticalStrut(4))
            add(ignorePathsSourceLabel)
            add(Box.createVerticalStrut(4))

            add(JBScrollPane(ignorePathsTextArea).apply {
                preferredSize = Dimension(500, 120)
                border = LCATheme.customLineBorder(LCATheme.borderColor, 1)
            })
        }
    }

    private fun createIndexConfigSection(): JPanel {
        maxFileSizeField = JBTextField("2", 5)
        chunkSizeField = JBTextField("1024", 8)

        return JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)

            add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
                add(JBLabel("Max File Size:"))
                add(maxFileSizeField)
                add(JBLabel("MB"))
                add(JBLabel("(files larger than this will be excluded)").apply {
                    foreground = LCATheme.descriptionForeground
                })
            })

            add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
                add(JBLabel("Chunk Size:"))
                add(chunkSizeField)
                add(JBLabel("tokens"))
                add(JBLabel("(size of text chunks for RAG)").apply {
                    foreground = LCATheme.descriptionForeground
                })
            })
        }
    }

    private fun createSearchSettingsSection(): JPanel {
        ragSearchThresholdField = JBTextField("0.5", 6)
        ragSearchTopKField = JBTextField("5", 4)
        ragSearchSemanticWeightField = JBTextField("0.7", 4)
        ragSearchHybridEnabledCheckbox = JCheckBox("Enable hybrid search", false)
        ragSearchIncludeContextChunksCheckbox = JCheckBox("Include context chunks", false)
        setupSearchSettingsAutoSave()

        return JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)

            add(JBLabel("Search Settings:").apply {
                font = LCATheme.boldFont
            })
            add(JBLabel("Configure default RAG similarity and ranking behavior").apply {
                foreground = LCATheme.descriptionForeground
            })
            add(Box.createVerticalStrut(8))

            add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
                add(JBLabel("Similarity Threshold:"))
                add(ragSearchThresholdField)
                add(JBLabel("(0.0 - 1.0)"))
            })

            add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
                add(JBLabel("Default TopK:"))
                add(ragSearchTopKField)
            })

            add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
                add(JBLabel("Semantic Weight:"))
                add(ragSearchSemanticWeightField)
                add(JBLabel("(0.0 - 1.0)"))
            })

            add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
                add(ragSearchHybridEnabledCheckbox)
                add(ragSearchIncludeContextChunksCheckbox)
            })

            add(Box.createVerticalStrut(8))
            add(JBLabel("BM25 Parameters (used in hybrid search):").apply {
                foreground = LCATheme.descriptionForeground
            })

            bm25K1Field = JBTextField("1.5", 4)
            bm25BField = JBTextField("0.75", 4)

            add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
                add(JBLabel("k1:"))
                add(bm25K1Field)
                add(JBLabel("(term saturation, default 1.5)").apply {
                    foreground = LCATheme.descriptionForeground
                })
            })

            add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
                add(JBLabel("b:"))
                add(bm25BField)
                add(JBLabel("(length normalization, default 0.75)").apply {
                    foreground = LCATheme.descriptionForeground
                })
            })
        }
    }

    private fun createEmbeddingsSection(): JPanel {
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

        return JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)

            add(JBLabel("Embeddings Management:").apply {
                font = LCATheme.boldFont
            })
            add(JBLabel("Generate embeddings for RAG search").apply {
                foreground = LCATheme.descriptionForeground
            })
            add(Box.createVerticalStrut(8))

            add(JBPanel<JBPanel<*>>(BorderLayout()).apply {
                add(embeddingStatusLabel, BorderLayout.WEST)
                add(embeddingProgressBar, BorderLayout.CENTER)
            })
            add(Box.createVerticalStrut(8))

            add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT)).apply {
                add(generateEmbeddingsButton)
                add(stopEmbeddingsButton)
            })
        }
    }

    private fun createChunkingEmbeddingSection(): JPanel {
        chunkingStrategyCombo = JComboBox(arrayOf("Semantic (recommended for code)", "Default (line-based)")).apply {
            selectedIndex = 0
        }
        embeddingModelField = JBTextField("ollama/nomic-embed-text", 25)

        return JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)

            add(JBLabel("Chunking Strategy:").apply {
                font = LCATheme.boldFont
            })
            add(JBLabel("Semantic chunking preserves code structure boundaries (classes, functions).").apply {
                foreground = LCATheme.descriptionForeground
            })
            add(Box.createVerticalStrut(4))
            add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
                add(JBLabel("Strategy:"))
                add(chunkingStrategyCombo)
            })
            add(Box.createVerticalStrut(8))

            add(JBLabel("Embedding Model:").apply {
                font = LCATheme.boldFont
            })
            add(JBLabel("Model used for generating vector embeddings.").apply {
                foreground = LCATheme.descriptionForeground
            })
            add(Box.createVerticalStrut(4))
            add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
                add(JBLabel("Model:"))
                add(embeddingModelField)
            })
        }
    }

    private fun createIndexStatisticsSection(): JPanel {
        indexStatsLabel = JLabel("Loading statistics...")

        return JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)

            add(JBLabel("Index Statistics:").apply {
                font = LCATheme.boldFont
            })
            add(Box.createVerticalStrut(4))
            add(indexStatsLabel)
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
                ConfigService.KEY_RAG_SEARCH_SIMILARITY_THRESHOLD
            )
            onSettingChanged(thresholdSection, thresholdKey, threshold)

            val (topKSection, topKKey) = ConfigKeyUtil.split(ConfigService.KEY_RAG_SEARCH_TOP_K)
            onSettingChanged(topKSection, topKKey, topK)

            val (hybridSection, hybridKey) = ConfigKeyUtil.split(ConfigService.KEY_RAG_SEARCH_HYBRID_ENABLED)
            onSettingChanged(hybridSection, hybridKey, hybridEnabled)

            val (weightSection, weightKey) = ConfigKeyUtil.split(ConfigService.KEY_RAG_SEARCH_SEMANTIC_WEIGHT)
            onSettingChanged(weightSection, weightKey, semanticWeight)

            val (contextSection, contextKey) = ConfigKeyUtil.split(
                ConfigService.KEY_RAG_SEARCH_INCLUDE_CONTEXT_CHUNKS
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
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(
                    LCATheme.customLineBorder(LCATheme.borderColor, 1),
                    title
                ),
                LCATheme.paddedBorder(LCATheme.spacingLg)
            )
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
