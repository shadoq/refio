package pl.jclab.refio.core.services.context

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.nio.file.Files
import java.nio.file.Path

class ProjectInstructionsLoaderTest {

    private val loader = ProjectInstructionsLoader()

    @Test
    fun `loads refio agent md from project root`() {
        val projectRoot = Files.createTempDirectory("refio-test")
        try {
            val refioDir = projectRoot.resolve(".refio")
            Files.createDirectories(refioDir)
            Files.writeString(refioDir.resolve("agent.md"), "Use Kotlin conventions.\nPrefer data classes.")

            val result = loader.load(projectRoot)

            assertFalse(result.isEmpty)
            assertEquals(1, result.instructions.size)
            assertEquals("refio", result.instructions[0].source)
            assertTrue(result.instructions[0].content.contains("Kotlin conventions"))
            assertEquals(1, result.instructions[0].priority)
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `loads AGENTS md from project root`() {
        val projectRoot = Files.createTempDirectory("refio-test")
        try {
            Files.writeString(projectRoot.resolve("AGENTS.md"), "# Project Rules\nAlways write tests.")

            val result = loader.load(projectRoot)

            assertFalse(result.isEmpty)
            assertEquals(1, result.instructions.size)
            assertEquals("AGENTS.md", result.instructions[0].source)
            assertTrue(result.instructions[0].content.contains("Always write tests"))
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `loads both refio agent md and AGENTS md with correct priority`() {
        val projectRoot = Files.createTempDirectory("refio-test")
        try {
            val refioDir = projectRoot.resolve(".refio")
            Files.createDirectories(refioDir)
            Files.writeString(refioDir.resolve("agent.md"), "Refio specific rules")
            Files.writeString(projectRoot.resolve("AGENTS.md"), "Universal agent rules")

            val result = loader.load(projectRoot)

            assertEquals(2, result.instructions.size)
            assertEquals(1, result.instructions[0].priority) // .refio/agent.md first
            assertEquals(2, result.instructions[1].priority) // AGENTS.md second
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `loads cascading AGENTS md from subdirectory`() {
        val projectRoot = Files.createTempDirectory("refio-test")
        try {
            Files.writeString(projectRoot.resolve("AGENTS.md"), "Root rules")
            val subDir = projectRoot.resolve("src/frontend")
            Files.createDirectories(subDir)
            Files.writeString(subDir.resolve("AGENTS.md"), "Frontend specific rules")

            val result = loader.load(projectRoot, workingDir = subDir)

            assertEquals(2, result.instructions.size)
            assertTrue(result.instructions.any { it.content.contains("Root rules") })
            assertTrue(result.instructions.any { it.content.contains("Frontend specific rules") })
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `returns empty when no instruction files exist`() {
        val projectRoot = Files.createTempDirectory("refio-test")
        try {
            val result = loader.load(projectRoot)
            assertTrue(result.isEmpty)
            assertEquals(0, result.instructions.size)
            assertEquals(0, result.rules.size)
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `ignores blank instruction files`() {
        val projectRoot = Files.createTempDirectory("refio-test")
        try {
            Files.writeString(projectRoot.resolve("AGENTS.md"), "   \n  \n  ")

            val result = loader.load(projectRoot)
            assertTrue(result.isEmpty)
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `truncates files exceeding max size`() {
        val projectRoot = Files.createTempDirectory("refio-test")
        try {
            val longContent = "x".repeat(5000)
            Files.writeString(projectRoot.resolve("AGENTS.md"), longContent)

            val result = loader.load(projectRoot)

            assertFalse(result.isEmpty)
            assertTrue(result.instructions[0].content.length <= 4100) // 4000 + truncation marker
            assertTrue(result.instructions[0].content.endsWith("(truncated)"))
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `caches results within TTL`() {
        val projectRoot = Files.createTempDirectory("refio-test")
        try {
            Files.writeString(projectRoot.resolve("AGENTS.md"), "Initial content")

            val result1 = loader.load(projectRoot)
            // Modify file - should still return cached
            Files.writeString(projectRoot.resolve("AGENTS.md"), "Modified content")
            val result2 = loader.load(projectRoot)

            // Should be same object (cached)
            assertEquals(result1.instructions[0].content, result2.instructions[0].content)
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `invalidateCache clears cached results`() {
        val projectRoot = Files.createTempDirectory("refio-test")
        try {
            Files.writeString(projectRoot.resolve("AGENTS.md"), "Initial content")
            loader.load(projectRoot)

            Files.writeString(projectRoot.resolve("AGENTS.md"), "Modified content")
            loader.invalidateCache()
            val result = loader.load(projectRoot)

            assertTrue(result.instructions[0].content.contains("Modified content"))
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }
}

class ProjectInstructionsRuleParsingTest {

    private val loader = ProjectInstructionsLoader()

    @Test
    fun `parses rule with full frontmatter`() {
        val content = """
            ---
            description: Kotlin coding style
            globs: "*.kt,*.kts"
            alwaysApply: false
            ---
            Use 4-space indentation.
            Prefer val over var.
        """.trimIndent()

        val rule = loader.parseRule("kotlin-style", content)

        assertNotNull(rule)
        assertEquals("kotlin-style", rule.name)
        assertEquals("Kotlin coding style", rule.description)
        assertEquals(listOf("*.kt", "*.kts"), rule.globs)
        assertFalse(rule.alwaysApply)
        assertTrue(rule.content.contains("4-space indentation"))
    }

    @Test
    fun `parses rule with alwaysApply true`() {
        val content = """
            ---
            description: Global rules
            alwaysApply: true
            ---
            Always write English comments.
        """.trimIndent()

        val rule = loader.parseRule("global", content)

        assertNotNull(rule)
        assertTrue(rule.alwaysApply)
        assertTrue(rule.globs.isEmpty())
    }

    @Test
    fun `treats rule without frontmatter as always-apply`() {
        val content = "Simple rule without frontmatter.\nAlways applies."

        val rule = loader.parseRule("simple", content)

        assertNotNull(rule)
        assertTrue(rule.alwaysApply)
        assertEquals("simple", rule.name)
    }

    @Test
    fun `returns null for frontmatter without body`() {
        val content = """
            ---
            description: Empty body rule
            ---
        """.trimIndent()

        val rule = loader.parseRule("empty", content)
        assertNull(rule)
    }

    @Test
    fun `parses quoted globs`() {
        val content = """
            ---
            globs: "src/**/*.tsx"
            ---
            React component rules.
        """.trimIndent()

        val rule = loader.parseRule("react", content)

        assertNotNull(rule)
        assertEquals(listOf("src/**/*.tsx"), rule.globs)
    }
}

class ProjectInstructionsRuleMatchingTest {

    private val loader = ProjectInstructionsLoader()

    @Test
    fun `alwaysApply rule is always included`() {
        val rule = ProjectInstructionsLoader.ConditionalRule(
            name = "global", description = "", globs = emptyList(),
            alwaysApply = true, content = "Global rule"
        )
        assertTrue(loader.shouldIncludeRule(rule, emptyList()))
        assertTrue(loader.shouldIncludeRule(rule, listOf("test.kt")))
    }

    @Test
    fun `glob rule matches active files`() {
        val rule = ProjectInstructionsLoader.ConditionalRule(
            name = "kotlin", description = "", globs = listOf("*.kt"),
            alwaysApply = false, content = "Kotlin rule"
        )
        assertTrue(loader.shouldIncludeRule(rule, listOf("Main.kt")))
        assertFalse(loader.shouldIncludeRule(rule, listOf("Main.java")))
    }

    @Test
    fun `glob rule with no active files is not included`() {
        val rule = ProjectInstructionsLoader.ConditionalRule(
            name = "kotlin", description = "", globs = listOf("*.kt"),
            alwaysApply = false, content = "Kotlin rule"
        )
        assertFalse(loader.shouldIncludeRule(rule, emptyList()))
    }

    @Test
    fun `description-only rule is included`() {
        val rule = ProjectInstructionsLoader.ConditionalRule(
            name = "security", description = "Security review guidelines",
            globs = emptyList(), alwaysApply = false, content = "Check OWASP"
        )
        assertTrue(loader.shouldIncludeRule(rule, emptyList()))
    }

    @Test
    fun `rule with no description no globs not alwaysApply is excluded`() {
        val rule = ProjectInstructionsLoader.ConditionalRule(
            name = "manual", description = "", globs = emptyList(),
            alwaysApply = false, content = "Manual only"
        )
        assertFalse(loader.shouldIncludeRule(rule, emptyList()))
    }

    @Test
    fun `matchesAnyGlob handles double star pattern`() {
        assertTrue(loader.matchesAnyGlob("src/main/kotlin/App.kt", listOf("**/*.kt")))
        assertFalse(loader.matchesAnyGlob("src/main/kotlin/App.java", listOf("**/*.kt")))
    }

    @Test
    fun `matchesAnyGlob handles directory scoped pattern`() {
        assertTrue(loader.matchesAnyGlob("src/components/Button.tsx", listOf("src/components/**/*.tsx")))
        assertFalse(loader.matchesAnyGlob("src/services/Api.tsx", listOf("src/components/**/*.tsx")))
    }

    @Test
    fun `matchesAnyGlob handles simple extension`() {
        assertTrue(loader.matchesAnyGlob("test.py", listOf("*.py")))
        assertTrue(loader.matchesAnyGlob("src/deep/test.py", listOf("*.py")))
        assertFalse(loader.matchesAnyGlob("test.js", listOf("*.py")))
    }

    @Test
    fun `matchesAnyGlob handles multiple patterns`() {
        assertTrue(loader.matchesAnyGlob("App.kt", listOf("*.java", "*.kt")))
        assertTrue(loader.matchesAnyGlob("App.java", listOf("*.java", "*.kt")))
        assertFalse(loader.matchesAnyGlob("App.py", listOf("*.java", "*.kt")))
    }
}

class ProjectInstructionsConditionalRulesIntegrationTest {

    private val loader = ProjectInstructionsLoader()

    @Test
    fun `loads conditional rules from refio rules directory`() {
        val projectRoot = Files.createTempDirectory("refio-test")
        try {
            val rulesDir = projectRoot.resolve(".refio/rules")
            Files.createDirectories(rulesDir)

            Files.writeString(rulesDir.resolve("kotlin.md"), """
                ---
                description: Kotlin conventions
                globs: "*.kt"
                alwaysApply: false
                ---
                Use data classes for DTOs.
            """.trimIndent())

            Files.writeString(rulesDir.resolve("global.md"), """
                ---
                alwaysApply: true
                ---
                Write comments in English.
            """.trimIndent())

            // With kotlin file active — both rules should load
            val result = loader.load(projectRoot, activeFiles = listOf("Main.kt"))

            assertEquals(2, result.rules.size)
            assertTrue(result.rules.any { it.name == "kotlin" })
            assertTrue(result.rules.any { it.name == "global" })
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `skips glob rules when no matching files active`() {
        val projectRoot = Files.createTempDirectory("refio-test")
        try {
            val rulesDir = projectRoot.resolve(".refio/rules")
            Files.createDirectories(rulesDir)

            Files.writeString(rulesDir.resolve("kotlin.md"), """
                ---
                globs: "*.kt"
                ---
                Kotlin specific rule.
            """.trimIndent())

            Files.writeString(rulesDir.resolve("always.md"), """
                ---
                alwaysApply: true
                ---
                Always active rule.
            """.trimIndent())

            // With Java file active — only always rule should load
            val result = loader.load(projectRoot, activeFiles = listOf("Main.java"))

            assertEquals(1, result.rules.size)
            assertEquals("always", result.rules[0].name)
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `combines instructions and rules`() {
        val projectRoot = Files.createTempDirectory("refio-test")
        try {
            Files.writeString(projectRoot.resolve("AGENTS.md"), "Project-wide instructions")

            val rulesDir = projectRoot.resolve(".refio/rules")
            Files.createDirectories(rulesDir)
            Files.writeString(rulesDir.resolve("style.md"), """
                ---
                alwaysApply: true
                ---
                Style guidelines.
            """.trimIndent())

            val result = loader.load(projectRoot)

            assertEquals(1, result.instructions.size)
            assertEquals(1, result.rules.size)
            assertTrue(result.totalChars > 0)
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `ignores non-md files in rules directory`() {
        val projectRoot = Files.createTempDirectory("refio-test")
        try {
            val rulesDir = projectRoot.resolve(".refio/rules")
            Files.createDirectories(rulesDir)
            Files.writeString(rulesDir.resolve("rule.md"), "---\nalwaysApply: true\n---\nValid rule.")
            Files.writeString(rulesDir.resolve("notes.txt"), "This should be ignored.")

            val result = loader.load(projectRoot)

            assertEquals(1, result.rules.size)
        } finally {
            projectRoot.toFile().deleteRecursively()
        }
    }
}
