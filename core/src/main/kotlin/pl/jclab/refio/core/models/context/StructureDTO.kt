package pl.jclab.refio.core.models.context

/**
 * Project structure DTO
 * Based on Python StructureDTO from context_dto.py
 */
data class StructureDTO(
    val totalFiles: Int = 0,
    val fileTypes: Map<String, Int> = emptyMap(),
    val topLevelItems: List<String> = emptyList(),
    val directoryCount: Int = 0,
    val maxDepth: Int = 0
)
