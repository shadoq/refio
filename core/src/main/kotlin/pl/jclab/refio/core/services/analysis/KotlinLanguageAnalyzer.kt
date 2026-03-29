package pl.jclab.refio.core.services.analysis

import java.nio.file.Path

class KotlinLanguageAnalyzer : ExtensionLanguageAnalyzer(
    languageId = "kotlin",
    extensions = setOf(".kt", ".kts")
) {

    private val classRegex = Regex(
        """(data\s+|sealed\s+|abstract\s+|open\s+|internal\s+|private\s+|public\s+)?class\s+([A-Za-z0-9_]+)\s*(?:\:\s*([A-Za-z0-9_<>,\s]+))?"""
    )
    private val interfaceRegex = Regex(
        """(internal\s+|private\s+|public\s+)?interface\s+([A-Za-z0-9_]+)\s*(?:\:\s*([A-Za-z0-9_<>,\s]+))?"""
    )
    private val objectRegex = Regex("""object\s+([A-Za-z0-9_]+)""")
    private val functionRegex = Regex(
        """(suspend\s+)?fun\s+((?:[A-Za-z0-9_]+)\.)?([A-Za-z0-9_]+)\s*\(([^)]*)\)\s*(?::\s*([^\{=]+))?"""
    )
    private val importRegex = Regex("""import\s+([\w.\*]+)(?:\s+as\s+(\w+))?""")

    override fun analyze(filePath: Path, content: String): CodeElements {
        val lines = content.lines()
        val classes = mutableListOf<ClassElement>()
        val functions = mutableListOf<FunctionElement>()
        val annotations = mutableSetOf<String>()

        classRegex.findAll(content).forEach { match ->
            val modifier = match.groupValues[1].trim()
            val name = match.groupValues[2]
            val inheritance = match.groupValues[3].split(",").map { it.trim() }.filter { it.isNotBlank() }
            val startLine = lineNumberAt(content, match.range.first)
            val endLine = findBlockEndLine(lines, startLine)
            val classAnnotations = annotationsAbove(lines, startLine)
            annotations.addAll(classAnnotations)
            val documentation = extractKDocBefore(lines, startLine)
            val purpose = inferKotlinClassPurpose(name, classAnnotations)
            val patterns = detectKotlinClassPatterns(name, modifier, classAnnotations)

            classes.add(
                ClassElement(
                    name = name,
                    type = when {
                        modifier.contains("data") -> "data_class"
                        modifier.contains("sealed") -> "sealed_class"
                        else -> "class"
                    },
                    startLine = startLine,
                    endLine = endLine,
                    superclass = inheritance.firstOrNull(),
                    interfaces = inheritance.drop(1),
                    annotations = classAnnotations,
                    documentation = documentation,
                    purpose = purpose,
                    patterns = patterns
                )
            )
        }

        interfaceRegex.findAll(content).forEach { match ->
            val name = match.groupValues[2]
            val inheritance = match.groupValues[3].split(",").map { it.trim() }.filter { it.isNotBlank() }
            val startLine = lineNumberAt(content, match.range.first)
            val endLine = findBlockEndLine(lines, startLine)
            val interfaceAnnotations = annotationsAbove(lines, startLine)
            annotations.addAll(interfaceAnnotations)
            val documentation = extractKDocBefore(lines, startLine)

            classes.add(
                ClassElement(
                    name = name,
                    type = "interface",
                    startLine = startLine,
                    endLine = endLine,
                    superclass = inheritance.firstOrNull(),
                    interfaces = inheritance.drop(1),
                    annotations = interfaceAnnotations,
                    documentation = documentation,
                    purpose = "Service Contract Interface"
                )
            )
        }

        objectRegex.findAll(content).forEach { match ->
            val name = match.groupValues[1]
            val startLine = lineNumberAt(content, match.range.first)
            val endLine = findBlockEndLine(lines, startLine)
            val classAnnotations = annotationsAbove(lines, startLine)
            annotations.addAll(classAnnotations)
            val documentation = extractKDocBefore(lines, startLine)

            classes.add(
                ClassElement(
                    name = name,
                    type = "object",
                    startLine = startLine,
                    endLine = endLine,
                    annotations = classAnnotations,
                    documentation = documentation,
                    patterns = listOf("Singleton")
                )
            )
        }

        functionRegex.findAll(content).forEach { match ->
            val modifier = match.groupValues[1]
            val receiver = match.groupValues[2].trim().ifBlank { null }
            val name = match.groupValues[3]
            val paramsStr = match.groupValues[4]
            val returnType = match.groupValues[5].trim().ifBlank { null }
            val startLine = lineNumberAt(content, match.range.first)
            val endLine = findBlockEndLine(lines, startLine)
            val fnAnnotations = annotationsAbove(lines, startLine)
            annotations.addAll(fnAnnotations)
            val parameters = parseKotlinParameters(paramsStr)
            val signature = buildKotlinSignature(modifier, receiver, name, parameters, returnType)
            val documentation = extractKDocBefore(lines, startLine)
            val complexity = estimateKotlinComplexity(lines, startLine, endLine)
            val callsTo = extractKotlinCalls(lines, startLine, endLine, name)

            functions.add(
                FunctionElement(
                    name = name,
                    startLine = startLine,
                    endLine = endLine,
                    signature = signature,
                    returnType = returnType,
                    parameters = parameters,
                    modifiers = if (modifier.isNotBlank()) listOf(modifier.trim()) else emptyList(),
                    annotations = fnAnnotations,
                    documentation = documentation,
                    complexity = complexity,
                    isPublicApi = !modifier.contains("private") && !name.startsWith("_"),
                    callsTo = callsTo
                )
            )
        }

        if (annotations.isEmpty()) {
            Regex("""@([A-Za-z0-9_]+)""").findAll(content).forEach { annotations.add(it.groupValues[1]) }
        }

        val imports = importRegex.findAll(content).map {
            ImportElement(
                module = it.groupValues[1],
                alias = it.groupValues[2].ifBlank { null },
                isWildcard = it.groupValues[1].endsWith(".*")
            )
        }.toList()

        val coroutineMarkers = mutableListOf<String>()
        if ("launch(" in content) coroutineMarkers.add("launch")
        if ("async(" in content) coroutineMarkers.add("async")
        if ("Flow<" in content) coroutineMarkers.add("Flow")
        if ("StateFlow<" in content) coroutineMarkers.add("StateFlow")
        if ("withContext" in content) coroutineMarkers.add("withContext")

        return CodeElements(
            classes = classes,
            functions = functions,
            imports = imports,
            annotations = annotations.toList(),
            frameworks = coroutineMarkers.distinct()
        )
    }

    private fun extractKDocBefore(lines: List<String>, startLine: Int): String? {
        var i = startLine - 2
        while (i >= 0) {
            val line = lines[i].trim()
            if (line.isEmpty()) {
                i--
                continue
            }
            if (line.startsWith("@")) {
                i--
                continue
            }
            if (line.endsWith("*/")) {
                val buffer = StringBuilder()
                var j = i
                while (j >= 0) {
                    val current = lines[j].trim()
                    buffer.insert(0, current + "\n")
                    if (current.startsWith("/**")) {
                        return buffer.toString()
                            .replace("/**", "")
                            .replace("*/", "")
                            .lines()
                            .map { it.trimStart('*', ' ').trim() }
                            .joinToString(" ")
                            .trim()
                            .ifBlank { null }
                    }
                    j--
                }
                return null
            }
            break
        }
        return null
    }

    private fun parseKotlinParameters(paramsStr: String): List<ParameterElement> {
        if (paramsStr.isBlank()) return emptyList()
        return splitKotlinParameters(paramsStr).mapNotNull { raw ->
            val trimmed = raw.trim()
            if (trimmed.isBlank()) return@mapNotNull null
            val parts = trimmed.split(":").map { it.trim() }
            val name = parts.firstOrNull()?.split(" ")?.lastOrNull()?.trim().orEmpty()
            if (name.isBlank()) return@mapNotNull null
            val type = parts.getOrNull(1)?.split("=")?.firstOrNull()?.trim()
            val defaultValue = trimmed.substringAfter("=", "").trim().ifBlank { null }
            ParameterElement(name = name, type = type, defaultValue = defaultValue)
        }
    }

    private fun splitKotlinParameters(paramsStr: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        paramsStr.forEach { ch ->
            when (ch) {
                '<', '(', '[' -> {
                    depth++
                    current.append(ch)
                }
                '>', ')', ']' -> {
                    depth = (depth - 1).coerceAtLeast(0)
                    current.append(ch)
                }
                ',' -> {
                    if (depth == 0) {
                        parts.add(current.toString())
                        current.setLength(0)
                    } else {
                        current.append(ch)
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotBlank()) parts.add(current.toString())
        return parts
    }

    private fun buildKotlinSignature(
        modifier: String,
        receiver: String?,
        name: String,
        parameters: List<ParameterElement>,
        returnType: String?
    ): String {
        val suspendPrefix = if (modifier.isNotBlank()) "suspend " else ""
        val receiverPrefix = receiver?.let { "${it.trim()}" } ?: ""
        val paramsStr = parameters.joinToString(", ") { param ->
            val typed = if (param.type != null) "${param.name}: ${param.type}" else param.name
            if (param.defaultValue != null) "$typed = ${param.defaultValue}" else typed
        }
        val returnStr = if (returnType != null) ": $returnType" else ""
        return "${suspendPrefix}fun ${receiverPrefix}${name}($paramsStr)$returnStr".trim()
    }

    private fun estimateKotlinComplexity(lines: List<String>, startLine: Int, endLine: Int): Int {
        val keywords = listOf("if", "for", "while", "when", "catch", "&&", "||", "?")
        val body = lines.subList((startLine - 1).coerceAtLeast(0), endLine.coerceAtMost(lines.size))
            .joinToString("\n")
        val keywordHits = keywords.sumOf { keyword ->
            if (keyword.all { it.isLetter() }) {
                Regex("""\b$keyword\b""").findAll(body).count()
            } else {
                body.split(keyword).size - 1
            }
        }
        return 1 + keywordHits
    }

    private fun extractKotlinCalls(lines: List<String>, startLine: Int, endLine: Int, functionName: String): List<String> {
        val body = lines.subList((startLine - 1).coerceAtLeast(0), endLine.coerceAtMost(lines.size)).joinToString("\n")
        val callRegex = Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        val ignored = setOf("if", "for", "while", "return", "when", "catch", "throw", "super", "this")
        return callRegex.findAll(body)
            .map { it.groupValues[1] }
            .filter { it != functionName && it !in ignored }
            .distinct()
            .take(15)
            .toList()
    }

    private fun inferKotlinClassPurpose(name: String, annotations: List<String>): String? {
        return when {
            annotations.any { it.contains("Controller") } -> "REST API Controller"
            annotations.any { it.contains("Service") } -> "Business Logic Service"
            annotations.any { it.contains("Repository") } -> "Data Access Repository"
            name.endsWith("DTO") || name.endsWith("Request") || name.endsWith("Response") -> "Data Transfer Object"
            else -> null
        }
    }

    private fun detectKotlinClassPatterns(name: String, modifier: String, annotations: List<String>): List<String> {
        val patterns = mutableListOf<String>()
        if (modifier.contains("data")) patterns.add("DataClass")
        if (modifier.contains("sealed")) patterns.add("Sealed")
        if (annotations.any { it.contains("Singleton") }) patterns.add("Singleton")
        if (name.endsWith("Factory")) patterns.add("Factory")
        if (name.endsWith("Builder")) patterns.add("Builder")
        return patterns
    }
}
