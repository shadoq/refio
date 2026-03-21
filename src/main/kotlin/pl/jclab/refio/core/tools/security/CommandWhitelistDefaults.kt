package pl.jclab.refio.core.tools.security

object CommandWhitelistDefaults {
    val DEFAULT_COMMANDS = listOf(
        // ── Build Systems ──────────────────────────────────────────────
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
            program = "make",
            aliases = listOf("gmake"),
            description = "Make build system"
        ),
        AllowedCommand(
            program = "cmake",
            description = "CMake build system"
        ),
        AllowedCommand(
            program = "sbt",
            description = "Scala Build Tool"
        ),
        AllowedCommand(
            program = "ant",
            description = "Apache Ant build system"
        ),
        AllowedCommand(
            program = "bazel",
            description = "Bazel build system",
            blockedSubcommands = listOf("clean --expunge")
        ),
        AllowedCommand(
            program = "msbuild",
            description = "Microsoft Build Engine"
        ),

        // ── JavaScript/TypeScript Ecosystem ────────────────────────────
        AllowedCommand(
            program = "npm",
            aliases = listOf("npx"),
            description = "Node.js package manager",
            blockedSubcommands = listOf("publish", "adduser", "login", "token"),
            blockedFlags = listOf("--global", "-g")
        ),
        AllowedCommand(
            program = "yarn",
            description = "Yarn package manager",
            blockedSubcommands = listOf("publish", "login", "npm audit"),
            blockedFlags = listOf("--global", "-g")
        ),
        AllowedCommand(
            program = "pnpm",
            description = "pnpm package manager",
            blockedSubcommands = listOf("publish", "login"),
            blockedFlags = listOf("--global", "-g")
        ),
        AllowedCommand(
            program = "bun",
            description = "Bun runtime & package manager",
            blockedSubcommands = listOf("publish")
        ),
        AllowedCommand(
            program = "node",
            description = "Node.js runtime",
            blockedFlags = listOf("-e", "--eval")
        ),
        AllowedCommand(
            program = "tsx",
            description = "TypeScript execute"
        ),
        AllowedCommand(
            program = "ts-node",
            description = "TypeScript Node.js"
        ),
        AllowedCommand(
            program = "tsc",
            description = "TypeScript compiler"
        ),
        AllowedCommand(
            program = "eslint",
            description = "JavaScript/TypeScript linter"
        ),
        AllowedCommand(
            program = "prettier",
            description = "Code formatter"
        ),
        AllowedCommand(
            program = "jest",
            description = "JavaScript test runner"
        ),
        AllowedCommand(
            program = "vitest",
            description = "Vite test runner"
        ),
        AllowedCommand(
            program = "mocha",
            description = "JavaScript test framework"
        ),
        AllowedCommand(
            program = "playwright",
            description = "E2E test runner"
        ),
        AllowedCommand(
            program = "cypress",
            description = "E2E test runner"
        ),
        AllowedCommand(
            program = "webpack",
            description = "Module bundler"
        ),
        AllowedCommand(
            program = "vite",
            description = "Frontend build tool"
        ),
        AllowedCommand(
            program = "esbuild",
            description = "JavaScript bundler"
        ),
        AllowedCommand(
            program = "rollup",
            description = "JavaScript module bundler"
        ),
        AllowedCommand(
            program = "next",
            description = "Next.js CLI"
        ),

        // ── Git ────────────────────────────────────────────────────────
        AllowedCommand(
            program = "git",
            description = "Git version control",
            blockedSubcommands = listOf(
                "reset --hard",
                "clean -f", "clean -fd", "clean -fdx",
                "filter-branch",
                "reflog expire"
            ),
            blockedFlags = listOf("--no-verify"),
            blockedArgPatterns = listOf(".*\\.env$", ".*credentials.*")
        ),

        // ── Python Ecosystem ───────────────────────────────────────────
        AllowedCommand(
            program = "python",
            aliases = listOf("python3", "py"),
            description = "Python interpreter",
            blockedFlags = listOf("-c"),
            blockedArgPatterns = listOf(".*socket\\..*")
        ),
        AllowedCommand(
            program = "pip",
            aliases = listOf("pip3"),
            description = "Python package manager",
            blockedSubcommands = listOf("install --user", "install --system"),
            blockedArgPatterns = listOf(".*http://.*")
        ),
        AllowedCommand(
            program = "poetry",
            description = "Python dependency manager",
            blockedSubcommands = listOf("publish")
        ),
        AllowedCommand(
            program = "pipenv",
            description = "Python virtualenv manager"
        ),
        AllowedCommand(
            program = "uv",
            description = "Python package manager (fast)",
            blockedSubcommands = listOf("publish")
        ),
        AllowedCommand(
            program = "pytest",
            description = "Python test runner"
        ),
        AllowedCommand(
            program = "mypy",
            description = "Python type checker"
        ),
        AllowedCommand(
            program = "ruff",
            description = "Python linter"
        ),
        AllowedCommand(
            program = "black",
            description = "Python formatter"
        ),
        AllowedCommand(
            program = "flake8",
            description = "Python linter"
        ),
        AllowedCommand(
            program = "pylint",
            description = "Python linter"
        ),
        AllowedCommand(
            program = "django-admin",
            aliases = listOf("manage.py"),
            description = "Django management"
        ),
        AllowedCommand(
            program = "flask",
            description = "Flask CLI"
        ),

        // ── JVM Ecosystem ──────────────────────────────────────────────
        AllowedCommand(
            program = "java",
            description = "Java runtime"
        ),
        AllowedCommand(
            program = "javac",
            description = "Java compiler"
        ),
        AllowedCommand(
            program = "kotlin",
            aliases = listOf("kotlinc"),
            description = "Kotlin compiler"
        ),
        AllowedCommand(
            program = "scala",
            aliases = listOf("scalac"),
            description = "Scala compiler"
        ),

        // ── Rust Ecosystem ─────────────────────────────────────────────
        AllowedCommand(
            program = "cargo",
            description = "Rust package manager",
            blockedSubcommands = listOf("publish", "install", "uninstall")
        ),
        AllowedCommand(
            program = "rustc",
            description = "Rust compiler"
        ),
        AllowedCommand(
            program = "rustup",
            description = "Rust toolchain manager",
            allowedSubcommands = listOf("show", "which", "target list", "toolchain list", "component list")
        ),

        // ── Go Ecosystem ───────────────────────────────────────────────
        AllowedCommand(
            program = "go",
            description = "Go toolchain",
            blockedSubcommands = listOf("install")
        ),
        AllowedCommand(
            program = "golangci-lint",
            description = "Go linter"
        ),

        // ── .NET Ecosystem ─────────────────────────────────────────────
        AllowedCommand(
            program = "dotnet",
            description = ".NET CLI",
            blockedSubcommands = listOf("publish", "nuget push")
        ),

        // ── Ruby Ecosystem ─────────────────────────────────────────────
        AllowedCommand(
            program = "ruby",
            description = "Ruby interpreter",
            blockedFlags = listOf("-e")
        ),
        AllowedCommand(
            program = "bundle",
            aliases = listOf("bundler"),
            description = "Ruby dependency manager"
        ),
        AllowedCommand(
            program = "rake",
            description = "Ruby Make"
        ),
        AllowedCommand(
            program = "rails",
            description = "Rails CLI"
        ),
        AllowedCommand(
            program = "rspec",
            description = "Ruby test runner"
        ),

        // ── PHP Ecosystem ──────────────────────────────────────────────
        AllowedCommand(
            program = "php",
            description = "PHP interpreter",
            blockedFlags = listOf("-r")
        ),
        AllowedCommand(
            program = "composer",
            description = "PHP dependency manager",
            blockedSubcommands = listOf("global")
        ),
        AllowedCommand(
            program = "phpunit",
            description = "PHP test runner"
        ),
        AllowedCommand(
            program = "artisan",
            description = "Laravel CLI"
        ),

        // ── Swift/Apple ────────────────────────────────────────────────
        AllowedCommand(
            program = "swift",
            description = "Swift compiler & package manager"
        ),
        AllowedCommand(
            program = "xcodebuild",
            description = "Xcode build CLI",
            blockedFlags = listOf("-allowProvisioningUpdates")
        ),
        AllowedCommand(
            program = "xcrun",
            description = "Xcode toolchain runner"
        ),

        // ── C/C++ ──────────────────────────────────────────────────────
        AllowedCommand(
            program = "gcc",
            aliases = listOf("g++", "cc", "c++"),
            description = "GNU C/C++ compiler"
        ),
        AllowedCommand(
            program = "clang",
            aliases = listOf("clang++"),
            description = "LLVM C/C++ compiler"
        ),
        AllowedCommand(
            program = "gdb",
            description = "GNU debugger"
        ),
        AllowedCommand(
            program = "lldb",
            description = "LLVM debugger"
        ),
        AllowedCommand(
            program = "valgrind",
            description = "Memory analysis tool"
        ),

        // ── Dart/Flutter ───────────────────────────────────────────────
        AllowedCommand(
            program = "dart",
            description = "Dart SDK",
            blockedSubcommands = listOf("pub publish")
        ),
        AllowedCommand(
            program = "flutter",
            description = "Flutter SDK"
        ),

        // ── Docker & Containers ────────────────────────────────────────
        AllowedCommand(
            program = "docker",
            description = "Docker CLI",
            blockedSubcommands = listOf(
                "rm", "rmi", "prune", "push",
                "system prune", "volume rm", "network rm"
            ),
            blockedFlags = listOf("--privileged"),
            blockedArgPatterns = listOf(".*--cap-add.*")
        ),
        AllowedCommand(
            program = "docker-compose",
            aliases = listOf("docker compose"),
            description = "Docker Compose",
            blockedSubcommands = listOf("rm", "down --volumes", "push")
        ),
        AllowedCommand(
            program = "podman",
            description = "Podman container engine",
            blockedSubcommands = listOf("rm", "rmi", "push", "system prune"),
            blockedFlags = listOf("--privileged")
        ),
        AllowedCommand(
            program = "kubectl",
            description = "Kubernetes CLI",
            blockedSubcommands = listOf("delete", "drain", "cordon", "taint", "edit"),
            blockedFlags = listOf("--force")
        ),

        // ── Database CLIs (read-safe) ──────────────────────────────────
        AllowedCommand(
            program = "psql",
            description = "PostgreSQL client",
            blockedArgPatterns = listOf(".*DROP\\s+DATABASE.*", ".*DROP\\s+TABLE.*")
        ),
        AllowedCommand(
            program = "mysql",
            description = "MySQL client",
            blockedArgPatterns = listOf(".*DROP\\s+DATABASE.*", ".*DROP\\s+TABLE.*")
        ),
        AllowedCommand(
            program = "sqlite3",
            description = "SQLite client"
        ),
        AllowedCommand(
            program = "redis-cli",
            description = "Redis client",
            blockedSubcommands = listOf("FLUSHALL", "FLUSHDB", "CONFIG SET", "DEBUG")
        ),
        AllowedCommand(
            program = "mongosh",
            aliases = listOf("mongo"),
            description = "MongoDB shell"
        ),

        // ── Cloud CLIs (read operations) ───────────────────────────────
        AllowedCommand(
            program = "aws",
            description = "AWS CLI",
            blockedSubcommands = listOf(
                "s3 rm", "ec2 terminate-instances", "rds delete",
                "iam delete", "lambda delete", "cloudformation delete"
            ),
            requireConfirmation = true
        ),
        AllowedCommand(
            program = "gcloud",
            description = "Google Cloud CLI",
            blockedSubcommands = listOf("compute instances delete", "sql instances delete", "functions delete"),
            requireConfirmation = true
        ),
        AllowedCommand(
            program = "az",
            description = "Azure CLI",
            blockedSubcommands = listOf("vm delete", "group delete", "webapp delete"),
            requireConfirmation = true
        ),

        // ── Infrastructure as Code ─────────────────────────────────────
        AllowedCommand(
            program = "terraform",
            description = "Terraform IaC",
            allowedSubcommands = listOf(
                "init", "plan", "validate", "fmt", "show", "state list",
                "state show", "output", "providers", "version", "graph"
            )
        ),
        AllowedCommand(
            program = "pulumi",
            description = "Pulumi IaC",
            allowedSubcommands = listOf("preview", "stack", "config", "whoami", "version")
        ),

        // ── Shell Utilities (cross-platform) ───────────────────────────
        AllowedCommand(
            program = "cat",
            aliases = listOf("type", "get-content"),
            description = "Print file content",
            blockedArgPatterns = listOf(
                ".*\\.env$", ".*/\\.ssh/.*", ".*credentials.*",
                ".*/etc/shadow.*", ".*/etc/passwd.*"
            )
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
            aliases = listOf("where.exe", "where"),
            description = "Locate executable path"
        ),
        AllowedCommand(
            program = "get-command",
            aliases = listOf("gcm"),
            description = "Resolve command metadata"
        ),
        AllowedCommand(
            program = "env",
            aliases = listOf("printenv"),
            description = "Print environment variables",
            blockedArgPatterns = listOf(".*SECRET.*", ".*PASSWORD.*", ".*TOKEN.*", ".*API_KEY.*")
        ),

        // ── File Operations (safe) ─────────────────────────────────────
        AllowedCommand(
            program = "find",
            description = "Find files",
            blockedFlags = listOf("-exec", "-delete", "-execdir")
        ),
        AllowedCommand(
            program = "grep",
            aliases = listOf("rg", "ag", "select-string", "sls"),
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
            program = "sed",
            description = "Stream editor"
        ),
        AllowedCommand(
            program = "awk",
            aliases = listOf("gawk"),
            description = "Pattern processing"
        ),
        AllowedCommand(
            program = "cut",
            description = "Extract columns"
        ),
        AllowedCommand(
            program = "tr",
            description = "Translate characters"
        ),
        AllowedCommand(
            program = "tee",
            description = "Pipe to file and stdout"
        ),
        AllowedCommand(
            program = "xargs",
            description = "Build argument lists"
        ),
        AllowedCommand(
            program = "tree",
            description = "Directory tree view"
        ),
        AllowedCommand(
            program = "file",
            description = "Determine file type"
        ),
        AllowedCommand(
            program = "stat",
            description = "File statistics"
        ),
        AllowedCommand(
            program = "du",
            description = "Disk usage"
        ),
        AllowedCommand(
            program = "df",
            description = "Disk free space"
        ),
        AllowedCommand(
            program = "touch",
            description = "Create empty file / update timestamp"
        ),
        AllowedCommand(
            program = "mkdir",
            description = "Create directory"
        ),
        AllowedCommand(
            program = "cp",
            aliases = listOf("copy", "copy-item"),
            description = "Copy files"
        ),
        AllowedCommand(
            program = "mv",
            aliases = listOf("move", "move-item", "ren", "rename-item"),
            description = "Move/rename files"
        ),
        AllowedCommand(
            program = "ln",
            description = "Create links",
            blockedFlags = listOf("-f")
        ),

        // ── Network Utilities (safe/diagnostic) ────────────────────────
        AllowedCommand(
            program = "curl",
            description = "HTTP client",
            blockedArgPatterns = listOf(".*\\|\\s*(sh|bash).*")
        ),
        AllowedCommand(
            program = "wget",
            description = "File downloader",
            blockedArgPatterns = listOf(".*\\|\\s*(sh|bash).*")
        ),
        AllowedCommand(
            program = "ping",
            description = "Network connectivity check"
        ),
        AllowedCommand(
            program = "nslookup",
            aliases = listOf("dig", "host"),
            description = "DNS lookup"
        ),
        AllowedCommand(
            program = "ssh",
            description = "Secure shell",
            requireConfirmation = true
        ),
        AllowedCommand(
            program = "scp",
            description = "Secure copy",
            requireConfirmation = true
        ),
        AllowedCommand(
            program = "rsync",
            description = "Remote sync",
            requireConfirmation = true,
            blockedFlags = listOf("--delete")
        ),

        // ── Process Management (safe) ──────────────────────────────────
        AllowedCommand(
            program = "ps",
            aliases = listOf("get-process"),
            description = "List processes"
        ),
        AllowedCommand(
            program = "top",
            aliases = listOf("htop"),
            description = "Process monitor"
        ),
        AllowedCommand(
            program = "lsof",
            description = "List open files"
        ),
        AllowedCommand(
            program = "netstat",
            aliases = listOf("ss"),
            description = "Network statistics"
        ),
        AllowedCommand(
            program = "kill",
            description = "Terminate process",
            requireConfirmation = true
        ),

        // ── Version Managers ───────────────────────────────────────────
        AllowedCommand(
            program = "nvm",
            description = "Node Version Manager"
        ),
        AllowedCommand(
            program = "fnm",
            description = "Fast Node Manager"
        ),
        AllowedCommand(
            program = "pyenv",
            description = "Python Version Manager"
        ),
        AllowedCommand(
            program = "rbenv",
            description = "Ruby Version Manager"
        ),
        AllowedCommand(
            program = "sdkman",
            aliases = listOf("sdk"),
            description = "JVM SDK Manager"
        ),

        // ── CI/CD & DevOps Tools ───────────────────────────────────────
        AllowedCommand(
            program = "gh",
            description = "GitHub CLI",
            blockedSubcommands = listOf("repo delete", "org delete")
        ),
        AllowedCommand(
            program = "jq",
            description = "JSON processor"
        ),
        AllowedCommand(
            program = "yq",
            description = "YAML processor"
        ),

        // ── Windows-Specific ───────────────────────────────────────────
        AllowedCommand(
            program = "test-path",
            description = "PowerShell path check"
        ),
        AllowedCommand(
            program = "test-connection",
            description = "PowerShell ping"
        ),
        AllowedCommand(
            program = "invoke-webrequest",
            aliases = listOf("iwr"),
            description = "PowerShell HTTP client"
        ),
        AllowedCommand(
            program = "convertfrom-json",
            description = "PowerShell JSON parser"
        ),
        AllowedCommand(
            program = "convertto-json",
            description = "PowerShell JSON serializer"
        ),
        AllowedCommand(
            program = "select-object",
            description = "PowerShell object filter"
        ),
        AllowedCommand(
            program = "where-object",
            description = "PowerShell object filter"
        ),
        AllowedCommand(
            program = "format-list",
            aliases = listOf("fl"),
            description = "PowerShell format list"
        ),
        AllowedCommand(
            program = "format-table",
            aliases = listOf("ft"),
            description = "PowerShell format table"
        ),
        AllowedCommand(
            program = "measure-object",
            description = "PowerShell object measurement"
        ),
        AllowedCommand(
            program = "out-string",
            description = "PowerShell output formatter"
        ),
        AllowedCommand(
            program = "new-item",
            aliases = listOf("ni"),
            description = "PowerShell create file/directory"
        )
    )

    val DEFAULT_BLOCKED_PATTERNS = listOf(
        // Pipe to shell interpreter (code execution via download)
        "\\|\\s*(sh|bash|zsh|powershell|cmd)\\b",
        "\\|\\s*eval\\b",

        // Command substitution (potential injection)
        "\\$\\(",
        "`[^`]+`",

        // Redirect to system directories
        ">\\s*/dev/",
        ">(>)?\\s*/etc/",

        // Destructive recursive delete
        "\\brm\\s+-r",
        "\\bdel\\s+/[sfq].*\\\\",

        // Disk format / raw write
        "\\bmkfs\\b",
        "\\bdd\\b.*of=",

        // Fork bomb
        ":\\(\\)\\s*\\{"
    )
}
