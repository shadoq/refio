package pl.jclab.refio.core.services.analysis

import java.nio.file.Path

class JavaLanguageAnalyzer : ExtensionLanguageAnalyzer(
    languageId = "java",
    extensions = setOf(".java")
) {

    private val classRegex = Regex(
        """(public|protected|private|abstract|final|\s)*class\s+([A-Za-z0-9_]+)\s*(?:extends\s+([A-Za-z0-9_<>,\s]+))?\s*(?:implements\s+([A-Za-z0-9_<>,\s]+))?"""
    )
    private val interfaceRegex = Regex(
        """(public|protected|private|\s)*interface\s+([A-Za-z0-9_]+)\s*(?:extends\s+([A-Za-z0-9_<>,\s]+))?"""
    )
    private val enumRegex = Regex("""(public|protected|private|\s)*enum\s+([A-Za-z0-9_]+)""")
    private val methodWithTypesRegex = Regex(
        """((?:public|protected|private|static|final|abstract|synchronized|native|\s)+)
           ([A-Za-z0-9_<>\[\].?]+)\s+
           ([A-Za-z0-9_]+)\s*
           \(([^)]*)\)
           (?:\s*throws\s+[\w,\s]+)?""".trimIndent().replace("\n", ""),
        RegexOption.COMMENTS
    )
    private val importRegex = Regex("""import\s+([\w.\*]+)(?:\s+as\s+(\w+))?""")

    override fun analyze(filePath: Path, content: String): CodeElements {
        val lines = content.lines()
        val classes = mutableListOf<ClassElement>()
        val functions = mutableListOf<FunctionElement>()
        val annotations = mutableSetOf<String>()
        val frameworks = mutableListOf<String>()

        classRegex.findAll(content).forEach { match ->
            val modifier = match.groupValues[1].trim()
            val name = match.groupValues[2]
            val superclass = match.groupValues[3].trim().ifBlank { null }
            val interfaces = match.groupValues[4].split(",").map { it.trim() }.filter { it.isNotBlank() }
            val startLine = lineNumberAt(content, match.range.first)
            val endLine = findBlockEndLine(lines, startLine)
            val classAnnotations = annotationsAbove(lines, startLine)
            annotations.addAll(classAnnotations)
            val documentation = extractJavadocBefore(lines, startLine)
            val purpose = inferJavaClassPurpose(name, classAnnotations)
            val patterns = detectJavaClassPatterns(name, classAnnotations)

            classes.add(
                ClassElement(
                    name = name,
                    type = if (modifier.contains("abstract")) "abstract_class" else "class",
                    startLine = startLine,
                    endLine = endLine,
                    modifiers = splitJavaModifiers(modifier),
                    superclass = superclass,
                    interfaces = interfaces,
                    annotations = classAnnotations,
                    documentation = documentation,
                    purpose = purpose,
                    patterns = patterns
                )
            )
        }

        interfaceRegex.findAll(content).forEach { match ->
            val name = match.groupValues[2]
            val interfaces = match.groupValues[3].split(",").map { it.trim() }.filter { it.isNotBlank() }
            val startLine = lineNumberAt(content, match.range.first)
            val endLine = findBlockEndLine(lines, startLine)
            val classAnnotations = annotationsAbove(lines, startLine)
            annotations.addAll(classAnnotations)
            val documentation = extractJavadocBefore(lines, startLine)
            val purpose = inferJavaClassPurpose(name, classAnnotations)
            val patterns = detectJavaClassPatterns(name, classAnnotations)

            classes.add(
                ClassElement(
                    name = name,
                    type = "interface",
                    startLine = startLine,
                    endLine = endLine,
                    interfaces = interfaces,
                    annotations = classAnnotations,
                    documentation = documentation,
                    purpose = purpose,
                    patterns = patterns
                )
            )
        }

        enumRegex.findAll(content).forEach { match ->
            val name = match.groupValues[2]
            val startLine = lineNumberAt(content, match.range.first)
            val endLine = findBlockEndLine(lines, startLine)
            classes.add(
                ClassElement(
                    name = name,
                    type = "enum",
                    startLine = startLine,
                    endLine = endLine
                )
            )
        }

        methodWithTypesRegex.findAll(content).forEach { match ->
            val modifiers = splitJavaModifiers(match.groupValues[1])
            val returnType = match.groupValues[2].trim()
            val name = match.groupValues[3]
            val paramsStr = match.groupValues[4]
            val startLine = lineNumberAt(content, match.range.first)
            val endLine = findBlockEndLine(lines, startLine)
            val fnAnnotations = annotationsAbove(lines, startLine)
            annotations.addAll(fnAnnotations)
            val documentation = extractJavadocBefore(lines, startLine)
            val parameters = parseJavaParameters(paramsStr)
            val signature = buildJavaSignature(modifiers, returnType, name, parameters)
            val complexity = estimateJavaComplexity(lines, startLine, endLine)
            val callsTo = extractJavaCalls(lines, startLine, endLine, name)

            functions.add(
                FunctionElement(
                    name = name,
                    startLine = startLine,
                    endLine = endLine,
                    modifiers = modifiers,
                    annotations = fnAnnotations,
                    signature = signature,
                    returnType = returnType,
                    parameters = parameters,
                    documentation = documentation,
                    complexity = complexity,
                    isPublicApi = modifiers.contains("public"),
                    callsTo = callsTo
                )
            )
        }

        Regex("""@([A-Za-z0-9_]+)""").findAll(content).forEach { annotations.add(it.groupValues[1]) }

        if (content.contains("@Controller") || content.contains("@RestController")) {
            frameworks.add("Spring MVC Controller")
        }
        if (content.contains("@Service")) frameworks.add("Spring Service")
        if (content.contains("@Repository")) frameworks.add("Spring Repository")
        if (content.contains("@Component")) frameworks.add("Spring Component")
        if (content.contains("@Configuration")) frameworks.add("Spring Configuration")
        if (content.contains("@Entity") || content.contains("@Table")) frameworks.add("JPA Entity")
        if (content.contains("@Autowired") || content.contains("@Inject")) frameworks.add("Dependency Injection")

        val imports = importRegex.findAll(content).map {
            ImportElement(
                module = it.groupValues[1],
                alias = it.groupValues[2].ifBlank { null },
                isWildcard = it.groupValues[1].endsWith(".*")
            )
        }.toList()

        return CodeElements(
            classes = classes,
            functions = functions,
            imports = imports,
            annotations = annotations.toList(),
            frameworks = frameworks.distinct()
        )
    }

    private fun splitJavaModifiers(raw: String): List<String> {
        return raw.split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun parseJavaParameters(paramsStr: String): List<ParameterElement> {
        if (paramsStr.isBlank()) return emptyList()
        return splitJavaParameters(paramsStr).mapNotNull { raw ->
            val trimmed = raw.trim()
            if (trimmed.isBlank()) return@mapNotNull null
            val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (tokens.isEmpty()) return@mapNotNull null
            val name = tokens.last()
            val type = tokens.dropLast(1).joinToString(" ").ifBlank { null }
            ParameterElement(name = name, type = type)
        }
    }

    private fun splitJavaParameters(paramsStr: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        paramsStr.forEach { ch ->
            when (ch) {
                '<' -> {
                    depth++
                    current.append(ch)
                }
                '>' -> {
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

    private fun buildJavaSignature(
        modifiers: List<String>,
        returnType: String,
        name: String,
        parameters: List<ParameterElement>
    ): String {
        val modifiersStr = if (modifiers.isEmpty()) "" else modifiers.joinToString(" ") + " "
        val paramsStr = parameters.joinToString(", ") { param ->
            if (param.type != null) "${param.type} ${param.name}" else param.name
        }
        return "${modifiersStr}${returnType} ${name}($paramsStr)"
    }

    private fun extractJavadocBefore(lines: List<String>, startLine: Int): String? {
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

    private fun estimateJavaComplexity(lines: List<String>, startLine: Int, endLine: Int): Int {
        val keywords = listOf("if", "for", "while", "case", "catch", "&&", "||", "?")
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

    private fun extractJavaCalls(lines: List<String>, startLine: Int, endLine: Int, functionName: String): List<String> {
        val body = lines.subList((startLine - 1).coerceAtLeast(0), endLine.coerceAtMost(lines.size)).joinToString("\n")
        val callRegex = Regex("""\b([A-Za-z_][A-Za-z0-9_]*)\s*\(""")
        val ignored = setOf("if", "for", "while", "return", "new", "switch", "catch", "throw", "super", "this")
        return callRegex.findAll(body)
            .map { it.groupValues[1] }
            .filter { it != functionName && it !in ignored }
            .distinct()
            .take(15)
            .toList()
    }

    private fun inferJavaClassPurpose(name: String, annotations: List<String>): String? {
        return when {
            annotations.any { it.contains("Controller") } -> "REST API Controller"
            annotations.any { it.contains("Service") } -> "Business Logic Service"
            annotations.any { it.contains("Repository") } -> "Data Access Repository"
            annotations.any { it.contains("Entity") } -> "JPA Entity / Domain Model"
            annotations.any { it.contains("Configuration") } -> "Spring Configuration"
            name.endsWith("DTO") || name.endsWith("Request") || name.endsWith("Response") -> "Data Transfer Object"
            name.endsWith("Factory") -> "Factory Pattern Implementation"
            name.endsWith("Builder") -> "Builder Pattern Implementation"
            else -> null
        }
    }

    private fun detectJavaClassPatterns(name: String, annotations: List<String>): List<String> {
        val patterns = mutableListOf<String>()
        if (name.endsWith("Factory")) patterns.add("Factory")
        if (name.endsWith("Builder")) patterns.add("Builder")
        if (annotations.any { it.contains("Singleton") }) patterns.add("Singleton")
        return patterns
    }
}
