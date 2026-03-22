package pl.jclab.refio.core.services.analysis

import java.nio.file.Path

class CppLanguageAnalyzer : ExtensionLanguageAnalyzer(
    languageId = "cpp",
    extensions = setOf(".c", ".cpp", ".cc", ".cxx", ".h", ".hpp", ".hh")
) {

    private val classRegex = Regex(
        """(template\s*<[^>]*>\s*)?class\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::\s*((?:(?:public|protected|private)\s+)?[A-Za-z0-9_:<>,\s]+))?"""
    )
    private val structRegex = Regex(
        """(template\s*<[^>]*>\s*)?struct\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?::\s*((?:(?:public|protected|private)\s+)?[A-Za-z0-9_:<>,\s]+))?"""
    )
    private val functionRegex = Regex(
        """(?:^|\n)\s*((?:static|inline|virtual|explicit|constexpr|extern|friend)\s+)*(?:([A-Za-z_][A-Za-z0-9_:*&<>,\s]*?)\s+)?([A-Za-z_][A-Za-z0-9_]*(?:::[A-Za-z_][A-Za-z0-9_]*)*)\s*\(([^)]*)\)\s*(?:const\s*)?(?:noexcept\s*)?(?:override\s*)?(?:=\s*(?:0|default|delete)\s*)?(?=[{;])"""
    )
    private val includeRegex = Regex("""#include\s+(?:[<"]([^>"]+)[>"])""")
    private val defineRegex = Regex("""#define\s+([A-Za-z_][A-Za-z0-9_]*)(?:\(([^)]*)\))?\s*(.*)""")
    private val namespaceRegex = Regex("""namespace\s+([A-Za-z_][A-Za-z0-9_:]*)\s*\{""")
    private val enumRegex = Regex(
        """enum\s+(class\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*(?::\s*([A-Za-z_][A-Za-z0-9_]*))?\s*\{"""
    )
    private val typedefRegex = Regex("""typedef\s+(.+?)\s+([A-Za-z_][A-Za-z0-9_]*)\s*;""")
    private val usingAliasRegex = Regex("""using\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.+?)\s*;""")

    // Names that are not functions
    private val notFunctions = setOf(
        "if", "for", "while", "switch", "return", "catch", "throw",
        "else", "do", "try", "delete", "new", "sizeof", "alignof",
        "decltype", "typeid", "static_assert", "namespace", "class",
        "struct", "enum", "union", "typedef", "using", "template"
    )

    override fun analyze(filePath: Path, content: String): CodeElements {
        val lines = content.lines()
        val classes = mutableListOf<ClassElement>()
        val functions = mutableListOf<FunctionElement>()
        val frameworks = mutableListOf<String>()

        // Extract classes
        classRegex.findAll(content).forEach { match ->
            val templatePrefix = match.groupValues[1].trim()
            val name = match.groupValues[2]
            val inheritance = parseInheritance(match.groupValues[3])
            val startLine = lineNumberAt(content, match.range.first)
            val endLine = findBlockEndLine(lines, startLine)
            val documentation = extractCppDocBefore(lines, startLine)
            val purpose = inferCppClassPurpose(name)
            val patterns = detectCppClassPatterns(name)
            val modifiers = if (templatePrefix.isNotEmpty()) listOf("template") else emptyList()

            classes.add(
                ClassElement(
                    name = name,
                    type = "class",
                    startLine = startLine,
                    endLine = endLine,
                    modifiers = modifiers,
                    superclass = inheritance.firstOrNull(),
                    interfaces = inheritance.drop(1),
                    documentation = documentation,
                    purpose = purpose,
                    patterns = patterns
                )
            )
        }

        // Extract structs
        structRegex.findAll(content).forEach { match ->
            val templatePrefix = match.groupValues[1].trim()
            val name = match.groupValues[2]
            val inheritance = parseInheritance(match.groupValues[3])
            val startLine = lineNumberAt(content, match.range.first)
            val endLine = findBlockEndLine(lines, startLine)
            val documentation = extractCppDocBefore(lines, startLine)
            val modifiers = if (templatePrefix.isNotEmpty()) listOf("template") else emptyList()

            classes.add(
                ClassElement(
                    name = name,
                    type = "struct",
                    startLine = startLine,
                    endLine = endLine,
                    modifiers = modifiers,
                    superclass = inheritance.firstOrNull(),
                    interfaces = inheritance.drop(1),
                    documentation = documentation,
                    purpose = inferCppClassPurpose(name),
                    patterns = detectCppClassPatterns(name)
                )
            )
        }

        // Extract enums
        enumRegex.findAll(content).forEach { match ->
            val isEnumClass = match.groupValues[1].isNotBlank()
            val name = match.groupValues[2]
            val startLine = lineNumberAt(content, match.range.first)
            val endLine = findBlockEndLine(lines, startLine)
            val documentation = extractCppDocBefore(lines, startLine)

            classes.add(
                ClassElement(
                    name = name,
                    type = if (isEnumClass) "enum_class" else "enum",
                    startLine = startLine,
                    endLine = endLine,
                    documentation = documentation
                )
            )
        }

        // Extract functions
        functionRegex.findAll(content).forEach { match ->
            val modifierStr = match.groupValues[1].trim()
            val returnType = match.groupValues[2].trim().ifBlank { null }
            val name = match.groupValues[3]
            val paramsStr = match.groupValues[4]

            // Skip non-function keywords
            val baseName = name.substringAfterLast("::")
            if (baseName in notFunctions) return@forEach

            val startLine = lineNumberAt(content, match.range.first)
            val endLine = findBlockEndLine(lines, startLine)
            val documentation = extractCppDocBefore(lines, startLine)
            val parameters = parseCppParameters(paramsStr)
            val modifiers = modifierStr.split(Regex("""\s+""")).filter { it.isNotBlank() }
            val signature = buildCppSignature(modifiers, returnType, name, parameters)
            val complexity = estimateCppComplexity(lines, startLine, endLine)
            val callsTo = extractCppCalls(lines, startLine, endLine, baseName)

            functions.add(
                FunctionElement(
                    name = name,
                    startLine = startLine,
                    endLine = endLine,
                    signature = signature,
                    returnType = returnType,
                    parameters = parameters,
                    modifiers = modifiers,
                    documentation = documentation,
                    complexity = complexity,
                    isPublicApi = !modifiers.contains("static") && !name.startsWith("_"),
                    callsTo = callsTo
                )
            )
        }

        // Extract includes as imports
        val imports = includeRegex.findAll(content).map {
            ImportElement(
                module = it.groupValues[1],
                alias = null,
                isWildcard = false
            )
        }.toList()

        // Extract #define macros as fields on a synthetic "Macros" class element
        val macros = mutableListOf<FieldElement>()
        defineRegex.findAll(content).forEach { match ->
            val name = match.groupValues[1]
            val params = match.groupValues[2].ifBlank { null }
            val body = match.groupValues[3].trim().ifBlank { null }
            macros.add(
                FieldElement(
                    name = name,
                    type = if (params != null) "macro($params)" else "macro",
                    modifiers = listOf("define"),
                    initializer = body
                )
            )
        }
        if (macros.isNotEmpty()) {
            classes.add(
                ClassElement(
                    name = "__macros__",
                    type = "macros",
                    startLine = 0,
                    endLine = 0,
                    fields = macros
                )
            )
        }

        // Extract namespaces
        namespaceRegex.findAll(content).forEach { match ->
            val name = match.groupValues[1]
            val startLine = lineNumberAt(content, match.range.first)
            val endLine = findBlockEndLine(lines, startLine)

            classes.add(
                ClassElement(
                    name = name,
                    type = "namespace",
                    startLine = startLine,
                    endLine = endLine
                )
            )
        }

        // Extract typedefs
        typedefRegex.findAll(content).forEach { match ->
            val targetType = match.groupValues[1].trim()
            val aliasName = match.groupValues[2]
            val startLine = lineNumberAt(content, match.range.first)

            classes.add(
                ClassElement(
                    name = aliasName,
                    type = "typedef",
                    startLine = startLine,
                    endLine = startLine,
                    superclass = targetType
                )
            )
        }

        // Extract using aliases
        usingAliasRegex.findAll(content).forEach { match ->
            val aliasName = match.groupValues[1]
            val targetType = match.groupValues[2].trim()
            val startLine = lineNumberAt(content, match.range.first)

            classes.add(
                ClassElement(
                    name = aliasName,
                    type = "using_alias",
                    startLine = startLine,
                    endLine = startLine,
                    superclass = targetType
                )
            )
        }

        // Detect framework markers
        if ("#include <iostream>" in content || "#include <fstream>" in content) frameworks.add("STL-IO")
        if ("#include <vector>" in content || "#include <map>" in content || "#include <set>" in content) frameworks.add("STL-Containers")
        if ("#include <memory>" in content) frameworks.add("SmartPointers")
        if ("#include <thread>" in content || "#include <mutex>" in content) frameworks.add("Threading")
        if ("std::unique_ptr" in content || "std::shared_ptr" in content) frameworks.add("RAII")
        if ("#include <boost/" in content) frameworks.add("Boost")
        if ("Q_OBJECT" in content || "#include <Q" in content) frameworks.add("Qt")

        return CodeElements(
            classes = classes,
            functions = functions,
            imports = imports,
            frameworks = frameworks.distinct()
        )
    }

    private fun extractCppDocBefore(lines: List<String>, startLine: Int): String? {
        var i = startLine - 2
        while (i >= 0) {
            val line = lines[i].trim()
            if (line.isEmpty()) {
                i--
                continue
            }
            // Skip preprocessor directives or other declarations
            if (line.startsWith("#")) break

            if (line.endsWith("*/")) {
                val buffer = StringBuilder()
                var j = i
                while (j >= 0) {
                    val current = lines[j].trim()
                    buffer.insert(0, current + "\n")
                    if (current.startsWith("/**") || current.startsWith("/*")) {
                        return buffer.toString()
                            .replace("/**", "")
                            .replace("/*", "")
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
            // Single-line /// or // comments
            if (line.startsWith("///") || line.startsWith("//")) {
                val commentLines = mutableListOf<String>()
                var j = i
                while (j >= 0) {
                    val current = lines[j].trim()
                    if (current.startsWith("///")) {
                        commentLines.add(0, current.removePrefix("///").trim())
                    } else if (current.startsWith("//")) {
                        commentLines.add(0, current.removePrefix("//").trim())
                    } else {
                        break
                    }
                    j--
                }
                return commentLines.joinToString(" ").trim().ifBlank { null }
            }
            break
        }
        return null
    }

    private fun parseInheritance(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.split(",").map { it.trim() }
            .map { it.removePrefix("public ").removePrefix("protected ").removePrefix("private ").trim() }
            .filter { it.isNotBlank() }
    }

    private fun parseCppParameters(paramsStr: String): List<ParameterElement> {
        if (paramsStr.isBlank()) return emptyList()
        return splitParameters(paramsStr).mapNotNull { raw ->
            val trimmed = raw.trim()
            if (trimmed.isBlank() || trimmed == "void") return@mapNotNull null

            // Handle default values
            val withoutDefault = trimmed.substringBefore("=").trim()
            val defaultValue = if ("=" in trimmed) trimmed.substringAfter("=").trim() else null

            // Split type and name: last token is the name (stripping * and &)
            val tokens = withoutDefault.split(Regex("""\s+"""))
            if (tokens.size >= 2) {
                val name = tokens.last().trimStart('*', '&')
                val type = tokens.dropLast(1).joinToString(" ")
                ParameterElement(name = name, type = type, defaultValue = defaultValue)
            } else {
                // Only type, no name (forward declaration or unnamed param)
                ParameterElement(name = "", type = withoutDefault, defaultValue = defaultValue)
            }
        }
    }

    private fun splitParameters(paramsStr: String): List<String> {
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

    private fun buildCppSignature(
        modifiers: List<String>,
        returnType: String?,
        name: String,
        parameters: List<ParameterElement>
    ): String {
        val modPrefix = if (modifiers.isNotEmpty()) modifiers.joinToString(" ") + " " else ""
        val retPrefix = if (returnType != null) "$returnType " else ""
        val paramsStr = parameters.joinToString(", ") { param ->
            val typed = if (param.type != null) "${param.type} ${param.name}" else param.name
            if (param.defaultValue != null) "$typed = ${param.defaultValue}" else typed
        }
        return "$modPrefix$retPrefix$name($paramsStr)".trim()
    }

    private fun estimateCppComplexity(lines: List<String>, startLine: Int, endLine: Int): Int {
        val keywords = listOf("if", "for", "while", "switch", "goto", "catch", "&&", "||", "?")
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

    private fun extractCppCalls(lines: List<String>, startLine: Int, endLine: Int, functionName: String): List<String> {
        val body = lines.subList((startLine - 1).coerceAtLeast(0), endLine.coerceAtMost(lines.size)).joinToString("\n")
        val callRegex = Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        return callRegex.findAll(body)
            .map { it.groupValues[1] }
            .filter { it != functionName && it !in notFunctions }
            .distinct()
            .take(15)
            .toList()
    }

    private fun inferCppClassPurpose(name: String): String? {
        return when {
            name.endsWith("Exception") || name.endsWith("Error") -> "Error Type"
            name.endsWith("Iterator") -> "Iterator"
            name.endsWith("Handler") || name.endsWith("Callback") -> "Event Handler"
            name.endsWith("Manager") -> "Resource Manager"
            name.endsWith("Factory") -> "Object Factory"
            name.endsWith("Builder") -> "Object Builder"
            name.endsWith("Pool") -> "Resource Pool"
            name.endsWith("Guard") || name.endsWith("Lock") -> "RAII Guard"
            name.endsWith("Test") || name.endsWith("Tests") -> "Test Fixture"
            name.endsWith("Config") || name.endsWith("Options") || name.endsWith("Settings") -> "Configuration"
            name.endsWith("Impl") -> "Implementation Detail"
            else -> null
        }
    }

    private fun detectCppClassPatterns(name: String): List<String> {
        val patterns = mutableListOf<String>()
        if (name.endsWith("Singleton") || name == "Singleton") patterns.add("Singleton")
        if (name.endsWith("Factory")) patterns.add("Factory")
        if (name.endsWith("Builder")) patterns.add("Builder")
        if (name.endsWith("Observer") || name.endsWith("Listener")) patterns.add("Observer")
        if (name.endsWith("Adapter") || name.endsWith("Wrapper")) patterns.add("Adapter")
        if (name.endsWith("Strategy")) patterns.add("Strategy")
        if (name.endsWith("Visitor")) patterns.add("Visitor")
        return patterns
    }
}
