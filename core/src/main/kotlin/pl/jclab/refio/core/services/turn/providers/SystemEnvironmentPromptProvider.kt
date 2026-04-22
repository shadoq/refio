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
        val cwd = projectRoot?.toAbsolutePath()?.toString() ?: System.getProperty("user.dir")

        // Split probed tools into have/lack lists. A single "have: a,b,c" line is ~4×
        // cheaper in tokens than a per-tool "a: yes\nb: no\n..." table that previously
        // cost ~600 tokens by itself — and a flat list is what the model actually needs
        // (it wants to know what it can invoke, not each tool's boolean individually).
        val toolStatus = probeTools(isWindows)
        val have = toolStatus.filter { it.second }.map { it.first }
        val lack = toolStatus.filter { !it.second }.map { it.first }

        return buildString {
            append("<system_environment>\n")
            append("os: $osName $osVersion ($osArch); shell: $shell; cwd: $cwd\n")
            if (have.isNotEmpty()) append("have: ${have.joinToString(",")}\n")
            if (lack.isNotEmpty()) append("lack: ${lack.joinToString(",")}\n")
            append("Prefer Refio tools over raw shell; if a command is not in `have`, do not assume it is installed.\n")

            // Platform-specific rules: only emit when non-trivial. Linux/GNU defaults
            // don't need a block — most shell idioms work out of the box. Mac BSD and
            // Windows PowerShell have real divergences that burn the agent if omitted,
            // so those sections stay.
            when {
                isWindows -> {
                    append("<platform_rules>PowerShell: use `;` not `&&` to chain; no heredocs; `NUL` not `/dev/null`; `where` not `which`; backslash paths. For non-trivial Python, write a .py file rather than `python -c \"...\"` (quote mangling).</platform_rules>\n")
                }
                isMac -> {
                    append("<platform_rules>macOS BSD userland: `sed -i ''` (not `-i`), `xargs -r` unavailable, `find` predicate set differs from GNU.</platform_rules>\n")
                }
                // Linux/other: omit — POSIX + GNU defaults are the model's baseline.
            }

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
