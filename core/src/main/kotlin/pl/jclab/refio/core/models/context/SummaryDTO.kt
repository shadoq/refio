package pl.jclab.refio.core.models.context

/**
 * Project summary DTO
 * Based on Python SummaryDTO from context_dto.py
 */
data class SummaryDTO(
    val projectType: String = "Unknown",
    val complexity: String = "Unknown",
    val mainLanguage: String = "Mixed",
    val architectureNotes: String? = null,
    val fileCount: Int = 0,
    val semanticDescription: String? = null,
    val keyCapabilities: List<String> = emptyList(),
    val entryPoints: List<String> = emptyList()
)
