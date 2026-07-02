package pl.jclab.refio.core.services

/**
 * Scores a project file tree against known domains (programming, documentation,
 * creative, research, business, educational) and picks the primary domain.
 */
internal object ProjectDomainScorer {

    /**
     * Analyze project domain (programming, documentation, creative, etc.)
     */
    fun analyzeProjectDomain(fileTree: FileNode, structure: StructureInfo): DomainAnalysis {
        val fileTypes = structure.fileTypes
        val folderStructure = collectFolderNames(fileTree)

        val domainScores = mapOf(
            "Programming" to scoreProgrammingProject(fileTypes, folderStructure),
            "Documentation" to scoreDocumentationProject(fileTypes, folderStructure),
            "Creative" to scoreCreativeProject(fileTypes, folderStructure),
            "Research" to scoreResearchProject(fileTypes, folderStructure),
            "Business" to scoreBusinessProject(fileTypes, folderStructure),
            "Educational" to scoreEducationalProject(fileTypes, folderStructure)
        )

        val primaryDomain = domainScores.maxByOrNull { it.value }?.key ?: "unknown"
        val confidence = domainScores[primaryDomain] ?: 0.0

        return DomainAnalysis(
            primaryDomain = primaryDomain,
            confidenceScore = confidence,
            domainScores = domainScores
        )
    }

    private fun scoreProgrammingProject(fileTypes: Map<String, Int>, folderStructure: List<String>): Double {
        var score = 0.0

        val codeExtensions = listOf(".kt", ".java", ".py", ".js", ".ts", ".tsx", ".jsx", ".cpp", ".c", ".cs", ".go", ".rs", ".rb", ".php", ".swift")
        val totalCodeFiles = codeExtensions.sumOf { fileTypes[it] ?: 0 }

        // Higher weight for code files (was 0.1, max 5.0)
        score += minOf(totalCodeFiles * 0.4, 8.0)

        // Bonus for having a dominant language (consistency)
        val (_, primaryCount) = detectPrimaryLanguage(fileTypes)
        if (primaryCount > 0 && totalCodeFiles > 0) {
            val dominanceRatio = primaryCount.toDouble() / totalCodeFiles
            if (dominanceRatio > 0.5) {
                score += 1.5  // Bonus for technological consistency
            }
        }

        // Programming folder structure
        val progFolders = listOf("src", "lib", "app", "components", "modules", "tests", "test", "spec", "main", "kotlin", "java", "python")
        val folderMatches = folderStructure.count { folder ->
            progFolders.any { it.equals(folder, ignoreCase = true) }
        }
        score += folderMatches * 0.4

        return minOf(score, 10.0)
    }

    private fun scoreDocumentationProject(fileTypes: Map<String, Int>, folderStructure: List<String>): Double {
        var score = 0.0

        val docExtensions = listOf(".md", ".rst", ".txt", ".pdf")
        val docFiles = docExtensions.sumOf { fileTypes[it] ?: 0 }
        score += minOf(docFiles * 0.3, 6.0)

        val docFolders = listOf("docs", "documentation", "wiki", "manual")
        val folderMatches = folderStructure.count { folder ->
            docFolders.any { it in folder.lowercase() }
        }
        score += folderMatches * 1.5

        return minOf(score, 10.0)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun scoreCreativeProject(fileTypes: Map<String, Int>, _folderStructure: List<String>): Double {
        var score = 0.0

        val creativeExtensions = listOf(".jpg", ".png", ".gif", ".svg", ".mp3", ".mp4", ".blend", ".psd")
        val creativeFiles = creativeExtensions.sumOf { fileTypes[it] ?: 0 }
        score += minOf(creativeFiles * 0.4, 5.0)

        return minOf(score, 10.0)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun scoreResearchProject(fileTypes: Map<String, Int>, _folderStructure: List<String>): Double {
        var score = 0.0

        val researchExtensions = listOf(".csv", ".xlsx", ".json", ".ipynb", ".r")
        val researchFiles = researchExtensions.sumOf { fileTypes[it] ?: 0 }
        score += minOf(researchFiles * 0.5, 5.0)

        return minOf(score, 10.0)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun scoreBusinessProject(fileTypes: Map<String, Int>, _folderStructure: List<String>): Double {
        var score = 0.0

        val businessExtensions = listOf(".xlsx", ".docx", ".pptx", ".pdf")
        val businessFiles = businessExtensions.sumOf { fileTypes[it] ?: 0 }
        score += minOf(businessFiles * 0.3, 4.0)

        return minOf(score, 10.0)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun scoreEducationalProject(_fileTypes: Map<String, Int>, folderStructure: List<String>): Double {
        var score = 0.0

        val eduFolders = listOf("lessons", "courses", "modules", "exercises")
        val folderMatches = folderStructure.count { folder ->
            eduFolders.any { it in folder.lowercase() }
        }
        score += folderMatches * 1.5

        return minOf(score, 10.0)
    }
}
