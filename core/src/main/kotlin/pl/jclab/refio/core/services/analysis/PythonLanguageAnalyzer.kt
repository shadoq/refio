package pl.jclab.refio.core.services.analysis

import java.nio.file.Path

class PythonLanguageAnalyzer : ExtensionLanguageAnalyzer(
    languageId = "python",
    extensions = setOf(".py")
) {

    private val classRegex = Regex("""class\s+([A-Za-z0-9_]+)\s*(?:\(([^)]*)\))?""")
    private val functionWithTypesRegex = Regex(
        """(async\s+)?def\s+([A-Za-z0-9_]+)\s*\(([^)]*)\)\s*(?:->\s*([^:]+))?:"""
    )
    private val decoratorRegex = Regex("""@([A-Za-z0-9_\.]+)""")

    override fun analyze(filePath: Path, content: String): CodeElements {
        val lines = content.lines()
        val classes = mutableListOf<ClassElement>()
        val functions = mutableListOf<FunctionElement>()
        val decorators = mutableSetOf<String>()
        val frameworks = mutableListOf<String>()

        classRegex.findAll(content).forEach { match ->
            val name = match.groupValues[1]
            val baseClasses = match.groupValues[2].split(",").map { it.trim() }.filter { it.isNotBlank() }
            val startLine = lineNumberAt(content, match.range.first)
            val endLine = findPythonBlockEnd(lines, startLine)
            val classDecorators = collectPythonDecorators(lines, startLine)
            decorators.addAll(classDecorators)
            val documentation = extractPythonDocstring(lines, startLine)
            val purpose = inferPythonClassPurpose(name, classDecorators, baseClasses)
            val patterns = detectPythonClassPatterns(name, classDecorators, baseClasses)

            classes.add(
                ClassElement(
                    name = name,
                    superclass = baseClasses.firstOrNull(),
                    interfaces = baseClasses.drop(1),
                    startLine = startLine,
                    endLine = endLine,
                    annotations = classDecorators,
                    documentation = documentation,
                    purpose = purpose,
                    patterns = patterns
                )
            )
        }

        functionWithTypesRegex.findAll(content).forEach { match ->
            val modifier = match.groupValues[1].trim()
            val name = match.groupValues[2]
            val paramsStr = match.groupValues[3]
            val returnType = match.groupValues[4].trim().ifBlank { null }
            val startLine = lineNumberAt(content, match.range.first)
            val endLine = findPythonBlockEnd(lines, startLine)
            val fnDecorators = collectPythonDecorators(lines, startLine)
            decorators.addAll(fnDecorators)
            val parameters = parsePythonParameters(paramsStr)
            val signature = buildPythonSignature(modifier, name, parameters, returnType)
            val documentation = extractPythonDocstring(lines, startLine)
            val complexity = estimatePythonComplexity(lines, startLine, endLine)
            val callsTo = extractPythonCalls(lines, startLine, endLine, name)

            functions.add(
                FunctionElement(
                    name = name,
                    startLine = startLine,
                    endLine = endLine,
                    signature = signature,
                    returnType = returnType,
                    parameters = parameters,
                    modifiers = if (modifier.isNotBlank()) listOf(modifier.trim()) else emptyList(),
                    annotations = fnDecorators,
                    documentation = documentation,
                    complexity = complexity,
                    isPublicApi = !name.startsWith("_"),
                    callsTo = callsTo
                )
            )
        }

        decoratorRegex.findAll(content).forEach { decorators.add(it.groupValues[1]) }

        if ("fastapi" in content) frameworks.add("FastAPI")
        if ("flask" in content) frameworks.add("Flask")
        if ("django" in content.lowercase()) frameworks.add("Django")
        if ("pydantic" in content || "BaseModel" in content) frameworks.add("Pydantic")
        if ("dataclass" in content) frameworks.add("dataclasses")

        return CodeElements(
            classes = classes,
            functions = functions,
            annotations = decorators.toList(),
            frameworks = frameworks.distinct()
        )
    }

    private fun collectPythonDecorators(lines: List<String>, startLine: Int): List<String> {
        val result = mutableListOf<String>()
        for (i in startLine - 2 downTo 0) {
            val trimmed = lines.getOrNull(i)?.trim() ?: break
            if (trimmed.startsWith("@")) {
                result.add(trimmed.removePrefix("@").takeWhile { !it.isWhitespace() })
            } else if (trimmed.isNotEmpty()) {
                break
            }
        }
        return result.reversed()
    }

    private fun findPythonBlockEnd(lines: List<String>, startLine: Int): Int {
        val indent = lines.getOrNull(startLine - 1)?.takeWhile { it == ' ' || it == '\t' } ?: ""
        for (i in startLine until lines.size) {
            val line = lines[i]
            if (line.isBlank()) continue
            val currentIndent = line.takeWhile { it == ' ' || it == '\t' }
            if (currentIndent.length <= indent.length) {
                return i
            }
        }
        return lines.size
    }

    private fun parsePythonParameters(paramsStr: String): List<ParameterElement> {
        if (paramsStr.isBlank()) return emptyList()
        return splitPythonParameters(paramsStr).mapNotNull { raw ->
            val trimmed = raw.trim()
            if (trimmed.isBlank()) return@mapNotNull null
            val match = Regex("""(\*{0,2}[A-Za-z0-9_]+)(?:\s*:\s*([^=,]+))?(?:\s*=\s*(.+))?""")
                .find(trimmed) ?: return@mapNotNull null
            val name = match.groupValues[1].trim()
            val type = match.groupValues[2].trim().ifBlank { null }
            val defaultValue = match.groupValues[3].trim().ifBlank { null }
            ParameterElement(name = name, type = type, defaultValue = defaultValue)
        }
    }

    private fun splitPythonParameters(paramsStr: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        paramsStr.forEach { ch ->
            when (ch) {
                '(', '[', '{' -> {
                    depth++
                    current.append(ch)
                }
                ')', ']', '}' -> {
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
        if (current.isNotBlank()) {
            parts.add(current.toString())
        }
        return parts
    }

    private fun extractPythonDocstring(lines: List<String>, startLine: Int): String? {
        val defIndent = lines.getOrNull(startLine - 1)?.takeWhile { it == ' ' || it == '\t' } ?: ""
        var i = startLine
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) {
                i++
                continue
            }
            val indent = line.takeWhile { it == ' ' || it == '\t' }
            if (indent.length <= defIndent.length) return null
            val trimmed = line.trimStart()
            val quote = when {
                trimmed.startsWith("\"\"\"") -> "\"\"\""
                trimmed.startsWith("'''") -> "'''"
                else -> null
            } ?: return null
            val startIdx = line.indexOf(quote) + quote.length
            val remainder = line.substring(startIdx)
            if (remainder.contains(quote)) {
                return remainder.substringBefore(quote).trim().take(500)
            }
            val buffer = StringBuilder(remainder)
            var j = i + 1
            while (j < lines.size) {
                val next = lines[j]
                val endIdx = next.indexOf(quote)
                if (endIdx >= 0) {
                    if (buffer.isNotEmpty()) buffer.appendLine()
                    buffer.append(next.substring(0, endIdx))
                    return buffer.toString().trim().take(500)
                }
                if (buffer.isNotEmpty()) buffer.appendLine()
                buffer.append(next)
                j++
            }
            return buffer.toString().trim().take(500)
        }
        return null
    }

    private fun buildPythonSignature(
        modifier: String,
        name: String,
        parameters: List<ParameterElement>,
        returnType: String?
    ): String {
        val asyncPrefix = if (modifier.isNotBlank()) "async " else ""
        val paramsStr = parameters.joinToString(", ") { param ->
            val typed = if (param.type != null) "${param.name}: ${param.type}" else param.name
            if (param.defaultValue != null) "$typed = ${param.defaultValue}" else typed
        }
        val returnStr = if (returnType != null) " -> $returnType" else ""
        return "${asyncPrefix}def $name($paramsStr)$returnStr"
    }

    private fun estimatePythonComplexity(lines: List<String>, startLine: Int, endLine: Int): Int {
        val keywords = listOf("if", "elif", "for", "while", "and", "or", "except", "case", "match", "with")
        val body = lines.subList((startLine - 1).coerceAtLeast(0), endLine.coerceAtMost(lines.size))
            .joinToString("\n")
        val keywordHits = keywords.sumOf { keyword ->
            Regex("""\b$keyword\b""").findAll(body).count()
        }
        return 1 + keywordHits
    }

    private fun extractPythonCalls(lines: List<String>, startLine: Int, endLine: Int, functionName: String): List<String> {
        val body = lines.subList((startLine - 1).coerceAtLeast(0), endLine.coerceAtMost(lines.size)).joinToString("\n")
        val callRegex = Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        val ignored = setOf(
            "if", "for", "while", "return", "with", "await", "async", "def", "class", "print", "super"
        )
        return callRegex.findAll(body)
            .map { it.groupValues[1] }
            .filter { it != functionName && it !in ignored }
            .distinct()
            .take(15)
            .toList()
    }

    private fun inferPythonClassPurpose(
        name: String,
        decorators: List<String>,
        baseClasses: List<String>
    ): String? {
        return when {
            decorators.any { it.contains("router") || it.contains("app") } -> "API Controller"
            name.endsWith("Controller") -> "Controller"
            name.endsWith("Service") -> "Service"
            name.endsWith("Repository") -> "Repository"
            name.endsWith("DTO") || name.endsWith("Request") || name.endsWith("Response") -> "Data Transfer Object"
            baseClasses.any { it.contains("BaseModel") } -> "Pydantic Model"
            decorators.any { it.contains("dataclass") } -> "Dataclass"
            else -> null
        }
    }

    private fun detectPythonClassPatterns(
        name: String,
        decorators: List<String>,
        baseClasses: List<String>
    ): List<String> {
        val patterns = mutableListOf<String>()
        if (decorators.any { it.contains("dataclass") }) patterns.add("Dataclass")
        if (baseClasses.any { it.contains("BaseModel") }) patterns.add("PydanticModel")
        if (name.endsWith("Factory")) patterns.add("Factory")
        if (name.endsWith("Builder")) patterns.add("Builder")
        return patterns
    }
}
