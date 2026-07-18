package pl.jclab.refio.core.services

/**
 * Detects architectural patterns, entry points, and top-level modules from a
 * project file tree and infers the overall architecture style.
 */
internal object ProjectArchitectureDetector {

    /**
     * Detect architectural patterns in the project.
     */
    fun detectArchitecturalPatterns(fileTree: FileNode): ArchitectureInfo {
        val patterns = mutableListOf<String>()
        val folders = collectFolderNames(fileTree)
        val files = flattenFiles(fileTree)

        // Wykrywanie wzorców
        if (folders.any { it in listOf("src", "lib", "dist") }) {
            patterns.add("Standard JS/TS project structure")
        }
        if (folders.any { it in listOf("components", "views", "pages") }) {
            patterns.add("Component-based architecture")
        }
        if (folders.any { it in listOf("models", "controllers", "views") }) {
            patterns.add("MVC pattern")
        }
        if (folders.any { it in listOf("domain", "application", "infrastructure") }) {
            patterns.add("Clean Architecture / DDD")
        }
        if (files.any { it.name == "index.html" } && files.count { it.name.endsWith(".html") } > 10) {
            patterns.add("Multi-page static site")
        }
        if (files.any { it.name.contains("game", ignoreCase = true) || it.relativePath.contains("arcade") }) {
            patterns.add("Game collection / Arcade")
        }

        // Wykrywanie entry points
        val entryPoints = detectEntryPoints(files)

        // Wykrywanie głównych modułów
        val modules = detectModules(fileTree)

        return ArchitectureInfo(
            patterns = patterns,
            entryPoints = entryPoints,
            modules = modules,
            style = inferArchitectureStyle(patterns)
        )
    }

    /**
     * Detect entry points in the project.
     */
    private fun detectEntryPoints(files: List<FileNode>): List<EntryPoint> {
        val entryPoints = mutableListOf<EntryPoint>()

        files.forEach { file ->
            when {
                file.name == "index.html" -> entryPoints.add(
                    EntryPoint(file.relativePath, "Web entry", "HTML")
                )
                file.name == "main.kt" || file.name == "main.kt" -> entryPoints.add(
                    EntryPoint(file.relativePath, "Application entry", "Kotlin")
                )
                file.name == "index.js" || file.name == "index.ts" -> entryPoints.add(
                    EntryPoint(file.relativePath, "Module entry", "JavaScript/TypeScript")
                )
                file.name == "__main__.py" || file.name == "main.py" -> entryPoints.add(
                    EntryPoint(file.relativePath, "Python entry", "Python")
                )
                file.name == "App.tsx" || file.name == "App.jsx" -> entryPoints.add(
                    EntryPoint(file.relativePath, "React app entry", "React")
                )
            }
        }

        return entryPoints.take(10)
    }

    /**
     * Detect modules in the project.
     */
    private fun detectModules(fileTree: FileNode): List<ModuleInfo> {
        val modules = mutableListOf<ModuleInfo>()

        fileTree.children.filter { it.isDirectory }.forEach { dir ->
            val fileCount = countFilesRecursive(dir)
            val primaryType = detectPrimaryFileType(dir)

            if (fileCount > 0) {
                modules.add(ModuleInfo(
                    name = dir.name,
                    path = dir.relativePath,
                    fileCount = fileCount,
                    primaryType = primaryType,
                    description = inferModuleDescription(dir.name)
                ))
            }
        }

        return modules.sortedByDescending { it.fileCount }.take(10)
    }

    /**
     * Count files recursively in directory node.
     */
    private fun countFilesRecursive(node: FileNode): Int {
        if (!node.isDirectory) return 1
        return node.children.sumOf { countFilesRecursive(it) }
    }

    /**
     * Detect primary file type in directory.
     */
    private fun detectPrimaryFileType(dir: FileNode): String {
        val extensions = mutableMapOf<String, Int>()

        fun collectExtensions(node: FileNode) {
            if (!node.isDirectory) {
                val ext = node.name.substringAfterLast('.', "")
                if (ext.isNotEmpty()) {
                    extensions[ext] = extensions.getOrDefault(ext, 0) + 1
                }
            } else {
                node.children.forEach { collectExtensions(it) }
            }
        }

        collectExtensions(dir)
        return extensions.maxByOrNull { it.value }?.key ?: "unknown"
    }

    /**
     * Infer module description from name.
     */
    private fun inferModuleDescription(name: String): String {
        return when {
            name.equals("arcade", ignoreCase = true) -> "Arcade games collection"
            name.equals("src", ignoreCase = true) -> "Source code"
            name.equals("lib", ignoreCase = true) -> "Libraries and utilities"
            name.equals("test", ignoreCase = true) || name.equals("tests", ignoreCase = true) -> "Test files"
            name.equals("docs", ignoreCase = true) -> "Documentation"
            name.equals("assets", ignoreCase = true) -> "Static assets"
            name.equals("components", ignoreCase = true) -> "UI components"
            name.equals("services", ignoreCase = true) -> "Services layer"
            name.equals("models", ignoreCase = true) -> "Data models"
            name.equals("controllers", ignoreCase = true) -> "Controllers"
            name.equals("views", ignoreCase = true) -> "Views"
            name.equals("utils", ignoreCase = true) || name.equals("utilities", ignoreCase = true) -> "Utility functions"
            else -> "Module"
        }
    }

    /**
     * Infer architecture style from detected patterns.
     */
    private fun inferArchitectureStyle(patterns: List<String>): String {
        return when {
            patterns.any { it.contains("Clean Architecture") } -> "Clean Architecture"
            patterns.any { it.contains("MVC") } -> "MVC"
            patterns.any { it.contains("Component-based") } -> "Component-based"
            patterns.any { it.contains("Multi-page") } -> "Multi-page application"
            patterns.any { it.contains("Game") } -> "Game collection"
            else -> "Standard"
        }
    }
}

/**
 * Collects all directory names in the file tree. Shared by domain scoring and
 * architecture detection.
 */
internal fun collectFolderNames(node: FileNode): List<String> {
    val result = mutableListOf<String>()
    fun walk(n: FileNode) {
        if (n.isDirectory) {
            result.add(n.name)
            n.children.forEach { walk(it) }
        }
    }
    walk(node)
    return result
}
