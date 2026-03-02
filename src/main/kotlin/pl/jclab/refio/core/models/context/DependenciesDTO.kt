package pl.jclab.refio.core.models.context

/**
 * Project dependencies DTO
 * Based on Python DependenciesDTO from context_dto.py
 */
data class DependenciesDTO(
    val python: List<String> = emptyList(),
    val javascript: List<String> = emptyList(),
    val packageManagers: List<String> = emptyList(),
    val configFiles: List<String> = emptyList()
)
