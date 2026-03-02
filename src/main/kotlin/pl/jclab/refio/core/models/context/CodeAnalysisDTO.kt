package pl.jclab.refio.core.models.context

/**
 * Code analysis DTO with per-language breakdown
 * Based on Python CodeAnalysisDTO from context_dto.py
 */
data class CodeAnalysisDTO(
    val javascript: Map<String, Any> = emptyMap(),
    val python: Map<String, Any> = emptyMap(),
    val html: Map<String, Any> = emptyMap(),
    val css: Map<String, Any> = emptyMap(),
    val typescript: Map<String, Any> = emptyMap(),
    val kotlin: Map<String, Any> = emptyMap(),
    val java: Map<String, Any> = emptyMap()
)
