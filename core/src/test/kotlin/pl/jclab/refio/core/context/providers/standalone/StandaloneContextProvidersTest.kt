package pl.jclab.refio.core.context.providers.standalone

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import org.junit.jupiter.api.TestInstance.Lifecycle
import pl.jclab.refio.core.context.ContextProviderExtras
import pl.jclab.refio.core.context.LoadSubmenuItemsArgs
import pl.jclab.refio.core.context.ProviderType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TestInstance(Lifecycle.PER_CLASS)
class StandaloneContextProvidersTest {

    private lateinit var tempDir: Path

    @BeforeAll
    fun setup() {
        tempDir = Files.createTempDirectory("refio-standalone-ctx-test-")
        // Create test structure
        Files.createDirectories(tempDir.resolve("src/main"))
        Files.createDirectories(tempDir.resolve("src/test"))
        Files.createDirectories(tempDir.resolve("docs"))
        Files.writeString(tempDir.resolve("src/main/App.kt"), "fun main() { println(\"hello\") }")
        Files.writeString(tempDir.resolve("src/main/Utils.kt"), "fun helper() = 42")
        Files.writeString(tempDir.resolve("src/test/AppTest.kt"), "fun testMain() { }")
        Files.writeString(tempDir.resolve("docs/README.md"), "# Project\nThis is a test project.")
        Files.writeString(tempDir.resolve("build.gradle"), "plugins { id 'java' }")
        // Init git repo
        ProcessBuilder("git", "init").directory(tempDir.toFile()).start().waitFor()
        ProcessBuilder("git", "add", ".").directory(tempDir.toFile()).start().waitFor()
        ProcessBuilder("git", "commit", "-m", "initial commit").directory(tempDir.toFile())
            .also { it.environment()["GIT_AUTHOR_NAME"] = "Test"; it.environment()["GIT_AUTHOR_EMAIL"] = "test@test.com"; it.environment()["GIT_COMMITTER_NAME"] = "Test"; it.environment()["GIT_COMMITTER_EMAIL"] = "test@test.com" }
            .start().waitFor()
        // Make a change for diff
        Files.writeString(tempDir.resolve("src/main/App.kt"), "fun main() { println(\"modified\") }")
    }

    @AfterAll
    fun teardown() {
        tempDir.toFile().deleteRecursively()
    }

    private fun extras() = ContextProviderExtras(workspacePath = tempDir.toRealPath().toString())
    private fun submenuArgs() = LoadSubmenuItemsArgs(project = tempDir.toRealPath().toString())

    // ========== FileContextProvider ==========

    @Test
    fun `FileContextProvider should have correct metadata`() {
        val provider = StandaloneFileContextProvider()
        assertEquals("file", provider.description.title)
        assertEquals(ProviderType.SUBMENU, provider.description.type)
    }

    @Test
    fun `FileContextProvider should list files in submenu`() = runBlocking {
        val provider = StandaloneFileContextProvider()
        val items = provider.loadSubmenuItems(submenuArgs())
        assertTrue(items.isNotEmpty(), "Should find project files")
        assertTrue(items.any { it.title == "App.kt" }, "Should find App.kt")
    }

    @Test
    fun `FileContextProvider should search files by pattern`() = runBlocking {
        val provider = StandaloneFileContextProvider()
        val items = provider.loadSubmenuItems(LoadSubmenuItemsArgs(query = "Utils", project = tempDir.toRealPath().toString()))
        assertTrue(items.any { it.title == "Utils.kt" }, "Should find Utils.kt by pattern")
    }

    @Test
    fun `FileContextProvider should read file content`() = runBlocking {
        val provider = StandaloneFileContextProvider()
        val items = provider.getContextItems("src/main/App.kt", extras())
        assertEquals(1, items.size)
        assertTrue(items[0].content.contains("println"))
        assertEquals("App.kt", items[0].name)
    }

    @Test
    fun `FileContextProvider should reject non-existent file`() = runBlocking {
        val provider = StandaloneFileContextProvider()
        val items = provider.getContextItems("nonexistent.txt", extras())
        assertTrue(items.isEmpty())
    }

    // ========== FolderContextProvider ==========

    @Test
    fun `FolderContextProvider should list top-level folders`() = runBlocking {
        val provider = StandaloneFolderContextProvider()
        val items = provider.loadSubmenuItems(submenuArgs())
        assertTrue(items.isNotEmpty())
        assertTrue(items.any { it.title == "." }, "Should include project root")
        assertTrue(items.any { it.title == "src" }, "Should find src folder")
    }

    @Test
    fun `FolderContextProvider should return directory tree`() = runBlocking {
        val provider = StandaloneFolderContextProvider()
        val items = provider.getContextItems("src", extras())
        assertEquals(1, items.size)
        assertTrue(items[0].content.contains("main"))
        assertTrue(items[0].content.contains("test"))
    }

    // ========== GitDiffContextProvider ==========

    @Test
    fun `GitDiffContextProvider should show uncommitted changes`() = runBlocking {
        val provider = StandaloneGitDiffContextProvider()
        val items = provider.getContextItems("", extras())
        assertEquals(1, items.size)
        assertEquals("Git Diff", items[0].name)
        // We modified App.kt, so diff should mention it
        assertTrue(items[0].content.contains("App.kt") || items[0].content.contains("modified") || items[0].content.contains("diff"),
            "Should show App.kt change in diff")
    }

    // ========== GitCommitContextProvider ==========

    @Test
    fun `GitCommitContextProvider should find initial commit`() = runBlocking {
        val provider = StandaloneGitCommitContextProvider()
        val items = provider.getContextItems("initial", extras())
        assertTrue(items.isNotEmpty(), "Should find commit by message keyword")
        assertTrue(items[0].content.contains("initial commit"))
    }

    @Test
    fun `GitCommitContextProvider should handle not found`() = runBlocking {
        val provider = StandaloneGitCommitContextProvider()
        val items = provider.getContextItems("nonexistent_commit_xyz123", extras())
        assertTrue(items.isNotEmpty())
        assertTrue(items[0].content.contains("not found") || items[0].description.contains("Error"))
    }

    // ========== GrepSearchContextProvider ==========

    @Test
    fun `GrepSearchContextProvider should find pattern in files`() = runBlocking {
        val provider = StandaloneGrepSearchContextProvider()
        val items = provider.getContextItems("println", extras())
        assertEquals(1, items.size)
        assertTrue(items[0].content.contains("println"))
        assertTrue(items[0].content.contains("App.kt"))
    }

    @Test
    fun `GrepSearchContextProvider should return no matches for absent pattern`() = runBlocking {
        val provider = StandaloneGrepSearchContextProvider()
        val items = provider.getContextItems("zzz_nonexistent_pattern_xyz", extras())
        assertEquals(1, items.size)
        assertTrue(items[0].content.contains("No matches"))
    }

    @Test
    fun `GrepSearchContextProvider should handle regex`() = runBlocking {
        val provider = StandaloneGrepSearchContextProvider()
        val items = provider.getContextItems("fun \\w+\\(\\)", extras())
        assertEquals(1, items.size)
        assertTrue(items[0].content.contains("matches") || items[0].content.contains("fun"))
    }

    // ========== CodebaseContextProvider ==========

    @Test
    fun `CodebaseContextProvider should have correct metadata`() {
        val provider = StandaloneCodebaseContextProvider()
        assertEquals("codebase", provider.description.title)
        assertEquals(ProviderType.QUERY, provider.description.type)
    }

    // Note: Full codebase search requires RAG index which is expensive to set up in tests.
    // Testing metadata only.

    // ========== DocsContextProvider ==========

    @Test
    fun `DocsContextProvider should have correct metadata`() {
        val provider = StandaloneDocsContextProvider()
        assertEquals("docs", provider.description.title)
        assertEquals(ProviderType.SUBMENU, provider.description.type)
    }

    // Note: Full docs search requires RAG index and documentation sources.
    // Testing metadata only.
}
