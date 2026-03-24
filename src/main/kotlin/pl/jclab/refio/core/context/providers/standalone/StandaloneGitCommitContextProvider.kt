package pl.jclab.refio.core.context.providers.standalone

import pl.jclab.refio.core.context.*
import pl.jclab.refio.core.logging.dualLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val logger = dualLogger("StandaloneGitCommitContextProvider")

/**
 * Standalone GitCommitContextProvider — identical to original but
 * uses workspacePath directly (original already uses git CLI,
 * only needs project.basePath fallback removed from loadSubmenuItems).
 */
class StandaloneGitCommitContextProvider : BaseContextProvider() {

    override val description = ContextProviderDescription(
        title = "commit",
        displayTitle = "commit",
        description = "Specific git commit details",
        type = ProviderType.QUERY,
        icon = "📝"
    )

    override suspend fun getContextItems(query: String, extras: ContextProviderExtras): List<ContextItem> = withContext(Dispatchers.IO) {
        val commitHash = query.trim()
        if (commitHash.isEmpty()) return@withContext emptyList()

        val projectPath = extras.workspacePath
        if (projectPath.isEmpty()) return@withContext listOf(errorItem("No project path found"))

        val workingDir = File(projectPath)
        if (!workingDir.exists()) return@withContext listOf(errorItem("Project directory does not exist: $projectPath"))

        val gitCheck = executeGit(workingDir, listOf("rev-parse", "--git-dir"))
        if (gitCheck.isFailure) return@withContext listOf(errorItem("Not a git repository: $projectPath"))

        // Try direct hash resolve
        val resolvedHash = resolveHash(workingDir, commitHash)
        if (resolvedHash != null) {
            val details = fetchDetails(workingDir, resolvedHash)
            if (details != null) return@withContext listOf(buildItem(details))
        }

        // Search by message
        val matches = searchMessages(workingDir, commitHash)
        if (matches.isEmpty()) return@withContext listOf(errorItem("Commit not found: $commitHash"))

        matches.mapNotNull { fetchDetails(workingDir, it.first) }.map { buildItem(it) }
    }

    private fun resolveHash(dir: File, input: String): String? {
        val r = executeGit(dir, listOf("rev-parse", "--verify", "${input}^{commit}"))
        return if (r.isSuccess) r.getOrNull()?.trim() else null
    }

    private fun searchMessages(dir: File, query: String): List<Pair<String, String>> {
        val r = executeGit(dir, listOf("log", "--all", "--max-count=5", "--regexp-ignore-case", "--format=%H|%h|%s", "--grep=$query"))
        val output = r.getOrNull()?.trim() ?: return emptyList()
        return output.lines().mapNotNull { line ->
            val parts = line.split("|", limit = 3)
            if (parts.size >= 2) parts[0] to parts[1] else null
        }
    }

    private data class CommitDetails(val fullHash: String, val shortHash: String, val author: String, val email: String, val date: String, val message: String, val stat: String)

    private fun fetchDetails(dir: File, hash: String): CommitDetails? {
        val show = executeGit(dir, listOf("show", "--no-patch", "--format=format:%H%n%h%n%an%n%ae%n%ai%n%B", hash))
        val lines = show.getOrNull()?.split("\n") ?: return null
        if (lines.size < 6) return null
        val stat = executeGit(dir, listOf("show", "--stat", "--format=", hash)).getOrDefault("")
        return CommitDetails(lines[0], lines[1], lines[2], lines[3], lines[4], lines.drop(5).joinToString("\n"), stat)
    }

    private fun buildItem(d: CommitDetails) = ContextItem(
        description = "Commit: ${d.shortHash}",
        content = "Commit: ${d.fullHash}\nAuthor: ${d.author} <${d.email}>\nDate: ${d.date}\n\n${d.message.trim()}\n\nChanges:\n${d.stat.trim().ifEmpty { "(no changes)" }}",
        name = "Commit ${d.shortHash}",
        uri = ContextUri(type = "git-commit", value = d.fullHash)
    )

    private fun errorItem(msg: String) = ContextItem(description = "Git Error", content = msg, name = "Error", uri = ContextUri(type = "error", value = "git"))

    private fun executeGit(dir: File, args: List<String>): Result<String> = try {
        val p = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (p.waitFor() == 0) Result.success(out) else Result.failure(Exception("git exit ${p.exitValue()}"))
    } catch (e: Exception) { Result.failure(e) }
}
