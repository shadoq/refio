package pl.jclab.refio.core.services.analysis

import java.nio.file.Path

class RustLanguageAnalyzer : ExtensionLanguageAnalyzer(
    languageId = "rust",
    extensions = setOf(".rs")
) {

    private val structRegex = Regex(
        """(?:^|\n)\s*(pub(?:\(crate\))?\s+)?struct\s+([A-Za-z_][A-Za-z0-9_]*)(?:<[^>]*>)?\s*(?:where\s+[^{]*?)?\s*\{"""
    )
    private val tupleStructRegex = Regex(
        """(?:^|\n)\s*(pub(?:\(crate\))?\s+)?struct\s+([A-Za-z_][A-Za-z0-9_]*)(?:<[^>]*>)?\s*\(([^)]*)\)\s*;"""
    )
    private val enumRegex = Regex(
        """(?:^|\n)\s*(pub(?:\(crate\))?\s+)?enum\s+([A-Za-z_][A-Za-z0-9_]*)(?:<[^>]*>)?\s*(?:where\s+[^{]*?)?\s*\{"""
    )
    private val traitRegex = Regex(
        """(?:^|\n)\s*(pub(?:\(crate\))?\s+)?trait\s+([A-Za-z_][A-Za-z0-9_]*)(?:<[^>]*>)?\s*(?::\s*([^\{]+))?\s*\{"""
    )
    private val implRegex = Regex(
        """(?:^|\n)\s*impl(?:<[^>]*>)?\s+(?:([A-Za-z_][A-Za-z0-9_:<>]*)\s+for\s+)?([A-Za-z_][A-Za-z0-9_]*)(?:<[^>]*>)?\s*(?:where\s+[^{]*?)?\s*\{"""
    )
    private val fnRegex = Regex(
        """(?:^|\n)\s*(pub(?:\(crate\))?\s+)?(?:(async|const|unsafe)\s+)?fn\s+([A-Za-z_][A-Za-z0-9_]*)\s*(?:<[^>]*>)?\s*\(([^)]*)\)\s*(?:->\s*([^\n{]+))?\s*(?:where\s+[^{]*?)?\s*[{;]"""
    )
    private val constStaticRegex = Regex(
        """(?:^|\n)\s*(pub(?:\(crate\))?\s+)?(const|static)\s+([A-Z_][A-Z0-9_]*)\s*:\s*([^\n=]+?)\s*="""
    )
    private val typeAliasRegex = Regex(
        """(?:^|\n)\s*(pub(?:\(crate\))?\s+)?type\s+([A-Za-z_][A-Za-z0-9_]*)(?:<[^>]*>)?\s*=\s*([^;]+);"""
    )
    private val useRegex = Regex("""use\s+([\w:]+(?::\{[^}]+\}|::\*)?)\s*;""")
    private val deriveRegex = Regex("""#\[derive\(([^)]+)\)]""")
    private val structFieldRegex = Regex("""^\s*(pub(?:\(crate\))?\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*:\s*([^\n,]+),?\s*$""")
    private val enumVariantRegex = Regex("""^\s*([A-Za-z_][A-Za-z0-9_]*)(?:\s*\{[^}]*\}|\s*\([^)]*\))?\s*,?\s*$""")

    override fun analyze(filePath: Path, content: String): CodeElements {
        val lines = content.lines()
        val joined = joinMultilineDeclarations(content)
        val classes = mutableListOf<ClassElement>()
        val functions = mutableListOf<FunctionElement>()
        val topFields = mutableListOf<FieldElement>()

        // Collect derive macros per type
        val derivesByLine = mutableMapOf<Int, List<String>>()
        deriveRegex.findAll(content).forEach { match ->
            val line = lineNumberAt(content, match.range.first)
            val derives = match.groupValues[1].split(",").map { it.trim() }
            derivesByLine[line] = derives
        }

        // Structs
        structRegex.findAll(content).forEach { match ->
            val isPub = match.groupValues[1].isNotBlank()
            val name = match.groupValues[2]
            val startLine = lineNumberAt(content, match.range.first)
            val endLine = findBlockEndLine(lines, startLine)
            val fields = extractStructFields(lines, startLine, endLine)
            val derives = findDerivesAbove(derivesByLine, startLine)
            val documentation = extractRustDocBefore(lines, startLine)
            val purpose = inferRustPurpose(name, derives, fields)

            classes.add(
                ClassElement(
                    name = name,
                    type = "struct",
                    startLine = startLine,
                    endLine = endLine,
                    modifiers = if (isPub) listOf("pub") else emptyList(),
                    fields = fields,
                    annotations = derives,
                    documentation = documentation,
                    purpose = purpose,
                    patterns = detectRustPatterns(name, derives)
                )
            )
        }

        // Tuple structs
        tupleStructRegex.findAll(content).forEach { match ->
            val isPub = match.groupValues[1].isNotBlank()
            val name = match.groupValues[2]
            val fieldsStr = match.groupValues[3]
            val startLine = lineNumberAt(content, match.range.first)
            val derives = findDerivesAbove(derivesByLine, startLine)
            val tupleFields = fieldsStr.split(",").mapIndexedNotNull { idx, f ->
                val t = f.trim(); if (t.isBlank()) null else FieldElement(name = "$idx", type = t)
            }
            classes.add(
                ClassElement(
                    name = name, type = "tuple_struct", startLine = startLine, endLine = startLine,
                    modifiers = if (isPub) listOf("pub") else emptyList(),
                    fields = tupleFields, annotations = derives,
                    documentation = extractRustDocBefore(lines, startLine)
                )
            )
        }

        // Enums
        enumRegex.findAll(content).forEach { match ->
            val isPub = match.groupValues[1].isNotBlank()
            val name = match.groupValues[2]
            val startLine = lineNumberAt(content, match.range.first)
            val endLine = findBlockEndLine(lines, startLine)
            val variants = extractEnumVariants(lines, startLine, endLine)
            val derives = findDerivesAbove(derivesByLine, startLine)
            val documentation = extractRustDocBefore(lines, startLine)

            classes.add(
                ClassElement(
                    name = name, type = "enum", startLine = startLine, endLine = endLine,
                    modifiers = if (isPub) listOf("pub") else emptyList(),
                    fields = variants, annotations = derives,
                    documentation = documentation,
                    patterns = if (name.endsWith("Error")) listOf("Error") else emptyList()
                )
            )
        }

        // Traits
        traitRegex.findAll(content).forEach { match ->
            val isPub = match.groupValues[1].isNotBlank()
            val name = match.groupValues[2]
            val supertraits = match.groupValues[3].trim().ifBlank { null }
            val startLine = lineNumberAt(content, match.range.first)
            val endLine = findBlockEndLine(lines, startLine)
            val methods = extractTraitMethods(lines, startLine, endLine)
            val documentation = extractRustDocBefore(lines, startLine)

            classes.add(
                ClassElement(
                    name = name, type = "trait", startLine = startLine, endLine = endLine,
                    modifiers = if (isPub) listOf("pub") else emptyList(),
                    interfaces = supertraits?.split("+")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
                    methods = methods,
                    documentation = documentation,
                    purpose = "Trait Contract"
                )
            )
        }

        // Impl blocks — attach methods to struct
        implRegex.findAll(content).forEach { match ->
            val traitName = match.groupValues[1].trim().ifBlank { null }
            val typeName = match.groupValues[2]
            val startLine = lineNumberAt(content, match.range.first)
            val endLine = findBlockEndLine(lines, startLine)
            val methods = extractImplMethods(lines, startLine, endLine, joined)

            // Find matching class and add methods
            val existing = classes.find { it.name == typeName }
            if (existing != null) {
                val idx = classes.indexOf(existing)
                classes[idx] = existing.copy(
                    methods = existing.methods + methods,
                    interfaces = existing.interfaces + listOfNotNull(traitName)
                )
            }
        }

        // Free functions
        fnRegex.findAll(joined).forEach { match ->
            val isPub = match.groupValues[1].isNotBlank()
            val qualifier = match.groupValues[2].trim()
            val name = match.groupValues[3]
            val paramsStr = match.groupValues[4]
            val returnType = match.groupValues[5].trim().ifBlank { null }
            val startLine = lineNumberAt(content, content.indexOf("fn $name(").coerceAtLeast(0))
            // Skip if inside impl/trait block
            if (classes.any { cl -> startLine > cl.startLine && startLine < cl.endLine && cl.type in setOf("struct", "trait") }) return@forEach
            val endLine = findBlockEndLine(lines, startLine)
            val parameters = parseRustParameters(paramsStr)
            val documentation = extractRustDocBefore(lines, startLine)
            val modifiers = mutableListOf<String>()
            if (isPub) modifiers.add("pub")
            if (qualifier.isNotBlank()) modifiers.add(qualifier)

            functions.add(
                FunctionElement(
                    name = name,
                    startLine = startLine,
                    endLine = endLine,
                    signature = buildRustSignature(isPub, qualifier, name, parameters, returnType),
                    returnType = returnType,
                    parameters = parameters,
                    modifiers = modifiers,
                    documentation = documentation,
                    isPublicApi = isPub,
                    complexity = estimateComplexity(lines, startLine, endLine)
                )
            )
        }

        // Constants and statics
        constStaticRegex.findAll(content).forEach { match ->
            val kind = match.groupValues[2]
            val name = match.groupValues[3]
            val type = match.groupValues[4].trim()
            topFields.add(FieldElement(name = name, type = type, modifiers = listOf(kind)))
        }

        // Type aliases
        typeAliasRegex.findAll(content).forEach { match ->
            val name = match.groupValues[2]
            val target = match.groupValues[3].trim()
            topFields.add(FieldElement(name = name, type = target, modifiers = listOf("type_alias")))
        }

        // Imports
        val imports = useRegex.findAll(content).map { ImportElement(module = it.groupValues[1]) }.toList()
        val frameworks = detectRustFrameworks(imports, content)

        // Module-level declarations
        if (topFields.isNotEmpty()) {
            val modName = filePath.fileName.toString().removeSuffix(".rs")
            classes.add(
                ClassElement(
                    name = modName, type = "module", startLine = 1, endLine = lines.size,
                    fields = topFields, purpose = "Module-level declarations"
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

    private fun extractStructFields(lines: List<String>, startLine: Int, endLine: Int): List<FieldElement> {
        val fields = mutableListOf<FieldElement>()
        for (i in startLine until (endLine - 1).coerceAtMost(lines.size)) {
            val match = structFieldRegex.find(lines[i]) ?: continue
            val isPub = match.groupValues[1].isNotBlank()
            val name = match.groupValues[2]
            val type = match.groupValues[3].trim().removeSuffix(",")
            fields.add(FieldElement(name = name, type = type, modifiers = if (isPub) listOf("pub") else emptyList()))
        }
        return fields
    }

    private fun extractEnumVariants(lines: List<String>, startLine: Int, endLine: Int): List<FieldElement> {
        val variants = mutableListOf<FieldElement>()
        for (i in startLine until (endLine - 1).coerceAtMost(lines.size)) {
            val line = lines[i].trim()
            if (line.startsWith("//") || line.isEmpty() || line == "{" || line == "}") continue
            val match = enumVariantRegex.find(line) ?: continue
            variants.add(FieldElement(name = match.groupValues[1], modifiers = listOf("variant")))
        }
        return variants
    }

    private fun extractTraitMethods(lines: List<String>, startLine: Int, endLine: Int): List<FunctionElement> {
        return extractMethodsFromBlock(lines, startLine, endLine)
    }

    private fun extractImplMethods(lines: List<String>, startLine: Int, endLine: Int, joinedContent: String): List<FunctionElement> {
        return extractMethodsFromBlock(lines, startLine, endLine)
    }

    private fun extractMethodsFromBlock(lines: List<String>, startLine: Int, endLine: Int): List<FunctionElement> {
        val methods = mutableListOf<FunctionElement>()
        val bodyContent = lines.subList(startLine.coerceAtLeast(0), (endLine - 1).coerceAtMost(lines.size)).joinToString("\n")
        val joinedBody = joinMultilineDeclarations(bodyContent)
        fnRegex.findAll(joinedBody).forEach { match ->
            val isPub = match.groupValues[1].isNotBlank()
            val qualifier = match.groupValues[2].trim()
            val name = match.groupValues[3]
            val paramsStr = match.groupValues[4]
            val returnType = match.groupValues[5].trim().ifBlank { null }
            val parameters = parseRustParameters(paramsStr)
            val modifiers = mutableListOf<String>()
            if (isPub) modifiers.add("pub")
            if (qualifier.isNotBlank()) modifiers.add(qualifier)
            if (paramsStr.trim().startsWith("&self") || paramsStr.trim().startsWith("&mut self") ||
                paramsStr.trim().startsWith("self")) modifiers.add("method")

            methods.add(
                FunctionElement(
                    name = name,
                    startLine = startLine,
                    endLine = endLine,
                    signature = buildRustSignature(isPub, qualifier, name, parameters, returnType),
                    returnType = returnType,
                    parameters = parameters,
                    modifiers = modifiers,
                    isPublicApi = isPub
                )
            )
        }
        return methods
    }

    private fun parseRustParameters(paramsStr: String): List<ParameterElement> {
        if (paramsStr.isBlank()) return emptyList()
        return paramsStr.split(",").mapNotNull { raw ->
            val trimmed = raw.trim()
            if (trimmed.isBlank() || trimmed == "&self" || trimmed == "&mut self" || trimmed == "self" || trimmed == "mut self") return@mapNotNull null
            val parts = trimmed.split(":", limit = 2)
            val name = parts[0].trim().removePrefix("mut ").trim()
            val type = parts.getOrNull(1)?.trim()
            ParameterElement(name = name, type = type)
        }
    }

    private fun buildRustSignature(isPub: Boolean, qualifier: String, name: String, params: List<ParameterElement>, returnType: String?): String {
        val pub = if (isPub) "pub " else ""
        val qual = if (qualifier.isNotBlank()) "$qualifier " else ""
        val paramsStr = params.joinToString(", ") { p ->
            if (p.type != null) "${p.name}: ${p.type}" else p.name
        }
        val ret = returnType?.let { " -> $it" } ?: ""
        return "${pub}${qual}fn ${name}($paramsStr)$ret"
    }

    private fun extractRustDocBefore(lines: List<String>, startLine: Int): String? {
        val docLines = mutableListOf<String>()
        var i = startLine - 2
        while (i >= 0) {
            val line = lines[i].trim()
            when {
                line.startsWith("///") -> { docLines.add(0, line.removePrefix("///").trim()); i-- }
                line.startsWith("//!") -> { docLines.add(0, line.removePrefix("//!").trim()); i-- }
                line.startsWith("#[") -> { i--; continue } // skip attributes
                else -> break
            }
        }
        return docLines.joinToString(" ").trim().ifBlank { null }
    }

    private fun findDerivesAbove(derivesByLine: Map<Int, List<String>>, startLine: Int): List<String> {
        // Check up to 5 lines above for derive macros
        for (offset in 1..5) {
            val derives = derivesByLine[startLine - offset]
            if (derives != null) return derives
        }
        return emptyList()
    }

    private fun estimateComplexity(lines: List<String>, startLine: Int, endLine: Int): Int {
        val keywords = listOf("if", "for", "while", "loop", "match", "&&", "||", "?")
        val body = lines.subList((startLine - 1).coerceAtLeast(0), endLine.coerceAtMost(lines.size)).joinToString("\n")
        val hits = keywords.sumOf { kw ->
            if (kw.all { it.isLetter() }) Regex("""\b$kw\b""").findAll(body).count()
            else body.windowed(kw.length).count { it == kw }
        }
        return 1 + hits
    }

    private fun inferRustPurpose(name: String, derives: List<String>, fields: List<FieldElement>): String? {
        return when {
            derives.contains("Error") || name.endsWith("Error") -> "Error Type"
            derives.contains("Serialize") && derives.contains("Deserialize") -> "Serializable Data"
            name.endsWith("Config") || name.endsWith("Options") || name.endsWith("Settings") -> "Configuration"
            name.endsWith("Builder") -> "Builder Pattern"
            name.endsWith("Handler") -> "Handler"
            name.endsWith("Client") -> "Client"
            name.endsWith("Server") -> "Server"
            name.endsWith("Request") || name.endsWith("Response") -> "API Model"
            name.endsWith("Context") || name.endsWith("State") -> "State Container"
            name.endsWith("Service") -> "Service"
            name.endsWith("Repository") || name.endsWith("Store") -> "Data Store"
            else -> null
        }
    }

    private fun detectRustPatterns(name: String, derives: List<String>): List<String> {
        val patterns = mutableListOf<String>()
        if (name.endsWith("Builder")) patterns.add("Builder")
        if (name.endsWith("Factory")) patterns.add("Factory")
        if (derives.contains("Clone") && derives.contains("Debug")) patterns.add("Value")
        if (derives.contains("Default")) patterns.add("Default")
        if (derives.contains("Serialize") || derives.contains("Deserialize")) patterns.add("Serde")
        return patterns
    }

    private fun detectRustFrameworks(imports: List<ImportElement>, content: String): List<String> {
        val frameworks = mutableListOf<String>()
        val paths = imports.map { it.module }.toSet()
        if (paths.any { it.startsWith("actix_web") || it.startsWith("actix_rt") }) frameworks.add("Actix Web")
        if (paths.any { it.startsWith("rocket") }) frameworks.add("Rocket")
        if (paths.any { it.startsWith("axum") }) frameworks.add("Axum")
        if (paths.any { it.startsWith("warp") }) frameworks.add("Warp")
        if (paths.any { it.startsWith("tokio") }) frameworks.add("Tokio")
        if (paths.any { it.startsWith("async_std") }) frameworks.add("async-std")
        if (paths.any { it.startsWith("serde") }) frameworks.add("Serde")
        if (paths.any { it.startsWith("diesel") }) frameworks.add("Diesel ORM")
        if (paths.any { it.startsWith("sqlx") }) frameworks.add("SQLx")
        if (paths.any { it.startsWith("tonic") }) frameworks.add("Tonic gRPC")
        if (paths.any { it.startsWith("clap") }) frameworks.add("Clap CLI")
        if (paths.any { it.startsWith("tracing") }) frameworks.add("Tracing")
        if ("#[test]" in content || "#[cfg(test)]" in content) frameworks.add("Rust Testing")
        return frameworks.distinct()
    }
}
