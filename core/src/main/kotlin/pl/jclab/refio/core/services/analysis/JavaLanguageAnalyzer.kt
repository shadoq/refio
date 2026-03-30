package pl.jclab.refio.core.services.analysis

import java.nio.file.Path

class JavaLanguageAnalyzer : ExtensionLanguageAnalyzer(
    languageId = "java",
    extensions = setOf(".java")
) {

    private val classRegex = Regex(
        """(?:^|\n)\s*((?:(?:public|protected|private|abstract|final|static|strictfp)\s+)*)class\s+([A-Za-z0-9_]+)(?:<[^>]*>)?\s*(?:extends\s+([A-Za-z0-9_<>,.\s]+))?\s*(?:implements\s+([A-Za-z0-9_<>,.\s]+))?"""
    )
    private val interfaceRegex = Regex(
        """(?:^|\n)\s*((?:(?:public|protected|private|static|strictfp)\s+)*)interface\s+([A-Za-z0-9_]+)(?:<[^>]*>)?\s*(?:extends\s+([A-Za-z0-9_<>,.\s]+))?"""
    )
    private val enumRegex = Regex(
        """(?:^|\n)\s*((?:(?:public|protected|private)\s+)*)enum\s+([A-Za-z0-9_]+)\s*(?:implements\s+([A-Za-z0-9_<>,.\s]+))?"""
    )
    private val recordRegex = Regex(
        """(?:^|\n)\s*((?:(?:public|protected|private|static|final)\s+)*)record\s+([A-Za-z0-9_]+)(?:<[^>]*>)?\s*\(([^)]*)\)\s*(?:implements\s+([A-Za-z0-9_<>,.\s]+))?"""
    )
    private val methodRegex = Regex(
        """(?:^|\n)\s*((?:(?:public|protected|private|static|final|abstract|synchronized|native|default|strictfp)\s+)*)(?:(<[^>]+>)\s+)?([A-Za-z0-9_<>\[\].?,\s]+?)\s+([A-Za-z0-9_]+)\s*\(([^)]*)\)\s*(?:throws\s+[A-Za-z0-9_,.\s]+)?\s*[{;]"""
    )
    private val constructorRegex = Regex(
        """(?:^|\n)\s*((?:(?:public|protected|private)\s+)*)([A-Z][A-Za-z0-9_]*)\s*\(([^)]*)\)\s*(?:throws\s+[A-Za-z0-9_,.\s]+)?\s*\{"""
    )
    private val fieldRegex = Regex(
        """(?:^|\n)\s*((?:(?:public|protected|private|static|final|volatile|transient)\s+)+)([A-Za-z0-9_<>\[\].?,\s]+?)\s+([A-Za-z0-9_]+)\s*(?:=\s*([^;]+))?\s*;"""
    )
    private val enumConstantRegex = Regex("""^\s*([A-Z][A-Z0-9_]*)\s*(?:\([^)]*\))?\s*[,;{]""")
    private val importRegex = Regex("""import\s+(static\s+)?([\w.*]+);""")
    private val annotationValueRegex = Regex("""@(\w+)\s*(?:\(([^)]*)\))?""")

    override fun analyze(filePath: Path, content: String): CodeElements {
        val lines = content.lines()
        val joined = joinMultilineDeclarations(content)
        val classes = mutableListOf<ClassElement>()
        val functions = mutableListOf<FunctionElement>()
        val annotations = mutableSetOf<String>()
        val frameworks = mutableListOf<String>()

        @Suppress("UNUSED_VARIABLE") // reserved for future inner-class constructor matching
        val fileClassName = filePath.fileName.toString().removeSuffix(".java")

        // Classes
        classRegex.findAll(joined).forEach { match ->
            val modifiers = splitModifiers(match.groupValues[1])
            val name = match.groupValues[2]
            val superclass = match.groupValues[3].trim().ifBlank { null }
            val interfaces = splitInheritance(match.groupValues[4])
            val startLine = lineNumberAt(content, findIndex(content, name, "class"))
            val endLine = findBlockEndLine(lines, startLine)
            val classAnnotations = annotationsAbove(lines, startLine)
            val fullAnnotations = annotationsWithParamsAbove(lines, startLine)
            annotations.addAll(classAnnotations)
            val documentation = extractJavadocBefore(lines, startLine)
            val purpose = inferJavaClassPurpose(name, classAnnotations, fullAnnotations)
            val patterns = detectJavaClassPatterns(name, classAnnotations, modifiers)

            val classFields = extractFields(lines, startLine, endLine)
            val classMethods = extractMethods(lines, startLine, endLine, joined)
            val classConstructors = extractConstructors(lines, startLine, endLine, name, joined)

            classes.add(
                ClassElement(
                    name = name,
                    type = if ("abstract" in modifiers) "abstract_class" else "class",
                    startLine = startLine,
                    endLine = endLine,
                    modifiers = modifiers,
                    superclass = superclass,
                    interfaces = interfaces,
                    annotations = classAnnotations,
                    documentation = documentation,
                    methods = classMethods + classConstructors,
                    fields = classFields,
                    purpose = purpose,
                    patterns = patterns
                )
            )
        }

        // Interfaces
        interfaceRegex.findAll(joined).forEach { match ->
            val modifiers = splitModifiers(match.groupValues[1])
            val name = match.groupValues[2]
            val interfaces = splitInheritance(match.groupValues[3])
            val startLine = lineNumberAt(content, findIndex(content, name, "interface"))
            val endLine = findBlockEndLine(lines, startLine)
            val classAnnotations = annotationsAbove(lines, startLine)
            annotations.addAll(classAnnotations)
            val documentation = extractJavadocBefore(lines, startLine)
            val classMethods = extractMethods(lines, startLine, endLine, joined)

            classes.add(
                ClassElement(
                    name = name,
                    type = "interface",
                    startLine = startLine,
                    endLine = endLine,
                    modifiers = modifiers,
                    interfaces = interfaces,
                    annotations = classAnnotations,
                    documentation = documentation,
                    methods = classMethods,
                    purpose = inferJavaClassPurpose(name, classAnnotations, emptyList())
                )
            )
        }

        // Enums
        enumRegex.findAll(joined).forEach { match ->
            val modifiers = splitModifiers(match.groupValues[1])
            val name = match.groupValues[2]
            val interfaces = splitInheritance(match.groupValues[3])
            val startLine = lineNumberAt(content, findIndex(content, name, "enum"))
            val endLine = findBlockEndLine(lines, startLine)
            val classAnnotations = annotationsAbove(lines, startLine)
            annotations.addAll(classAnnotations)
            val enumEntries = extractEnumConstants(lines, startLine, endLine)

            classes.add(
                ClassElement(
                    name = name,
                    type = "enum",
                    startLine = startLine,
                    endLine = endLine,
                    modifiers = modifiers,
                    interfaces = interfaces,
                    annotations = classAnnotations,
                    fields = enumEntries,
                    documentation = extractJavadocBefore(lines, startLine)
                )
            )
        }

        // Records (Java 14+)
        recordRegex.findAll(joined).forEach { match ->
            val modifiers = splitModifiers(match.groupValues[1])
            val name = match.groupValues[2]
            val paramsStr = match.groupValues[3]
            val interfaces = splitInheritance(match.groupValues[4])
            val startLine = lineNumberAt(content, findIndex(content, name, "record"))
            val endLine = findBlockEndLine(lines, startLine)
            val classAnnotations = annotationsAbove(lines, startLine)
            annotations.addAll(classAnnotations)

            val recordFields = parseJavaParameters(paramsStr).map {
                FieldElement(name = it.name, type = it.type, modifiers = listOf("final"))
            }

            classes.add(
                ClassElement(
                    name = name,
                    type = "record",
                    startLine = startLine,
                    endLine = endLine,
                    modifiers = modifiers,
                    interfaces = interfaces,
                    annotations = classAnnotations,
                    fields = recordFields,
                    documentation = extractJavadocBefore(lines, startLine),
                    purpose = "Record / Data Carrier",
                    patterns = listOf("Record")
                )
            )
        }

        // Collect all annotations
        annotationValueRegex.findAll(content).forEach { annotations.add(it.groupValues[1]) }

        // Framework detection
        frameworks.addAll(detectJavaFrameworks(content, annotations))

        val imports = importRegex.findAll(content).map {
            val isStatic = it.groupValues[1].isNotBlank()
            ImportElement(
                module = it.groupValues[2],
                alias = if (isStatic) "static" else null,
                isWildcard = it.groupValues[2].endsWith(".*")
            )
        }.toList()

        // Also add class methods to top-level functions for backward compatibility
        val allFunctions = functions.toMutableList()
        classes.forEach { cls ->
            allFunctions.addAll(cls.methods)
        }

        return CodeElements(
            classes = classes,
            functions = allFunctions,
            imports = imports,
            annotations = annotations.toList(),
            frameworks = frameworks.distinct()
        )
    }

    private fun findIndex(content: String, name: String, keyword: String): Int {
        val pattern = Regex("""\b$keyword\s+${Regex.escape(name)}\b""")
        return pattern.find(content)?.range?.first ?: content.indexOf(name).coerceAtLeast(0)
    }

    private fun splitModifiers(raw: String): List<String> =
        raw.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotBlank() }

    private fun splitInheritance(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        raw.forEach { ch ->
            when {
                ch == '<' -> { depth++; current.append(ch) }
                ch == '>' -> { depth--; current.append(ch) }
                ch == ',' && depth == 0 -> { parts.add(current.toString().trim()); current.setLength(0) }
                else -> current.append(ch)
            }
        }
        if (current.isNotBlank()) parts.add(current.toString().trim())
        return parts.filter { it.isNotBlank() }
    }

    private fun extractFields(lines: List<String>, startLine: Int, endLine: Int): List<FieldElement> {
        val fields = mutableListOf<FieldElement>()
        val fieldPattern = Regex(
            """^\s*((?:(?:public|protected|private|static|final|volatile|transient)\s+)+)([A-Za-z0-9_<>\[\].?,\s]+?)\s+([A-Za-z0-9_]+)\s*(?:=\s*([^;]+))?\s*;"""
        )
        for (i in startLine until (endLine - 1).coerceAtMost(lines.size)) {
            val match = fieldPattern.find(lines[i]) ?: continue
            val modifiers = splitModifiers(match.groupValues[1])
            val type = match.groupValues[2].trim()
            val name = match.groupValues[3]
            val init = match.groupValues[4].trim().ifBlank { null }
            val fAnnotations = annotationsAbove(lines, i + 1)
            fields.add(FieldElement(name = name, type = type, modifiers = modifiers, initializer = init?.take(80), annotations = fAnnotations))
        }
        return fields
    }

    private fun extractMethods(lines: List<String>, startLine: Int, endLine: Int, @Suppress("UNUSED_PARAMETER") joinedContent: String): List<FunctionElement> {
        val methods = mutableListOf<FunctionElement>()
        val bodyContent = lines.subList(startLine.coerceAtLeast(0), (endLine - 1).coerceAtMost(lines.size)).joinToString("\n")
        val joinedBody = joinMultilineDeclarations(bodyContent)
        methodRegex.findAll(joinedBody).forEach { match ->
            val modifiers = splitModifiers(match.groupValues[1])
            val returnType = match.groupValues[3].trim()
            val name = match.groupValues[4]
            val paramsStr = match.groupValues[5]
            val parameters = parseJavaParameters(paramsStr)
            // Find method's actual line range in original content for doc/calls extraction
            val methodStartLine = lineNumberAt(bodyContent, joinedBody.indexOf("$returnType $name(").coerceAtLeast(0)) + startLine
            val methodEndLine = findBlockEndLine(lines, methodStartLine)
            val fnAnnotations = annotationsAbove(lines, methodStartLine)
            val documentation = extractJavadocBefore(lines, methodStartLine)
            val complexity = estimateJavaComplexity(lines, methodStartLine, methodEndLine)
            val callsTo = extractJavaCalls(lines, methodStartLine, methodEndLine, name)
            methods.add(
                FunctionElement(
                    name = name,
                    startLine = methodStartLine,
                    endLine = methodEndLine,
                    signature = buildJavaSignature(modifiers, returnType, name, parameters),
                    returnType = returnType,
                    parameters = parameters,
                    modifiers = modifiers,
                    annotations = fnAnnotations,
                    documentation = documentation,
                    complexity = complexity,
                    isPublicApi = "public" in modifiers,
                    callsTo = callsTo
                )
            )
        }
        return methods
    }

    private fun extractConstructors(lines: List<String>, startLine: Int, endLine: Int, className: String, joinedContent: String): List<FunctionElement> {
        val constructors = mutableListOf<FunctionElement>()
        val bodyContent = lines.subList(startLine.coerceAtLeast(0), (endLine - 1).coerceAtMost(lines.size)).joinToString("\n")
        val joinedBody = joinMultilineDeclarations(bodyContent)
        constructorRegex.findAll(joinedBody).forEach { match ->
            val name = match.groupValues[2]
            if (name != className) return@forEach
            val modifiers = splitModifiers(match.groupValues[1])
            val paramsStr = match.groupValues[3]
            val parameters = parseJavaParameters(paramsStr)
            constructors.add(
                FunctionElement(
                    name = "<init>",
                    startLine = startLine,
                    endLine = endLine,
                    signature = "${modifiers.joinToString(" ")} $className(${paramsStr.trim()})".trim(),
                    parameters = parameters,
                    modifiers = modifiers + "constructor",
                    isPublicApi = "public" in modifiers
                )
            )
        }
        return constructors
    }

    private fun extractEnumConstants(lines: List<String>, startLine: Int, endLine: Int): List<FieldElement> {
        val entries = mutableListOf<FieldElement>()
        var insideBody = false
        for (i in (startLine - 1) until endLine.coerceAtMost(lines.size)) {
            val line = lines[i].trim()
            if (line.contains('{')) insideBody = true
            if (!insideBody) continue
            // Stop at first method/field or semicolon-only line
            if (line.startsWith("public ") || line.startsWith("private ") || line.startsWith("protected ") ||
                line.startsWith("@") || line == ";") break
            val entryMatch = enumConstantRegex.find(line)
            if (entryMatch != null) {
                entries.add(FieldElement(name = entryMatch.groupValues[1], modifiers = listOf("enum_constant")))
            }
        }
        return entries
    }

    private fun parseJavaParameters(paramsStr: String): List<ParameterElement> {
        if (paramsStr.isBlank()) return emptyList()
        return splitBalanced(paramsStr).mapNotNull { raw ->
            val trimmed = raw.trim()
            if (trimmed.isBlank()) return@mapNotNull null
            // Remove annotations from params
            val cleaned = trimmed.replace(Regex("""@\w+\s*(?:\([^)]*\))?\s*"""), "").trim()
            val tokens = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (tokens.isEmpty()) return@mapNotNull null
            val name = tokens.last()
            val type = tokens.dropLast(1).joinToString(" ").ifBlank { null }
            ParameterElement(name = name, type = type)
        }
    }

    private fun splitBalanced(input: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        input.forEach { ch ->
            when {
                ch == '<' -> { depth++; current.append(ch) }
                ch == '>' -> { depth = (depth - 1).coerceAtLeast(0); current.append(ch) }
                ch == ',' && depth == 0 -> { parts.add(current.toString()); current.setLength(0) }
                else -> current.append(ch)
            }
        }
        if (current.isNotBlank()) parts.add(current.toString())
        return parts
    }

    private fun buildJavaSignature(
        modifiers: List<String>, returnType: String, name: String, parameters: List<ParameterElement>
    ): String {
        val modStr = if (modifiers.isEmpty()) "" else modifiers.joinToString(" ") + " "
        val paramsStr = parameters.joinToString(", ") { p ->
            if (p.type != null) "${p.type} ${p.name}" else p.name
        }
        return "${modStr}${returnType} ${name}($paramsStr)"
    }

    private fun extractJavadocBefore(lines: List<String>, startLine: Int): String? {
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

    private fun estimateJavaComplexity(lines: List<String>, startLine: Int, endLine: Int): Int {
        val keywords = listOf("if", "for", "while", "case", "catch", "&&", "||", "?")
        val body = lines.subList((startLine - 1).coerceAtLeast(0), endLine.coerceAtMost(lines.size)).joinToString("\n")
        val hits = keywords.sumOf { kw ->
            if (kw.all { it.isLetter() }) Regex("""\b$kw\b""").findAll(body).count()
            else body.windowed(kw.length).count { it == kw }
        }
        return 1 + hits
    }

    private fun extractJavaCalls(lines: List<String>, startLine: Int, endLine: Int, functionName: String): List<String> {
        val body = lines.subList((startLine - 1).coerceAtLeast(0), endLine.coerceAtMost(lines.size)).joinToString("\n")
        val callRegex = Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        val ignored = setOf("if", "for", "while", "return", "new", "switch", "catch", "throw", "super", "this")
        return callRegex.findAll(body)
            .map { it.groupValues[1] }
            .filter { it != functionName && it !in ignored }
            .distinct().take(15).toList()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun inferJavaClassPurpose(name: String, annotations: List<String>, fullAnnotations: List<String>): String? {
        return when {
            annotations.any { it == "RestController" || it == "Controller" } -> "REST API Controller"
            annotations.any { it == "Service" } -> "Business Logic Service"
            annotations.any { it == "Repository" } -> "Data Access Repository"
            annotations.any { it == "Entity" || it == "Table" } -> "JPA Entity / Domain Model"
            annotations.any { it == "Configuration" } -> "Spring Configuration"
            annotations.any { it == "Component" } -> "Spring Component"
            annotations.any { it == "Aspect" } -> "AOP Aspect"
            annotations.any { it == "MappedSuperclass" } -> "JPA Mapped Superclass"
            name.endsWith("DTO") || name.endsWith("Dto") -> "Data Transfer Object"
            name.endsWith("Request") || name.endsWith("Response") -> "API Model"
            name.endsWith("Factory") -> "Factory Pattern"
            name.endsWith("Builder") -> "Builder Pattern"
            name.endsWith("Adapter") -> "Adapter Pattern"
            name.endsWith("Strategy") -> "Strategy Pattern"
            name.endsWith("Repository") -> "Repository"
            name.endsWith("Service") || name.endsWith("ServiceImpl") -> "Service"
            name.endsWith("Controller") -> "Controller"
            name.endsWith("Handler") -> "Handler"
            name.endsWith("Interceptor") -> "Interceptor"
            name.endsWith("Filter") -> "Servlet Filter"
            name.endsWith("Converter") -> "Type Converter"
            name.endsWith("Validator") -> "Validator"
            name.endsWith("Mapper") -> "Object Mapper"
            name.endsWith("Test") || name.endsWith("Tests") || name.endsWith("IT") -> "Test Class"
            name.endsWith("Exception") -> "Exception"
            name.endsWith("Config") || name.endsWith("Properties") -> "Configuration"
            else -> null
        }
    }

    private fun detectJavaClassPatterns(name: String, annotations: List<String>, modifiers: List<String>): List<String> {
        val patterns = mutableListOf<String>()
        if ("abstract" in modifiers) patterns.add("Abstract")
        if ("final" in modifiers) patterns.add("Final")
        if (annotations.any { it == "Singleton" || it == "Scope" }) patterns.add("Singleton")
        if (name.endsWith("Factory")) patterns.add("Factory")
        if (name.endsWith("Builder")) patterns.add("Builder")
        if (name.endsWith("Adapter")) patterns.add("Adapter")
        if (name.endsWith("Decorator")) patterns.add("Decorator")
        if (name.endsWith("Observer") || name.endsWith("Listener")) patterns.add("Observer")
        if (name.endsWith("Strategy")) patterns.add("Strategy")
        if (name.endsWith("Visitor")) patterns.add("Visitor")
        return patterns
    }

    private fun detectJavaFrameworks(content: String, annotations: Set<String>): List<String> {
        val frameworks = mutableListOf<String>()
        if ("Controller" in annotations || "RestController" in annotations) frameworks.add("Spring MVC")
        if ("Service" in annotations) frameworks.add("Spring Service")
        if ("Repository" in annotations) frameworks.add("Spring Data")
        if ("Component" in annotations) frameworks.add("Spring Component")
        if ("Configuration" in annotations) frameworks.add("Spring Configuration")
        if ("Entity" in annotations || "Table" in annotations) frameworks.add("JPA")
        if ("Autowired" in annotations || "Inject" in annotations) frameworks.add("Dependency Injection")
        if ("Transactional" in annotations) frameworks.add("Spring Transaction")
        if ("Cacheable" in annotations || "CacheEvict" in annotations) frameworks.add("Spring Cache")
        if ("Scheduled" in annotations || "Async" in annotations) frameworks.add("Spring Scheduling")
        if ("Test" in annotations || "ParameterizedTest" in annotations) frameworks.add("JUnit")
        if ("Mock" in annotations || "InjectMocks" in annotations) frameworks.add("Mockito")
        if (content.contains("@Slf4j") || content.contains("LoggerFactory")) frameworks.add("SLF4J Logging")
        if (content.contains("@Data") || content.contains("@Getter") || content.contains("@Builder")) frameworks.add("Lombok")
        return frameworks
    }
}
