package pl.jclab.refio.core.services.turn.providers

import pl.jclab.refio.core.services.turn.PromptBuildContext
import pl.jclab.refio.core.services.turn.PromptSectionProvider
import java.io.File
import java.nio.file.Path
import java.util.Locale

/**
 * Injects a <system_environment> block into the system prompt describing the host
 * the agent is running on:
 *
 *   - operating system (name, arch, version)
 *   - default shell and path separator
 *   - working directory
 *   - which commonly-used command-line tools are actually on PATH
 *
 * Why this exists: the agent was repeatedly picking shell commands that don't
 * work on the host (e.g. `ls` on Windows, `find` flags that only exist on GNU
 * coreutils). Giving the model explicit environment facts lets it pick the
 * correct invocation instead of guessing.
 *
 * This block is built once per construction and cached — the host environment
 * does not change during a session. [projectRoot] is the only session-variable
 * piece and it's resolved per call.
 */
class SystemEnvironmentPromptProvider(
    private val projectRoot: Path?
) : PromptSectionProvider {

    // Cached because OS/PATH probes are slightly expensive and never change at runtime.
    private val cachedBlock: String by lazy { buildBlock() }

    override suspend fun build(context: PromptBuildContext): String? = cachedBlock

    private fun buildBlock(): String {
        val osName = System.getProperty("os.name") ?: "unknown"
        val osVersion = System.getProperty("os.version") ?: "unknown"
        val osArch = System.getProperty("os.arch") ?: "unknown"
        val isWindows = osName.lowercase(Locale.ROOT).contains("windows")
        val isMac = osName.lowercase(Locale.ROOT).contains("mac")
        val shell = detectShell(isWindows)
        val pathSep = File.pathSeparator
        val fileSep = File.separator
        val cwd = projectRoot?.toAbsolutePath()?.toString() ?: System.getProperty("user.dir")
        val home = System.getProperty("user.home")
        val platformHint = when {
            isWindows -> buildString {
                append("**CRITICAL: This is a Windows PowerShell environment.**\n")
                append("SHELL SYNTAX RULES (violations will cause command failures):\n")
                append("- Do NOT use `&&` to chain commands — use `;` in PowerShell\n")
                append("- Do NOT use heredocs (`<<'EOF'`) — not supported in PowerShell\n")
                append("- Do NOT use `/dev/null` — use `NUL` or `Out-Null`\n")
                append("- Do NOT pass complex Python one-liners via `python -c \"...\"` — ")
                append("PowerShell mangles quotes. Write a .py file and run it instead.\n")
                append("- Use `where` not `which` to locate binaries\n")
                append("- Paths use backslashes: `dir\\file.py` not `dir/file.py`\n")
                append("PREFERRED PATTERN: Write scripts to files, then execute them.")
            }
            isMac -> "macOS — BSD userland (not GNU). Some flags differ from Linux (e.g. `sed -i ''`, `find` predicates). `xargs -r` is unavailable."
            else -> "Linux — GNU userland. Standard POSIX + GNU extensions available."
        }
        val toolStatus = probeTools(isWindows)

        return buildString {
            append("<system_environment>\n")
            append("os: $osName $osVersion ($osArch)\n")
            append("shell: $shell\n")
            append("working_directory: $cwd\n")
            append("user_home: $home\n")
            append("path_separator: \"$pathSep\"\n")
            append("file_separator: \"$fileSep\"\n")
            append("available_tools:\n")
            for ((tool, available) in toolStatus) {
                append("  - $tool: ${if (available) "yes" else "no"}\n")
            }

            append("IMPORTANT: Pick shell commands and flags that match the OS above. ")
            append("Do NOT assume a tool exists unless it is listed as `yes` in available_tools. ")
            append("When uncertain, prefer cross-platform alternatives or use Refio tools instead of raw shell.\n")

            append("\n\n<platform_rules>\n")
            append(platformHint)
            append("\n</platform_rules>\n")

            append("</system_environment>")
        }
    }

    private fun detectShell(isWindows: Boolean): String {
        // Prefer explicit env vars over heuristics.
        System.getenv("SHELL")?.takeIf { it.isNotBlank() }?.let { return it }
        if (isWindows) {
            if (!System.getenv("PSModulePath").isNullOrBlank()) return "powershell"
            return "cmd.exe"
        }
        return "/bin/sh"
    }

    private fun probeTools(isWindows: Boolean): List<Pair<String, Boolean>> {
        // Probe common tools the agent tends to invoke. Order by usefulness.
        val probes = listOf(
            "git", "node", "npm", "pnpm", "yarn",
            "python", "python3", "pip",
            "java", "mvn", "gradle",
            "go", "cargo", "rustc",
            "docker", "kubectl",
            "rg", "grep", "fd", "find",
            "curl", "wget",
            "make", "cmake",
            "jq", "sed", "awk"
        )
        return probes.map { it to isOnPath(it, isWindows) }
    }

    private fun isOnPath(command: String, isWindows: Boolean): Boolean {
        val pathEnv = System.getenv("PATH") ?: return false
        val exts: List<String> = if (isWindows) {
            (System.getenv("PATHEXT") ?: ".EXE;.BAT;.CMD")
                .split(File.pathSeparatorChar)
                .map { it.lowercase(Locale.ROOT) }
        } else {
            listOf("")
        }
        for (dir in pathEnv.split(File.pathSeparatorChar)) {
            if (dir.isBlank()) continue
            for (ext in exts) {
                val candidate = File(dir, command + ext)
                if (candidate.isFile && (isWindows || candidate.canExecute())) {
                    return true
                }
            }
        }
        return false
    }
}
