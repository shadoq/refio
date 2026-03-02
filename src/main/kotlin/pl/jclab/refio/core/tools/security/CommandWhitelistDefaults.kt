package pl.jclab.refio.core.tools.security

object CommandWhitelistDefaults {
    val DEFAULT_COMMANDS = listOf(
        AllowedCommand(
            program = "gradle",
            aliases = listOf("gradlew", "gradlew.bat", "./gradlew"),
            description = "Gradle build system",
            blockedFlags = listOf("--init-script")
        ),
        AllowedCommand(
            program = "mvn",
            aliases = listOf("mvnw", "mvnw.cmd", "./mvnw"),
            description = "Maven build system"
        ),

        AllowedCommand(
            program = "npm",
            description = "Node.js package manager",
            allowedSubcommands = listOf("run", "test", "build", "list", "ls", "outdated", "info", "ci"),
            blockedSubcommands = listOf("install", "uninstall", "publish", "adduser", "login")
        ),
        AllowedCommand(
            program = "yarn",
            allowedSubcommands = listOf("run", "test", "build", "list", "info", "why"),
            blockedSubcommands = listOf("add", "remove", "publish")
        ),
        AllowedCommand(
            program = "pnpm",
            allowedSubcommands = listOf("run", "test", "build", "list", "why"),
            blockedSubcommands = listOf("add", "remove", "publish")
        ),
        AllowedCommand(
            program = "bun",
            allowedSubcommands = listOf("run", "test", "build"),
            blockedSubcommands = listOf("add", "remove", "publish")
        ),

        AllowedCommand(
            program = "git",
            description = "Git version control",
            allowedSubcommands = listOf(
                "status", "log", "diff", "show", "branch", "tag",
                "blame", "shortlog", "describe", "rev-parse",
                "ls-files", "ls-tree", "cat-file",
                "stash", "stash list",
                "add", "commit", "checkout", "switch", "merge", "rebase",
                "fetch", "pull"
            ),
            blockedSubcommands = listOf(
                "push",
                "remote",
                "reset --hard",
                "clean -f",
                "filter-branch",
                "reflog expire"
            ),
            blockedFlags = listOf("--force", "-f", "--no-verify"),
            blockedArgPatterns = listOf(".*\\.env$", ".*credentials.*")
        ),

        AllowedCommand(
            program = "python",
            aliases = listOf("python3", "py"),
            description = "Python interpreter",
            blockedFlags = listOf("-c"),
            blockedArgPatterns = listOf(".*http.*", ".*socket.*")
        ),
        AllowedCommand(
            program = "node",
            description = "Node.js runtime",
            blockedFlags = listOf("-e", "--eval")
        ),
        AllowedCommand(
            program = "java",
            description = "Java runtime"
        ),
        AllowedCommand(
            program = "kotlin",
            aliases = listOf("kotlinc"),
            description = "Kotlin compiler"
        ),

        AllowedCommand(
            program = "cat",
            aliases = listOf("type", "get-content"),
            description = "Print file content",
            blockedArgPatterns = listOf(".*\\.env$", ".*/\\.ssh/.*", ".*credentials.*", ".*/etc/shadow.*")
        ),
        AllowedCommand(
            program = "ls",
            aliases = listOf("dir", "gci", "get-childitem"),
            description = "List directory"
        ),
        AllowedCommand(
            program = "pwd",
            aliases = listOf("get-location"),
            description = "Print working directory"
        ),
        AllowedCommand(
            program = "echo",
            aliases = listOf("write-output"),
            description = "Print text output"
        ),
        AllowedCommand(
            program = "clear",
            aliases = listOf("cls"),
            description = "Clear terminal screen"
        ),
        AllowedCommand(
            program = "date",
            aliases = listOf("get-date"),
            description = "Print current date and time"
        ),
        AllowedCommand(
            program = "whoami",
            description = "Print current user"
        ),
        AllowedCommand(
            program = "hostname",
            description = "Print host name"
        ),
        AllowedCommand(
            program = "uname",
            description = "Print OS information"
        ),
        AllowedCommand(
            program = "id",
            description = "Print user identity"
        ),
        AllowedCommand(
            program = "which",
            aliases = listOf("where.exe"),
            description = "Locate executable path"
        ),
        AllowedCommand(
            program = "get-command",
            aliases = listOf("gcm"),
            description = "Resolve command metadata"
        ),
        AllowedCommand(
            program = "find",
            description = "Find files",
            blockedFlags = listOf("-exec", "-delete", "-execdir")
        ),
        AllowedCommand(
            program = "grep",
            aliases = listOf("rg", "ag"),
            description = "Search in files"
        ),
        AllowedCommand(
            program = "wc",
            description = "Word/line count"
        ),
        AllowedCommand(
            program = "head",
            description = "Print first lines"
        ),
        AllowedCommand(
            program = "tail",
            description = "Print last lines"
        ),
        AllowedCommand(
            program = "sort",
            description = "Sort lines"
        ),
        AllowedCommand(
            program = "uniq",
            description = "Filter duplicates"
        ),
        AllowedCommand(
            program = "diff",
            description = "Compare files"
        ),

        AllowedCommand(
            program = "pytest",
            description = "Python test runner"
        ),
        AllowedCommand(
            program = "jest",
            description = "JavaScript test runner"
        ),
        AllowedCommand(
            program = "cargo",
            description = "Rust package manager",
            allowedSubcommands = listOf("build", "test", "check", "clippy", "fmt", "doc", "run"),
            blockedSubcommands = listOf("publish", "install", "uninstall")
        ),
        AllowedCommand(
            program = "go",
            description = "Go toolchain",
            allowedSubcommands = listOf("build", "test", "vet", "fmt", "run", "mod tidy", "mod verify"),
            blockedSubcommands = listOf("install")
        ),
        AllowedCommand(
            program = "dotnet",
            description = ".NET CLI",
            allowedSubcommands = listOf("build", "test", "run", "clean", "restore"),
            blockedSubcommands = listOf("publish", "nuget push")
        ),

        AllowedCommand(
            program = "docker",
            description = "Docker CLI (read-only)",
            allowedSubcommands = listOf("ps", "images", "logs", "inspect", "stats", "top", "version"),
            blockedSubcommands = listOf("rm", "rmi", "prune", "exec", "run", "build", "push", "pull")
        )
    )

    val DEFAULT_BLOCKED_PATTERNS = listOf(
        "\\|\\s*(sh|bash|zsh|powershell|cmd)\\b",
        "\\|\\s*eval\\b",
        "\\$\\(",
        "`[^`]+`",
        ">\\s*/dev/",
        ">(>)?\\s*/etc/",
        "\\brm\\s+-r",
        "\\bmkfs\\b",
        "\\bdd\\b.*of=",
        "\\bcurl\\b.*\\|",
        "\\bwget\\b.*\\|",
        ":\\(\\)\\s*\\{"
    )
}
