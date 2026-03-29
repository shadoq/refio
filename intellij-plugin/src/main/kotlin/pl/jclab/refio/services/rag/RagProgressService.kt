package pl.jclab.refio.services.rag

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Service for tracking RAG indexing and embedding progress across the plugin.
 *
 * Provides StateFlow for reactive UI updates in ContextSettingsPanel and other components.
 * Used by BackgroundIndexingTask and manual reindex operations.
 */
@Service(Service.Level.PROJECT)
class RagProgressService {

    private val _indexingProgress = MutableStateFlow(RagProgress.idle())
    val indexingProgress: StateFlow<RagProgress> = _indexingProgress.asStateFlow()

    private val _embeddingProgress = MutableStateFlow(RagProgress.idle())
    val embeddingProgress: StateFlow<RagProgress> = _embeddingProgress.asStateFlow()

    /**
     * Update indexing progress (0-100%)
     */
    fun updateIndexingProgress(percent: Int, status: String) {
        _indexingProgress.value = RagProgress(
            percent = percent.coerceIn(0, 100),
            status = status,
            isRunning = percent in 1..99
        )
    }

    /**
     * Update embedding progress (0-100%)
     */
    fun updateEmbeddingProgress(percent: Int, status: String) {
        _embeddingProgress.value = RagProgress(
            percent = percent.coerceIn(0, 100),
            status = status,
            isRunning = percent in 1..99
        )
    }

    /**
     * Reset both progress states to idle
     */
    fun reset() {
        _indexingProgress.value = RagProgress.idle()
        _embeddingProgress.value = RagProgress.idle()
    }

    companion object {
        fun getInstance(project: Project): RagProgressService {
            return project.getService(RagProgressService::class.java)
        }
    }
}

/**
 * RAG progress state
 */
data class RagProgress(
    val percent: Int,
    val status: String,
    val isRunning: Boolean
) {
    companion object {
        fun idle() = RagProgress(0, "Idle", false)
    }
}
