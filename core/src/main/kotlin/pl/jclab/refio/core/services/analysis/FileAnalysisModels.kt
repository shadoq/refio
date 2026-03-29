package pl.jclab.refio.core.services.analysis

/**
 * Structured representation of a single source file analysis.
 * Keeps metadata lightweight so SessionManager can attach it directly to context.
 */
data class FileAnalysis(
    val projectRoot: String,
    val filePath: String,
    val language: String?,
    val fileSize: Long,
    val lastModified: Long,
    val codeElements: CodeElements = CodeElements(),
    val contentHash: String? = null,
    val fileId: Int? = null,
    val lineCount: Int? = null
)

/**
 * Aggregated code elements extracted from a file.
 */
data class CodeElements(
    val classes: List<ClassElement> = emptyList(),
    val functions: List<FunctionElement> = emptyList(),
    val imports: List<ImportElement> = emptyList(),
    val exports: List<ExportElement> = emptyList(),
    val annotations: List<String> = emptyList(),
    val frameworks: List<String> = emptyList(),
    val documentation: String? = null
)

data class ClassElement(
    val name: String,
    val type: String = "class", // class, interface, data_class, sealed_class, component, etc.
    val startLine: Int,
    val endLine: Int,
    val modifiers: List<String> = emptyList(),
    val superclass: String? = null,
    val interfaces: List<String> = emptyList(),
    val annotations: List<String> = emptyList(),
    val documentation: String? = null,
    val methods: List<FunctionElement> = emptyList(),
    val fields: List<FieldElement> = emptyList(),
    val purpose: String? = null,
    val patterns: List<String> = emptyList()
)

data class FunctionElement(
    val name: String,
    val startLine: Int,
    val endLine: Int,
    val signature: String? = null,
    val returnType: String? = null,
    val parameters: List<ParameterElement> = emptyList(),
    val modifiers: List<String> = emptyList(),
    val annotations: List<String> = emptyList(),
    val documentation: String? = null,
    val complexity: Int? = null,
    val isPublicApi: Boolean = false,
    val callsTo: List<String> = emptyList()
)

data class FieldElement(
    val name: String,
    val type: String? = null,
    val modifiers: List<String> = emptyList(),
    val initializer: String? = null,
    val annotations: List<String> = emptyList()
)

data class ParameterElement(
    val name: String,
    val type: String? = null,
    val defaultValue: String? = null
)

data class ImportElement(
    val module: String,
    val alias: String? = null,
    val isWildcard: Boolean = false
)

data class ExportElement(
    val name: String,
    val type: String // class, function, variable, constant, etc.
)

data class FileContext(
    val filePath: String,
    val purpose: String,
    val publicApi: List<String>,
    val dependencies: List<String>,
    val patterns: List<String>,
    val keyFunctions: List<FunctionSummary>
)

data class FunctionSummary(
    val signature: String,
    val purpose: String?,
    val complexity: String
)
