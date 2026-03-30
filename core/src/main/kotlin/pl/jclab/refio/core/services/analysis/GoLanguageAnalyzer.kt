package pl.jclab.refio.core.services.analysis

import java.nio.file.Path

class GoLanguageAnalyzer : ExtensionLanguageAnalyzer(
    languageId = "go",
    extensions = setOf(".go")
) {

    private val packageRegex = Regex("""^package\s+(\w+)""", RegexOption.MULTILINE)
    private val importSingleRegex = Regex("""import\s+"([^"]+)"""")
    private val importBlockRegex = Regex("""import\s*\((.*?)\)""", RegexOption.DOT_MATCHES_ALL)
    private val importLineRegex = Regex("""\s*(?:(\w+)\s+)?"([^"]+)"""")

    private val structRegex = Regex(
        """type\s+([A-Za-z_][A-Za-z0-9_]*)\s+struct\s*\{"""
    )
    private val interfaceRegex = Regex(
        """type\s+([A-Za-z_][A-Za-z0-9_]*)\s+interface\s*\{"""
    )
    private val typeAliasRegex = Regex(
        """type\s+([A-Za-z_][A-Za-z0-9_]*)\s+(?!struct|interface)([^\n{]+)"""
    )
    private val funcRegex = Regex(
        """func\s+(?:\(([^)]+)\)\s*)?([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)\s*(?:\(([^)]*)\)|([^\n{]*))?"""
    )
    private val constVarRegex = Regex(
        """(?:^|\n)\s*(const|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:([A-Za-z_][A-Za-z0-9_.*\[\]]*))?\s*="""
    )
    private val structFieldRegex = Regex(
        """^\s+([A-Za-z_][A-Za-z0-9_]*)\s+([^\n`]+?)(?:\s+`[^`]*`)?\s*$"""
    )

    override fun analyze(filePath: Path, content: String): CodeElements {
        val lines = content.lines()
        val joined = joinMultilineDeclarations(content)
        val classes = mutableListOf<ClassElement>()
        val functions = mutableListOf<FunctionElement>()
        val topFields = mutableListOf<FieldElement>()

        // Structs
        structRegex.findAll(content).forEach { match ->
            val name = match.groupValues[1]
            val startLine = lineNumberAt(content, match.range.first)
            val endLine = findBlockEndLine(lines, startLine)
            val fields = extractStructFields(lines, startLine, endLine)
            val documentation = extractGoDocBefore(lines, startLine)
            val purpose = inferGoPurpose(name, fields)

            classes.add(
                ClassElement(
                    name = name,
                    type = "struct",
                    startLine = startLine,
                    endLine = endLine,
                    fields = fields,
                    documentation = documentation,
                    purpose = purpose,
                    patterns = detectGoPatterns(name, fields)
                )
            )
        }

        // Interfaces
        interfaceRegex.findAll(content).forEach { match ->
            val name = match.groupValues[1]
            val startLine = lineNumberAt(content, match.range.first)
            val endLine = findBlockEndLine(lines, startLine)
            val methods = extractInterfaceMethods(lines, startLine, endLine)
            val documentation = extractGoDocBefore(lines, startLine)

            classes.add(
                ClassElement(
                    name = name,
                    type = "interface",
                    startLine = startLine,
                    endLine = endLine,
                    methods = methods,
                    documentation = documentation,
                    purpose = "Interface Contract"
                )
            )
        }

        // Type aliases
        typeAliasRegex.findAll(content).forEach { match ->
            val name = match.groupValues[1]
            val aliasTarget = match.groupValues[2].trim()
            if (aliasTarget.isBlank() || aliasTarget.startsWith("{")) return@forEach
            topFields.add(FieldElement(name = name, type = aliasTarget, modifiers = listOf("type_alias")))
        }

        // Functions and methods
        funcRegex.findAll(joined).forEach { match ->
            val receiver = match.groupValues[1].trim().ifBlank { null }
            val name = match.groupValues[2]
            val paramsStr = match.groupValues[3]
            val multiReturn = match.groupValues[4].trim().ifBlank { null }
            val singleReturn = match.groupValues[5].trim().ifBlank { null }
            val returnType = multiReturn?.let { "($it)" } ?: singleReturn
            val startLine = lineNumberAt(content, findFuncIndex(content, name, receiver))
            val endLine = findBlockEndLine(lines, startLine)
            val parameters = parseGoParameters(paramsStr)
            val documentation = extractGoDocBefore(lines, startLine)
            val complexity = estimateComplexity(lines, startLine, endLine)
            val isExported = name[0].isUpperCase()

            val signature = buildGoSignature(receiver, name, parameters, returnType)

            functions.add(
                FunctionElement(
                    name = name,
                    startLine = startLine,
                    endLine = endLine,
                    signature = signature,
                    returnType = returnType,
                    parameters = parameters,
                    modifiers = listOfNotNull(
                        if (receiver != null) "method" else null,
                        if (isExported) "exported" else "unexported"
                    ),
                    documentation = documentation,
                    complexity = complexity,
                    isPublicApi = isExported
                )
            )
        }

        // Constants and variables
        constVarRegex.findAll(content).forEach { match ->
            val kind = match.groupValues[1]
            val name = match.groupValues[2]
            val type = match.groupValues[3].trim().ifBlank { null }
            topFields.add(FieldElement(name = name, type = type, modifiers = listOf(kind)))
        }

        // Imports
        val imports = mutableListOf<ImportElement>()
        importSingleRegex.findAll(content).forEach {
            imports.add(ImportElement(module = it.groupValues[1]))
        }
        importBlockRegex.findAll(content).forEach { blockMatch ->
            importLineRegex.findAll(blockMatch.groupValues[1]).forEach { lineMatch ->
                val alias = lineMatch.groupValues[1].ifBlank { null }
                val path = lineMatch.groupValues[2]
                imports.add(ImportElement(module = path, alias = alias))
            }
        }

        val frameworks = detectGoFrameworks(imports, content)

        // Package-level file class for top-level fields
        if (topFields.isNotEmpty()) {
            val pkg = packageRegex.find(content)?.groupValues?.get(1) ?: "main"
            classes.add(
                ClassElement(
                    name = pkg,
                    type = "package",
                    startLine = 1,
                    endLine = lines.size,
                    fields = topFields,
                    purpose = "Package-level declarations"
                )
            )
        }

        return CodeElements(
            classes = classes,
            functions = functions,
            imports = imports,
            frameworks = frameworks
        )
    }

    private fun findFuncIndex(content: String, name: String, receiver: String?): Int {
        val pattern = if (receiver != null) {
            Regex("""func\s*\([^)]*\)\s*${Regex.escape(name)}\s*\(""")
        } else {
            Regex("""func\s+${Regex.escape(name)}\s*\(""")
        }
        return pattern.find(content)?.range?.first ?: content.indexOf(name).coerceAtLeast(0)
    }

    private fun extractStructFields(lines: List<String>, startLine: Int, endLine: Int): List<FieldElement> {
        val fields = mutableListOf<FieldElement>()
        for (i in startLine until (endLine - 1).coerceAtMost(lines.size)) {
            val line = lines[i]
            if (line.trim().startsWith("//") || line.trim().isEmpty()) continue
            val match = structFieldRegex.find(line) ?: continue
            val name = match.groupValues[1]
            val type = match.groupValues[2].trim()
            if (name == "}" || type.isEmpty()) continue
            val isExported = name[0].isUpperCase()
            fields.add(
                FieldElement(
                    name = name,
                    type = type,
                    modifiers = if (isExported) listOf("exported") else listOf("unexported")
                )
            )
        }
        return fields
    }

    private fun extractInterfaceMethods(lines: List<String>, startLine: Int, endLine: Int): List<FunctionElement> {
        val methods = mutableListOf<FunctionElement>()
        val methodPattern = Regex("""^\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(([^)]*)\)\s*(.*)$""")
        for (i in startLine until (endLine - 1).coerceAtMost(lines.size)) {
            val match = methodPattern.find(lines[i]) ?: continue
            val name = match.groupValues[1]
            val paramsStr = match.groupValues[2]
            val returnType = match.groupValues[3].trim().ifBlank { null }
            methods.add(
                FunctionElement(
                    name = name,
                    startLine = i + 1,
                    endLine = i + 1,
                    parameters = parseGoParameters(paramsStr),
                    returnType = returnType,
                    isPublicApi = name[0].isUpperCase()
                )
            )
        }
        return methods
    }

    private fun parseGoParameters(paramsStr: String): List<ParameterElement> {
        if (paramsStr.isBlank()) return emptyList()
        return paramsStr.split(",").mapNotNull { raw ->
            val trimmed = raw.trim()
            if (trimmed.isBlank()) return@mapNotNull null
            val tokens = trimmed.split(Regex("\\s+"))
            when (tokens.size) {
                1 -> ParameterElement(name = "", type = tokens[0])
                else -> ParameterElement(name = tokens.first(), type = tokens.drop(1).joinToString(" "))
            }
        }
    }

    private fun buildGoSignature(receiver: String?, name: String, params: List<ParameterElement>, returnType: String?): String {
        val rcv = receiver?.let { "($it) " } ?: ""
        val paramsStr = params.joinToString(", ") { p ->
            if (p.name.isNotBlank()) "${p.name} ${p.type ?: ""}" else p.type ?: ""
        }
        val ret = returnType?.let { " $it" } ?: ""
        return "func ${rcv}${name}($paramsStr)$ret".trim()
    }

    private fun extractGoDocBefore(lines: List<String>, startLine: Int): String? {
        val docLines = mutableListOf<String>()
        var i = startLine - 2
        while (i >= 0) {
            val line = lines[i].trim()
            if (line.startsWith("//")) {
                docLines.add(0, line.removePrefix("//").trim())
                i--
            } else break
        }
        return docLines.joinToString(" ").trim().ifBlank { null }
    }

    private fun estimateComplexity(lines: List<String>, startLine: Int, endLine: Int): Int {
        val keywords = listOf("if", "for", "switch", "case", "select", "&&", "||")
        val body = lines.subList((startLine - 1).coerceAtLeast(0), endLine.coerceAtMost(lines.size)).joinToString("\n")
        val hits = keywords.sumOf { kw ->
            if (kw.all { it.isLetter() }) Regex("""\b$kw\b""").findAll(body).count()
            else body.windowed(kw.length).count { it == kw }
        }
        return 1 + hits
    }

    private fun inferGoPurpose(name: String, fields: List<FieldElement>): String? {
        return when {
            name.endsWith("Handler") -> "HTTP Handler"
            name.endsWith("Server") -> "Server"
            name.endsWith("Client") -> "Client"
            name.endsWith("Service") -> "Service"
            name.endsWith("Repository") || name.endsWith("Store") -> "Data Store"
            name.endsWith("Config") || name.endsWith("Options") -> "Configuration"
            name.endsWith("Request") || name.endsWith("Response") -> "API Model"
            name.endsWith("Error") -> "Error Type"
            name.endsWith("Middleware") -> "Middleware"
            name.endsWith("Mock") || name.endsWith("Stub") -> "Test Double"
            fields.any { it.name == "mu" || it.type?.contains("sync.Mutex") == true } -> "Thread-safe Type"
            else -> null
        }
    }

    private fun detectGoPatterns(name: String, fields: List<FieldElement>): List<String> {
        val patterns = mutableListOf<String>()
        if (name.endsWith("Factory")) patterns.add("Factory")
        if (name.endsWith("Builder")) patterns.add("Builder")
        if (name.endsWith("Pool")) patterns.add("Pool")
        if (fields.any { it.type?.contains("sync.Mutex") == true || it.type?.contains("sync.RWMutex") == true }) patterns.add("Mutex-protected")
        if (fields.any { it.type?.contains("chan ") == true }) patterns.add("Channel-based")
        return patterns
    }

    private fun detectGoFrameworks(imports: List<ImportElement>, content: String): List<String> {
        val frameworks = mutableListOf<String>()
        val importPaths = imports.map { it.module }.toSet()
        if (importPaths.any { it.startsWith("github.com/gin-gonic/gin") }) frameworks.add("Gin")
        if (importPaths.any { it.startsWith("github.com/labstack/echo") }) frameworks.add("Echo")
        if (importPaths.any { it.startsWith("github.com/gofiber/fiber") }) frameworks.add("Fiber")
        if (importPaths.any { it == "net/http" }) frameworks.add("net/http")
        if (importPaths.any { it.startsWith("github.com/gorilla/mux") }) frameworks.add("Gorilla Mux")
        if (importPaths.any { it.startsWith("google.golang.org/grpc") }) frameworks.add("gRPC")
        if (importPaths.any { it.startsWith("gorm.io") }) frameworks.add("GORM")
        if (importPaths.any { it.startsWith("github.com/jmoiron/sqlx") }) frameworks.add("sqlx")
        if (importPaths.any { it == "database/sql" }) frameworks.add("database/sql")
        if (importPaths.any { it == "testing" }) frameworks.add("Go Testing")
        if (importPaths.any { it.startsWith("github.com/stretchr/testify") }) frameworks.add("Testify")
        if ("go func(" in content || "go " in content) frameworks.add("Goroutines")
        return frameworks.distinct()
    }
}
