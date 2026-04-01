package pl.jclab.refio.core.services.analysis

import java.nio.file.Path

class TypeScriptLanguageAnalyzer : ExtensionLanguageAnalyzer(
    languageId = "typescript",
    extensions = setOf(".ts", ".tsx", ".js", ".jsx")
) {

    private val classRegex = Regex("""class\s+([A-Za-z0-9_]+)""")
    private val interfaceRegex = Regex(
        """(?:export\s+)?interface\s+([A-Za-z0-9_]+)(?:<[^>]+>)?\s*(?:extends\s+[\w,\s<>]+)?\s*\{([^}]+)\}""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val typeAliasRegex = Regex(
        """(?:export\s+)?type\s+([A-Za-z0-9_]+)(?:<[^>]+>)?\s*=\s*([^;]+);"""
    )
    private val functionWithTypesRegex = Regex(
        """(?:export\s+)?(?:async\s+)?function\s+([A-Za-z0-9_]+)(?:<[^>]+>)?\s*\(([^)]*)\)\s*(?::\s*([^{]+))?\s*\{"""
    )
    private val arrowFunctionWithTypesRegex = Regex(
        """(?:export\s+)?const\s+([A-Za-z0-9_]+)(?::\s*[^=]+)?\s*=\s*(?:async\s+)?\(([^)]*)\)(?:\s*:\s*([^=]+))?\s*=>"""
    )
    private val reactComponentRegex = Regex("""function\s+([A-Z][A-Za-z0-9_]*)\s*\(""")
    private val importRegex = Regex("""import\s+(?:[\w{}\*,\s]+)\s+from\s+['"]([^'"]+)['"]""")
    private val exportRegex = Regex("""export\s+(class|function|const|default)?\s*([A-Za-z0-9_]+)?""")

    override fun analyze(filePath: Path, content: String): CodeElements {
        val lines = content.lines()
        val classes = mutableListOf<ClassElement>()
        val functions = mutableListOf<FunctionElement>()
        val exports = mutableListOf<ExportElement>()
        val frameworks = mutableListOf<String>()

        classRegex.findAll(content).forEach { match ->
            val name = match.groupValues[1]
            val startLine = lineNumberAt(content, match.range.first)
            val endLine = findBlockEndLine(lines, startLine)
            val documentation = extractJsdocBefore(lines, startLine)
            classes.add(
                ClassElement(
                    name = name,
                    startLine = startLine,
                    endLine = endLine,
                    documentation = documentation
                )
            )
        }

        interfaceRegex.findAll(content).forEach { match ->
            val name = match.groupValues[1]
            val body = match.groupValues[2]
            val startLine = lineNumberAt(content, match.range.first)
            val endLine = lineNumberAt(content, match.range.last)
            val members = parseInterfaceMembers(body)
            val documentation = extractJsdocBefore(lines, startLine)
            classes.add(
                ClassElement(
                    name = name,
                    type = "interface",
                    startLine = startLine,
                    endLine = endLine,
                    methods = members.first,
                    fields = members.second,
                    documentation = documentation,
                    purpose = "TypeScript Interface / Contract"
                )
            )
        }

        typeAliasRegex.findAll(content).forEach { match ->
            val name = match.groupValues[1]
            val definition = match.groupValues[2].trim()
            val startLine = lineNumberAt(content, match.range.first)
            val endLine = lineNumberAt(content, match.range.last)
            val documentation = extractJsdocBefore(lines, startLine)
            classes.add(
                ClassElement(
                    name = name,
                    type = "type_alias",
                    startLine = startLine,
                    endLine = endLine,
                    documentation = documentation ?: "Type: $definition",
                    purpose = inferTypePurpose(name, definition)
                )
            )
        }

        functionWithTypesRegex.findAll(content).forEach { match ->
            val name = match.groupValues[1]
            val paramsStr = match.groupValues[2]
            val returnType = match.groupValues[3].trim().ifBlank { null }
            val startLine = lineNumberAt(content, match.range.first)
            val endLine = findBlockEndLine(lines, startLine)
            val parameters = parseTypeScriptParameters(paramsStr)
            val signature = buildTypeScriptSignature(name, parameters, returnType, isArrow = false)
            val documentation = extractJsdocBefore(lines, startLine)
            val complexity = estimateTypeScriptComplexity(lines, startLine, endLine)
            val callsTo = extractTypeScriptCalls(lines, startLine, endLine, name)
            functions.add(
                FunctionElement(
                    name = name,
                    startLine = startLine,
                    endLine = endLine,
                    signature = signature,
                    returnType = returnType,
                    parameters = parameters,
                    documentation = documentation,
                    complexity = complexity,
                    isPublicApi = content.substring(match.range.first, match.range.last).contains("export"),
                    callsTo = callsTo
                )
            )
        }

        arrowFunctionWithTypesRegex.findAll(content).forEach { match ->
            val name = match.groupValues[1]
            val paramsStr = match.groupValues[2]
            val returnType = match.groupValues[3].trim().ifBlank { null }
            val startLine = lineNumberAt(content, match.range.first)
            val endLine = findBlockEndLine(lines, startLine)
            val parameters = parseTypeScriptParameters(paramsStr)
            val signature = buildTypeScriptSignature(name, parameters, returnType, isArrow = true)
            val documentation = extractJsdocBefore(lines, startLine)
            val complexity = estimateTypeScriptComplexity(lines, startLine, endLine)
            val callsTo = extractTypeScriptCalls(lines, startLine, endLine, name)
            functions.add(
                FunctionElement(
                    name = name,
                    startLine = startLine,
                    endLine = endLine,
                    signature = signature,
                    returnType = returnType,
                    parameters = parameters,
                    documentation = documentation,
                    complexity = complexity,
                    isPublicApi = content.substring(match.range.first, match.range.last).contains("export"),
                    callsTo = callsTo
                )
            )
        }

        val reactComponents = detectReactComponents(content, functions)
        if (reactComponents.isNotEmpty()) frameworks.add("React")

        val reactHooks = detectReactHooks(functions)
        if (reactHooks.isNotEmpty() && !frameworks.contains("React")) frameworks.add("React")

        reactComponents.forEach { name ->
            exports.add(ExportElement(name = name, type = "component"))
        }

        val imports = importRegex.findAll(content).map {
            ImportElement(module = it.groupValues[1])
        }.toList()

        exportRegex.findAll(content).forEach { match ->
            val type = match.groupValues[1].ifBlank { "default" }
            val name = match.groupValues[2].ifBlank { "default" }
            exports.add(ExportElement(name = name, type = type))
        }

        return CodeElements(
            classes = classes,
            functions = functions,
            imports = imports,
            exports = exports,
            frameworks = frameworks.distinct()
        )
    }

    private fun parseInterfaceMembers(body: String): Pair<List<FunctionElement>, List<FieldElement>> {
        val methods = mutableListOf<FunctionElement>()
        val fields = mutableListOf<FieldElement>()
        val lines = body.lines().map { it.trim() }.filter { it.isNotBlank() }
        lines.forEachIndexed { index, line ->
            if (line.contains("(") && line.contains("):")) {
                val name = line.substringBefore("(").trim()
                val paramsStr = line.substringAfter("(").substringBefore(")")
                val returnType = line.substringAfter("):").substringBefore(";").trim().ifBlank { null }
                val parameters = parseTypeScriptParameters(paramsStr)
                methods.add(
                    FunctionElement(
                        name = name,
                        startLine = index + 1,
                        endLine = index + 1,
                        signature = buildTypeScriptSignature(name, parameters, returnType, isArrow = false),
                        returnType = returnType,
                        parameters = parameters,
                        isPublicApi = true
                    )
                )
            } else if (line.contains(":")) {
                val name = line.substringBefore(":").trim()
                val type = line.substringAfter(":").substringBefore(";").trim().ifBlank { null }
                fields.add(FieldElement(name = name, type = type))
            }
        }
        return methods to fields
    }

    private fun parseTypeScriptParameters(paramsStr: String): List<ParameterElement> {
        if (paramsStr.isBlank()) return emptyList()
        return splitTypeScriptParameters(paramsStr).mapNotNull { raw ->
            val trimmed = raw.trim()
            if (trimmed.isBlank()) return@mapNotNull null
            val name = trimmed.substringBefore(":").trim()
            val type = trimmed.substringAfter(":", "").substringBefore("=").trim().ifBlank { null }
            val defaultValue = trimmed.substringAfter("=", "").trim().ifBlank { null }
            ParameterElement(name = name, type = type, defaultValue = defaultValue)
        }
    }

    private fun splitTypeScriptParameters(paramsStr: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        paramsStr.forEach { ch ->
            when (ch) {
                '<', '(', '[', '{' -> {
                    depth++
                    current.append(ch)
                }
                '>', ')', ']', '}' -> {
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

    private fun buildTypeScriptSignature(
        name: String,
        parameters: List<ParameterElement>,
        returnType: String?,
        isArrow: Boolean
    ): String {
        val paramsStr = parameters.joinToString(", ") { param ->
            val typed = if (param.type != null) "${param.name}: ${param.type}" else param.name
            if (param.defaultValue != null) "$typed = ${param.defaultValue}" else typed
        }
        val returnStr = if (returnType != null) ": $returnType" else ""
        return if (isArrow) {
            "const $name = ($paramsStr)$returnStr =>"
        } else {
            "function $name($paramsStr)$returnStr"
        }
    }

    private fun extractJsdocBefore(lines: List<String>, startLine: Int): String? {
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

    private fun estimateTypeScriptComplexity(lines: List<String>, startLine: Int, endLine: Int): Int {
        val keywords = listOf("if", "for", "while", "switch", "case", "catch", "&&", "||", "?")
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

    private fun extractTypeScriptCalls(lines: List<String>, startLine: Int, endLine: Int, functionName: String): List<String> {
        val body = lines.subList((startLine - 1).coerceAtLeast(0), endLine.coerceAtMost(lines.size)).joinToString("\n")
        val callRegex = Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        val ignored = setOf("if", "for", "while", "return", "switch", "catch", "throw", "new")
        return callRegex.findAll(body)
            .map { it.groupValues[1] }
            .filter { it != functionName && it !in ignored }
            .distinct()
            .take(15)
            .toList()
    }

    private fun inferTypePurpose(name: String, definition: String): String? {
        return when {
            name.endsWith("Props") -> "Component Props"
            definition.contains("React.FC") -> "React Component Type"
            else -> null
        }
    }

    private fun detectReactComponents(content: String, functions: List<FunctionElement>): List<String> {
        val components = mutableListOf<String>()
        functions.filter { it.name.isNotEmpty() && it.name[0].isUpperCase() }.forEach { fn ->
            val body = extractFunctionBody(content, fn.startLine, fn.endLine)
            if (body.contains("<") && (body.contains("return") || body.contains("=>"))) {
                components.add(fn.name)
            }
        }
        return components
    }

    private fun detectReactHooks(functions: List<FunctionElement>): List<String> {
        return functions.filter { it.name.startsWith("use") && it.name.length > 3 }
            .map { it.name }
    }

    private fun extractFunctionBody(content: String, startLine: Int, endLine: Int): String {
        val lines = content.lines()
        if (startLine <= 0 || endLine <= 0) return ""
        return lines.subList((startLine - 1).coerceAtLeast(0), endLine.coerceAtMost(lines.size)).joinToString("\n")
    }
}
