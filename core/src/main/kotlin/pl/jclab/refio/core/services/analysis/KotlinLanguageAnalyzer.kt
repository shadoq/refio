package pl.jclab.refio.core.services.analysis

import java.nio.file.Path

class KotlinLanguageAnalyzer : ExtensionLanguageAnalyzer(
    languageId = "kotlin",
    extensions = setOf(".kt", ".kts")
) {

    // Class/interface/object regexes (applied to joined content for multiline support)
    private val classRegex = Regex(
        """(?:^|\n)\s*((?:(?:data|sealed|abstract|open|inner|internal|private|protected|public|actual|expect|value|inline)\s+)*)class\s+([A-Za-z0-9_]+)(?:<[^>]*>)?\s*(?:\([^)]*\))?\s*(?::\s*([^\{]+))?""",
    )
    private val interfaceRegex = Regex(
        """(?:^|\n)\s*((?:(?:sealed|internal|private|protected|public|fun|actual|expect)\s+)*)interface\s+([A-Za-z0-9_]+)(?:<[^>]*>)?\s*(?::\s*([^\{]+))?"""
    )
    private val objectRegex = Regex(
        """(?:^|\n)\s*(?:(?:internal|private|public)\s+)?object\s+([A-Za-z0-9_]+)\s*(?::\s*([^\{]+))?"""
    )
    private val companionObjectRegex = Regex(
        """companion\s+object\s*(?:([A-Za-z0-9_]+)\s*)?"""
    )
    private val enumEntryRegex = Regex(
        """^\s*([A-Z][A-Z0-9_]*)\s*(?:\([^)]*\))?\s*[,;]?\s*$"""
    )
    // Function regex: supports suspend, visibility, override, extension receivers, generics
    private val functionRegex = Regex(
        """(?:^|\n)\s*(?:(?:override|public|internal|private|protected|actual|expect|inline|infix|operator|tailrec)\s+)*(suspend\s+)?fun\s+(?:<[^>]*>\s+)?((?:[A-Za-z0-9_<>?.*]+)\.)?([A-Za-z0-9_]+)\s*\(([^)]*)\)\s*(?::\s*([^\{=\n]+))?"""
    )
    // Property regex: val/var with optional type and initializer
    private val propertyRegex = Regex(
        """(?:^|\n)\s*(?:(?:override|public|internal|private|protected|lateinit|const|actual|expect|abstract|open)\s+)*(val|var)\s+(?:<[^>]*>\s+)?(?:([A-Za-z0-9_<>?.*]+)\.)?([A-Za-z0-9_]+)\s*(?::\s*([^\n=]+?))?(?:\s*=\s*([^\n]+?))?(?:\s*$)"""
    )
    private val importRegex = Regex("""import\s+([\w.*]+)(?:\s+as\s+(\w+))?""")
    private val typealiasRegex = Regex("""typealias\s+([A-Za-z0-9_]+)(?:<[^>]*>)?\s*=\s*([^\n]+)""")

    override fun analyze(filePath: Path, content: String): CodeElements {
        val lines = content.lines()
        val joined = joinMultilineDeclarations(content)
        val classes = mutableListOf<ClassElement>()
        val functions = mutableListOf<FunctionElement>()
        val annotations = mutableSetOf<String>()

        // Classes
        classRegex.findAll(joined).forEach { match ->
            val modifierStr = match.groupValues[1].trim()
            val modifiers = modifierStr.split(Regex("\\s+")).filter { it.isNotBlank() }
            val name = match.groupValues[2]
            val inheritanceRaw = match.groupValues[3].trim()
            val inheritance = splitInheritance(inheritanceRaw)
            val startLine = lineNumberAt(content, findOriginalIndex(content, name, "class"))
            val endLine = findBlockEndLine(lines, startLine)
            val classAnnotations = annotationsAbove(lines, startLine)
            annotations.addAll(classAnnotations)
            val documentation = extractKDocBefore(lines, startLine)
            val purpose = inferKotlinClassPurpose(name, classAnnotations, modifiers)
            val patterns = detectKotlinClassPatterns(name, modifiers, classAnnotations)

            // Extract fields (properties) inside class body
            val classFields = extractClassProperties(lines, startLine, endLine)

            // Extract methods inside class body
            val classMethods = extractClassMethods(lines, startLine, endLine, joined)

            // For enum classes, extract entries
            val enumFields = if ("enum" in modifiers) extractEnumEntries(lines, startLine, endLine) else emptyList()

            classes.add(
                ClassElement(
                    name = name,
                    type = when {
                        "data" in modifiers -> "data_class"
                        "sealed" in modifiers -> "sealed_class"
                        "enum" in modifiers -> "enum_class"
                        "value" in modifiers || "inline" in modifiers -> "value_class"
                        "abstract" in modifiers -> "abstract_class"
                        else -> "class"
                    },
                    startLine = startLine,
                    endLine = endLine,
                    modifiers = modifiers,
                    superclass = inheritance.firstOrNull(),
                    interfaces = inheritance.drop(1),
                    annotations = classAnnotations,
                    documentation = documentation,
                    methods = classMethods,
                    fields = classFields + enumFields,
                    purpose = purpose,
                    patterns = patterns
                )
            )
        }

        // Interfaces
        interfaceRegex.findAll(joined).forEach { match ->
            val modifierStr = match.groupValues[1].trim()
            val modifiers = modifierStr.split(Regex("\\s+")).filter { it.isNotBlank() }
            val name = match.groupValues[2]
            val inheritance = splitInheritance(match.groupValues[3].trim())
            val startLine = lineNumberAt(content, findOriginalIndex(content, name, "interface"))
            val endLine = findBlockEndLine(lines, startLine)
            val iAnnotations = annotationsAbove(lines, startLine)
            annotations.addAll(iAnnotations)
            val documentation = extractKDocBefore(lines, startLine)
            val classMethods = extractClassMethods(lines, startLine, endLine, joined)

            classes.add(
                ClassElement(
                    name = name,
                    type = if ("sealed" in modifiers) "sealed_interface" else "interface",
                    startLine = startLine,
                    endLine = endLine,
                    modifiers = modifiers,
                    superclass = inheritance.firstOrNull(),
                    interfaces = inheritance.drop(1),
                    annotations = iAnnotations,
                    documentation = documentation,
                    methods = classMethods,
                    purpose = inferInterfacePurpose(name, iAnnotations)
                )
            )
        }

        // Objects (excluding companion)
        objectRegex.findAll(joined).forEach { match ->
            val fullMatch = match.value
            if (fullMatch.contains("companion")) return@forEach
            val name = match.groupValues[1]
            val inheritance = splitInheritance(match.groupValues[2].trim())
            val startLine = lineNumberAt(content, findOriginalIndex(content, name, "object"))
            val endLine = findBlockEndLine(lines, startLine)
            val oAnnotations = annotationsAbove(lines, startLine)
            annotations.addAll(oAnnotations)
            val documentation = extractKDocBefore(lines, startLine)

            classes.add(
                ClassElement(
                    name = name,
                    type = "object",
                    startLine = startLine,
                    endLine = endLine,
                    superclass = inheritance.firstOrNull(),
                    interfaces = inheritance.drop(1),
                    annotations = oAnnotations,
                    documentation = documentation,
                    patterns = listOf("Singleton")
                )
            )
        }

        // All functions (including class methods for backward compatibility)
        functionRegex.findAll(joined).forEach { match ->
            val isSuspend = match.groupValues[1].isNotBlank()
            val receiver = match.groupValues[2].trim().removeSuffix(".").ifBlank { null }
            val name = match.groupValues[3]
            val paramsStr = match.groupValues[4]
            val returnType = match.groupValues[5].trim().ifBlank { null }
            val startLine = lineNumberAt(content, findOriginalIndex(content, name, "fun"))
            val endLine = findBlockEndLine(lines, startLine)
            val fnAnnotations = annotationsAbove(lines, startLine)
            annotations.addAll(fnAnnotations)
            val parameters = parseKotlinParameters(paramsStr)
            val modifiers = mutableListOf<String>()
            if (isSuspend) modifiers.add("suspend")
            if (receiver != null) modifiers.add("extension")
            val signature = buildKotlinSignature(isSuspend, receiver, name, parameters, returnType)
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
                    modifiers = modifiers,
                    annotations = fnAnnotations,
                    documentation = documentation,
                    complexity = complexity,
                    isPublicApi = fnAnnotations.none { it == "private" || it == "internal" } && !name.startsWith("_"),
                    callsTo = callsTo
                )
            )
        }

        // Top-level properties
        val topLevelProperties = mutableListOf<FieldElement>()
        propertyRegex.findAll(joined).forEach { match ->
            val valOrVar = match.groupValues[1]
            val receiver = match.groupValues[2].trim().ifBlank { null }
            val name = match.groupValues[3]
            val type = match.groupValues[4].trim().ifBlank { null }
            val initializer = match.groupValues[5].trim().ifBlank { null }
            val startLine = lineNumberAt(content, findOriginalIndex(content, name, valOrVar))
            if (isInsideClassBody(classes, startLine)) return@forEach
            val propAnnotations = annotationsAbove(lines, startLine)
            topLevelProperties.add(
                FieldElement(
                    name = if (receiver != null) "$receiver.$name" else name,
                    type = type,
                    modifiers = listOf(valOrVar) + if (receiver != null) listOf("extension") else emptyList(),
                    initializer = initializer?.take(80),
                    annotations = propAnnotations
                )
            )
        }

        // Type aliases
        typealiasRegex.findAll(content).forEach { match ->
            val name = match.groupValues[1]
            val target = match.groupValues[2].trim()
            topLevelProperties.add(
                FieldElement(name = name, type = target, modifiers = listOf("typealias"))
            )
        }

        // Collect all annotations
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

        val frameworks = detectKotlinFrameworks(content, imports)

        // If we have top-level properties or typealiases, wrap them in a synthetic file-level class
        if (topLevelProperties.isNotEmpty()) {
            val fileName = filePath.fileName.toString().removeSuffix(".kt").removeSuffix(".kts")
            classes.add(
                ClassElement(
                    name = "${fileName}Kt",
                    type = "file",
                    startLine = 1,
                    endLine = lines.size,
                    fields = topLevelProperties,
                    purpose = "Top-level declarations"
                )
            )
        }

        return CodeElements(
            classes = classes,
            functions = functions,
            imports = imports,
            annotations = annotations.toList(),
            frameworks = frameworks
        )
    }

    private fun findOriginalIndex(content: String, name: String, keyword: String): Int {
        // Find the keyword+name combination in original content
        val pattern = Regex("""\b$keyword\s+.*?\b${Regex.escape(name)}\b""")
        return pattern.find(content)?.range?.first ?: content.indexOf(name).coerceAtLeast(0)
    }

    private fun isInsideClassBody(classes: List<ClassElement>, line: Int): Boolean {
        return classes.any { line > it.startLine && line < it.endLine }
    }

    private fun splitInheritance(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        raw.forEach { ch ->
            when {
                ch == '<' || ch == '(' -> { depth++; current.append(ch) }
                ch == '>' || ch == ')' -> { depth--; current.append(ch) }
                ch == ',' && depth == 0 -> { parts.add(current.toString().trim()); current.setLength(0) }
                else -> current.append(ch)
            }
        }
        if (current.isNotBlank()) parts.add(current.toString().trim())
        return parts.filter { it.isNotBlank() }
    }

    private fun extractClassProperties(lines: List<String>, startLine: Int, endLine: Int): List<FieldElement> {
        val fields = mutableListOf<FieldElement>()
        val propPattern = Regex(
            """^\s*(?:(?:override|public|internal|private|protected|lateinit|const|abstract|open)\s+)*(val|var)\s+([A-Za-z0-9_]+)\s*(?::\s*([^\n=]+?))?(?:\s*=\s*(.+?))?$"""
        )
        for (i in startLine until (endLine - 1).coerceAtMost(lines.size)) {
            val line = lines[i]
            val match = propPattern.find(line) ?: continue
            val valOrVar = match.groupValues[1]
            val name = match.groupValues[2]
            val type = match.groupValues[3].trim().ifBlank { null }
            val init = match.groupValues[4].trim().ifBlank { null }
            val propAnnotations = annotationsAbove(lines, i + 1)
            fields.add(
                FieldElement(
                    name = name,
                    type = type,
                    modifiers = listOf(valOrVar),
                    initializer = init?.take(80),
                    annotations = propAnnotations
                )
            )
        }
        return fields
    }

    private fun extractClassMethods(lines: List<String>, startLine: Int, endLine: Int, joinedContent: String): List<FunctionElement> {
        val methods = mutableListOf<FunctionElement>()
        val bodyContent = lines.subList((startLine).coerceAtLeast(0), (endLine - 1).coerceAtMost(lines.size)).joinToString("\n")
        val joinedBody = joinMultilineDeclarations(bodyContent)
        functionRegex.findAll(joinedBody).forEach { match ->
            val isSuspend = match.groupValues[1].isNotBlank()
            val name = match.groupValues[3]
            val paramsStr = match.groupValues[4]
            val returnType = match.groupValues[5].trim().ifBlank { null }
            val parameters = parseKotlinParameters(paramsStr)
            val modifiers = if (isSuspend) listOf("suspend") else emptyList()
            val receiver = match.groupValues[2].trim().removeSuffix(".").ifBlank { null }
            methods.add(
                FunctionElement(
                    name = name,
                    startLine = startLine,
                    endLine = endLine,
                    signature = buildKotlinSignature(isSuspend, receiver, name, parameters, returnType),
                    returnType = returnType,
                    parameters = parameters,
                    modifiers = modifiers,
                    isPublicApi = !name.startsWith("_")
                )
            )
        }
        return methods
    }

    private fun extractEnumEntries(lines: List<String>, startLine: Int, endLine: Int): List<FieldElement> {
        val entries = mutableListOf<FieldElement>()
        var insideBody = false
        for (i in (startLine - 1) until endLine.coerceAtMost(lines.size)) {
            val line = lines[i].trim()
            if (line.contains('{')) insideBody = true
            if (!insideBody) continue
            // Stop at first method/property or semicolon-only line
            if (line.startsWith("fun ") || line.startsWith("val ") || line.startsWith("var ") ||
                line.startsWith("override ") || line.startsWith("abstract ") || line == ";") break
            val entryMatch = enumEntryRegex.find(line)
            if (entryMatch != null) {
                entries.add(FieldElement(name = entryMatch.groupValues[1], modifiers = listOf("enum_entry")))
            }
        }
        return entries
    }

    private fun extractKDocBefore(lines: List<String>, startLine: Int): String? {
        var i = startLine - 2
        while (i >= 0) {
            val line = lines[i].trim()
            if (line.isEmpty()) { i--; continue }
            if (line.startsWith("@")) { i--; continue }
            if (line.endsWith("*/")) {
                val buffer = StringBuilder()
                var j = i
                while (j >= 0) {
                    val current = lines[j].trim()
                    buffer.insert(0, current + "\n")
                    if (current.startsWith("/**")) {
                        return buffer.toString()
                            .replace("/**", "").replace("*/", "")
                            .lines().map { it.trimStart('*', ' ').trim() }
                            .joinToString(" ").trim().ifBlank { null }
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
        return splitBalanced(paramsStr, ',').mapNotNull { raw ->
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

    private fun splitBalanced(input: String, delimiter: Char): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        input.forEach { ch ->
            when {
                ch in "<([" -> { depth++; current.append(ch) }
                ch in ">)]" -> { depth = (depth - 1).coerceAtLeast(0); current.append(ch) }
                ch == delimiter && depth == 0 -> { parts.add(current.toString()); current.setLength(0) }
                else -> current.append(ch)
            }
        }
        if (current.isNotBlank()) parts.add(current.toString())
        return parts
    }

    private fun buildKotlinSignature(
        isSuspend: Boolean, receiver: String?, name: String,
        parameters: List<ParameterElement>, returnType: String?
    ): String {
        val prefix = if (isSuspend) "suspend " else ""
        val receiverStr = receiver?.let { "$it." } ?: ""
        val paramsStr = parameters.joinToString(", ") { p ->
            val typed = if (p.type != null) "${p.name}: ${p.type}" else p.name
            if (p.defaultValue != null) "$typed = ${p.defaultValue}" else typed
        }
        val returnStr = if (returnType != null) ": $returnType" else ""
        return "${prefix}fun ${receiverStr}${name}($paramsStr)$returnStr".trim()
    }

    private fun estimateKotlinComplexity(lines: List<String>, startLine: Int, endLine: Int): Int {
        val keywords = listOf("if", "for", "while", "when", "catch", "&&", "||", "?:")
        val body = lines.subList((startLine - 1).coerceAtLeast(0), endLine.coerceAtMost(lines.size)).joinToString("\n")
        val hits = keywords.sumOf { kw ->
            if (kw.all { it.isLetter() }) Regex("""\b$kw\b""").findAll(body).count()
            else body.windowed(kw.length).count { it == kw }
        }
        return 1 + hits
    }

    private fun extractKotlinCalls(lines: List<String>, startLine: Int, endLine: Int, functionName: String): List<String> {
        val body = lines.subList((startLine - 1).coerceAtLeast(0), endLine.coerceAtMost(lines.size)).joinToString("\n")
        val callRegex = Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        val ignored = setOf("if", "for", "while", "return", "when", "catch", "throw", "super", "this", "listOf", "mapOf", "setOf", "mutableListOf", "mutableMapOf")
        return callRegex.findAll(body)
            .map { it.groupValues[1] }
            .filter { it != functionName && it !in ignored }
            .distinct().take(15).toList()
    }

    private fun inferKotlinClassPurpose(name: String, annotations: List<String>, modifiers: List<String>): String? {
        return when {
            annotations.any { it.contains("Controller") || it.contains("RestController") } -> "REST API Controller"
            annotations.any { it.contains("Service") } -> "Business Logic Service"
            annotations.any { it.contains("Repository") } -> "Data Access Repository"
            annotations.any { it.contains("Entity") || it.contains("Table") } -> "Database Entity"
            annotations.any { it.contains("Configuration") } -> "Spring Configuration"
            annotations.any { it.contains("Component") } -> "Spring Component"
            "data" in modifiers && (name.endsWith("DTO") || name.endsWith("Request") || name.endsWith("Response")) -> "Data Transfer Object"
            "data" in modifiers -> "Data Class"
            "sealed" in modifiers -> "Sealed Type Hierarchy"
            "enum" in modifiers -> "Enum Type"
            name.endsWith("Factory") -> "Factory Pattern"
            name.endsWith("Builder") -> "Builder Pattern"
            name.endsWith("Adapter") -> "Adapter Pattern"
            name.endsWith("Repository") -> "Repository"
            name.endsWith("Service") -> "Service"
            name.endsWith("Controller") -> "Controller"
            name.endsWith("Handler") -> "Event/Request Handler"
            name.endsWith("Listener") -> "Event Listener"
            name.endsWith("Provider") -> "Provider"
            name.endsWith("Mapper") -> "Object Mapper"
            name.endsWith("Test") || name.endsWith("Tests") || name.endsWith("Spec") -> "Test Class"
            else -> null
        }
    }

    private fun inferInterfacePurpose(name: String, annotations: List<String>): String? {
        return when {
            annotations.any { it.contains("FunctionalInterface") } -> "Functional Interface (SAM)"
            name.endsWith("Repository") -> "Repository Contract"
            name.endsWith("Service") -> "Service Contract"
            name.endsWith("Factory") -> "Factory Contract"
            name.endsWith("Listener") -> "Event Listener Contract"
            name.endsWith("Handler") -> "Handler Contract"
            name.endsWith("Callback") -> "Callback Interface"
            name.endsWith("Strategy") -> "Strategy Pattern Interface"
            else -> "Interface Contract"
        }
    }

    private fun detectKotlinClassPatterns(name: String, modifiers: List<String>, annotations: List<String>): List<String> {
        val patterns = mutableListOf<String>()
        if ("data" in modifiers) patterns.add("DataClass")
        if ("sealed" in modifiers) patterns.add("Sealed")
        if ("value" in modifiers || "inline" in modifiers) patterns.add("ValueClass")
        if ("enum" in modifiers) patterns.add("Enum")
        if (annotations.any { it == "Singleton" || it == "Inject" }) patterns.add("Singleton")
        if (name.endsWith("Factory")) patterns.add("Factory")
        if (name.endsWith("Builder")) patterns.add("Builder")
        if (name.endsWith("Adapter")) patterns.add("Adapter")
        if (name.endsWith("Decorator")) patterns.add("Decorator")
        if (name.endsWith("Observer") || name.endsWith("Listener")) patterns.add("Observer")
        if (name.endsWith("Strategy")) patterns.add("Strategy")
        return patterns
    }

    private fun detectKotlinFrameworks(content: String, imports: List<ImportElement>): List<String> {
        val frameworks = mutableListOf<String>()
        // Coroutines
        val coroutineMarkers = mutableListOf<String>()
        if ("launch(" in content || "launch {" in content) coroutineMarkers.add("launch")
        if ("async(" in content || "async {" in content) coroutineMarkers.add("async")
        if ("Flow<" in content) coroutineMarkers.add("Flow")
        if ("StateFlow<" in content || "MutableStateFlow" in content) coroutineMarkers.add("StateFlow")
        if ("withContext(" in content) coroutineMarkers.add("withContext")
        if ("runBlocking" in content) coroutineMarkers.add("runBlocking")
        if (coroutineMarkers.isNotEmpty()) frameworks.add("Coroutines: ${coroutineMarkers.joinToString(", ")}")

        // Spring
        if ("@Controller" in content || "@RestController" in content) frameworks.add("Spring MVC")
        if ("@Service" in content) frameworks.add("Spring Service")
        if ("@Repository" in content) frameworks.add("Spring Repository")
        if ("@Configuration" in content) frameworks.add("Spring Configuration")

        // Ktor
        if (imports.any { it.module.startsWith("io.ktor") }) frameworks.add("Ktor")

        // Exposed ORM
        if (imports.any { it.module.startsWith("org.jetbrains.exposed") }) frameworks.add("Exposed ORM")

        // Testing
        if (imports.any { it.module.startsWith("org.junit") }) frameworks.add("JUnit")
        if (imports.any { it.module.startsWith("io.mockk") }) frameworks.add("MockK")
        if (imports.any { it.module.startsWith("app.cash.turbine") }) frameworks.add("Turbine")

        // Serialization
        if (imports.any { it.module.contains("kotlinx.serialization") }) frameworks.add("Kotlin Serialization")
        if (imports.any { it.module.contains("com.google.gson") }) frameworks.add("Gson")

        return frameworks.distinct()
    }
}
