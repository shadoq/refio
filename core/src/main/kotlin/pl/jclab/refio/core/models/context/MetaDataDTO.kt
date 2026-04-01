package pl.jclab.refio.core.models.context

import java.time.Instant

/**
 * Project metadata DTO
 * Based on Python MetaDataDTO from context_dto.py
 */
data class MetaDataDTO(
    val projectId: String? = null,
    val projectName: String,
    val projectDescription: String? = null,
    val fileCount: Int = 0,
    val complexity: String = "Unknown",
    val mainLanguage: String = "Unknown",
    val lastAnalysis: Instant? = null
)
