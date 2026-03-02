package pl.jclab.refio.core.services.analysis.project

/**
 * Rich project analysis DTO returned by the AST-backed analyzer.
 */
data class ProjectAnalysisReport(
    val projectPath: String,
    val analyzedAt: Long,
    val checksum: String,
    val statistics: ProjectStatistics,
    val codeStructure: CodeStructure,
    val dependencies: DependencyAnalysis,
    val patterns: PatternAnalysis,
    val quality: QualityMetrics,
    val technologies: TechnologyStack,
    val architecture: ArchitecturalInsights
)

data class ProjectStatistics(
    val totalFiles: Int,
    val totalLines: Int,
    val codeLines: Int,
    val commentLines: Int,
    val blankLines: Int,
    val filesByLanguage: Map<String, Int>,
    val linesByLanguage: Map<String, Int>
)

data class CodeStructure(
    val packages: List<PackageInfo>,
    val classes: List<ClassInfo>,
    val interfaces: List<ClassInfo>,
    val enums: List<ClassInfo>,
    val topLevelFunctions: List<FunctionInfo>,
    val classHierarchy: ClassHierarchy
)

data class PackageInfo(
    val name: String,
    val files: List<String>,
    val classes: List<String>,
    val publicApi: List<String>,
    val dependencies: List<String>
)

data class ClassInfo(
    val name: String,
    val qualifiedName: String?,
    val filePath: String,
    val startLine: Int,
    val endLine: Int,
    val modifiers: List<String>,
    val superclass: String?,
    val interfaces: List<String>,
    val annotations: List<String>,
    val documentation: String?,
    val methods: List<FunctionInfo>,
    val fields: List<FieldInfo>,
    val metrics: ClassMetrics
)

data class ClassMetrics(
    val linesOfCode: Int,
    val methodCount: Int,
    val fieldCount: Int
)

data class FunctionInfo(
    val name: String,
    val signature: String?,
    val filePath: String,
    val startLine: Int,
    val endLine: Int,
    val returnType: String?,
    val parameters: List<ParameterInfo>,
    val modifiers: List<String>,
    val annotations: List<String>,
    val documentation: String?
)

data class FieldInfo(
    val name: String,
    val type: String?,
    val modifiers: List<String>,
    val annotations: List<String>
)

data class ParameterInfo(
    val name: String,
    val type: String?
)

data class ClassHierarchy(
    val rootClasses: List<String>,
    val inheritanceTree: Map<String, List<String>>,
    val interfaceImplementations: Map<String, List<String>>
)

data class DependencyAnalysis(
    val imports: List<ImportInfo>,
    val dependencyGraph: DependencyGraph,
    val externalDependencies: List<ExternalDependency>,
    val internalDependencies: List<InternalDependency>,
    val circularDependencies: List<List<String>>,
    val mostUsedClasses: List<ClassUsage>,
    val mostUsedPackages: List<PackageUsage>
)

data class ImportInfo(
    val module: String,
    val member: String? = null,
    val isExternal: Boolean,
    val language: String?,
    val filePath: String
)

data class DependencyGraph(
    val nodes: List<String>,
    val edges: List<DependencyEdge>
)

data class DependencyEdge(
    val from: String,
    val to: String,
    val type: String
)

data class ExternalDependency(
    val name: String,
    val usageCount: Int
)

data class InternalDependency(
    val from: String,
    val to: String,
    val usageCount: Int
)

data class ClassUsage(
    val className: String,
    val usageCount: Int
)

data class PackageUsage(
    val packageName: String,
    val usageCount: Int
)

data class PatternAnalysis(
    val designPatterns: List<DetectedPattern>,
    val frameworkPatterns: List<FrameworkPattern>,
    val namingConventions: NamingConventions,
    val codingStyle: CodingStyle
)

data class DetectedPattern(
    val type: String,
    val confidence: Double,
    val location: String,
    val evidence: List<String>
)

data class FrameworkPattern(
    val framework: String,
    val pattern: String,
    val classes: List<String>
)

data class NamingConventions(
    val classNaming: String,
    val methodNaming: String,
    val constantNaming: String,
    val packageNaming: String
)

data class CodingStyle(
    val indentation: String,
    val braceStyle: String,
    val maxLineLength: Int
)

data class QualityMetrics(
    val averageComplexity: Double,
    val maxComplexity: Int,
    val complexMethods: List<ComplexMethod>,
    val codeSmells: List<CodeSmell>,
    val documentationCoverage: DocumentationCoverage,
    val testCoverage: TestCoverage?
)

data class ComplexMethod(
    val name: String,
    val qualifiedName: String,
    val complexity: Int,
    val linesOfCode: Int,
    val filePath: String,
    val startLine: Int
)

data class CodeSmell(
    val type: String,
    val severity: String,
    val location: String,
    val description: String
)

data class DocumentationCoverage(
    val documentedSymbols: Int,
    val undocumentedSymbols: Int,
    val coveragePercent: Double
)

data class TestCoverage(
    val lineCoverage: Double,
    val branchCoverage: Double,
    val methodCoverage: Double
)

data class TechnologyStack(
    val languages: Map<String, LanguageInfo>,
    val frameworks: List<FrameworkInfo>,
    val libraries: List<LibraryInfo>,
    val buildTools: List<String>,
    val infrastructure: List<String>
)

data class LanguageInfo(
    val name: String,
    val version: String?,
    val fileCount: Int,
    val lineCount: Int,
    val percentage: Double
)

data class FrameworkInfo(
    val name: String,
    val version: String?,
    val confidence: Double,
    val detectedFrom: List<String>
)

data class LibraryInfo(
    val name: String,
    val version: String?,
    val usageCount: Int
)

data class ArchitecturalInsights(
    val style: String?,
    val layers: List<Layer>,
    val modules: List<Module>,
    val apiSurface: ApiSurface,
    val dataFlow: DataFlow
)

data class Layer(
    val name: String,
    val packages: List<String>,
    val classes: List<String>,
    val dependencies: List<String>
)

data class Module(
    val name: String,
    val packages: List<String>,
    val publicApi: List<String>,
    val dependencies: List<String>
)

data class ApiSurface(
    val publicClasses: List<String>,
    val publicMethods: List<String>,
    val entryPoints: List<String>
)

data class DataFlow(
    val sources: List<String>,
    val sinks: List<String>,
    val transformations: List<String>
)
