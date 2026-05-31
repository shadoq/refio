package pl.jclab.refio.core.context.mcp

/**
 * Preset categories for MCP servers.
 */
enum class MCPPresetCategory(val displayName: String, val icon: String) {
    DOCUMENTATION("Documentation", "📚"),
    VERSION_CONTROL("Version Control", "🔄"),
    DATABASE("Databases", "🗄️"),
    SEARCH("Search", "🔍"),
    FILESYSTEM("File System", "📁"),
    COMMUNICATION("Communication", "💬"),
    CLOUD("Cloud Providers", "☁️"),
    MEMORY("Memory & Knowledge", "🧠"),
    DEVOPS("DevOps & Monitoring", "🔧"),
    DEVELOPMENT("Development Tools", "🛠️")
}

/**
 * Built-in MCP server presets derived from ADR 0001.
 */
object MCPServerPresets {
    data class Preset(
        val id: String,
        val label: String,
        val description: String,
        val category: MCPPresetCategory,
        val build: (projectRoot: String?) -> MCPServerConfig
    )

    val ALL: List<Preset> = listOf(
        Preset(
            id = "filesystem",
            label = "Filesystem (stdio)",
            description = "Local filesystem via @anthropic-ai/mcp-filesystem (READ, stdio).",
            category = MCPPresetCategory.FILESYSTEM,
            build = { projectRoot ->
                val root = projectRoot ?: "."
                MCPServerConfig(
                    id = "filesystem",
                    displayName = "Filesystem Access",
                    description = "Access local files and directories",
                    type = MCPServerType.STDIO,
                    command = "npx",
                    args = listOf("@anthropic-ai/mcp-filesystem", "."),
                    workingDirectory = root,
                    env = emptyList(),
                    httpHeaders = emptyList(),
                    serverInstructions = "Use this server to read project files. Always prefer reading specific files over listing entire directories.",
                    accessMode = MCPAccessMode.READ,
                    enabled = true,
                    timeout = 30_000,
                    resourcesEnabled = true,
                    toolsEnabled = false,
                    promptsEnabled = false
                )
            }
        ),
        Preset(
            id = "github",
            label = "GitHub (stdio)",
            description = "GitHub MCP server over stdio with GITHUB_TOKEN (READ_WRITE).",
            category = MCPPresetCategory.VERSION_CONTROL,
            build = { projectRoot ->
                MCPServerConfig(
                    id = "github",
                    displayName = "GitHub",
                    description = "Access GitHub repositories, issues, and pull requests",
                    type = MCPServerType.STDIO,
                    command = "npx",
                    args = listOf("@github/mcp-server"),
                    workingDirectory = projectRoot,
                    env = listOf(MCPEnvVariable("GITHUB_TOKEN", "\${GITHUB_TOKEN}", isSecret = true)),
                    httpHeaders = emptyList(),
                    serverInstructions = "Use this server to interact with GitHub. You can create issues, read PRs, and manage repositories.",
                    accessMode = MCPAccessMode.READ_WRITE,
                    enabled = true,
                    timeout = 60_000,
                    resourcesEnabled = true,
                    toolsEnabled = true,
                    promptsEnabled = true
                )
            }
        ),
        Preset(
            id = "custom-api",
            label = "Custom API (HTTP/SSE)",
            description = "HTTP/SSE MCP endpoint with auth headers.",
            category = MCPPresetCategory.DEVELOPMENT,
            build = {
                MCPServerConfig(
                    id = "custom-api",
                    displayName = "Custom API Server",
                    description = "Company internal API server",
                    type = MCPServerType.HTTP_SSE,
                    url = "http://localhost:3000/mcp",
                    oauth = null,
                    httpHeaders = listOf(
                        MCPHttpHeader("Authorization", "Bearer \${API_TOKEN}", isSecret = true),
                        MCPHttpHeader("X-Client-Id", "refio-plugin", isSecret = false)
                    ),
                    env = emptyList(),
                    serverInstructions = "This server provides access to company internal APIs. Always respect rate limits.",
                    accessMode = MCPAccessMode.READ,
                    enabled = true,
                    timeout = 30_000,
                    resourcesEnabled = true,
                    toolsEnabled = false,
                    promptsEnabled = true
                )
            }
        ),
        Preset(
            id = "database",
            label = "Database (HTTP Streamable + OAuth)",
            description = "Read-only DB schema/data via HTTP_STREAMABLE with OAuth.",
            category = MCPPresetCategory.DATABASE,
            build = {
                MCPServerConfig(
                    id = "database",
                    displayName = "Database Explorer",
                    description = "Read-only access to production database schema and sample data",
                    type = MCPServerType.HTTP_STREAMABLE,
                    url = "https://db-mcp.company.com/mcp",
                    oauth = MCPOAuthConfig(
                        enabled = true,
                        clientId = "refio-plugin",
                        clientSecret = null,
                        authorizationUrl = "https://auth.company.com/oauth/authorize",
                        tokenUrl = "https://auth.company.com/oauth/token",
                        scopes = listOf("read:schema", "read:data"),
                        redirectUri = null
                    ),
                    httpHeaders = emptyList(),
                    env = emptyList(),
                    serverInstructions = "Use for DB schema and sample data. Do not modify production data.",
                    accessMode = MCPAccessMode.READ,
                    enabled = true,
                    timeout = 30_000,
                    resourcesEnabled = true,
                    toolsEnabled = false,
                    promptsEnabled = true
                )
            }
        ),

        // === Documentation ===
        Preset(
            id = "context7",
            label = "Context7",
            description = "Library documentation and code examples",
            category = MCPPresetCategory.DOCUMENTATION,
            build = {
                MCPServerConfig(
                    id = "context7",
                    displayName = "Context7",
                    description = "Up-to-date documentation for popular libraries",
                    type = MCPServerType.HTTP_SSE,
                    url = "https://mcp.context7.com/mcp",
                    auth = MCPAuthConfig(
                        type = MCPAuthType.BEARER,
                        apiKey = "\${CONTEXT7_API_KEY}",
                        isSecret = true
                    ),
                    httpHeaders = listOf(
                        MCPHttpHeader("Accept", "application/json, text/event-stream", isSecret = false)
                    ),
                    serverInstructions = "Use Context7 to fetch up-to-date documentation for libraries. Always specify the library name and version.",
                    accessMode = MCPAccessMode.READ,
                    enabled = true,
                    resourcesEnabled = true,
                    toolsEnabled = true,
                    // TOOLS (not CONTEXT) so the agent can call resolve-library-id / get-library-docs
                    // directly as mcp_context7_* tools. In CONTEXT mode the server connects but is
                    // never registered as an agent tool — it only feeds the @context7 context path.
                    toolsExposureMode = MCPToolsExposureMode.TOOLS,
                    toolParamMapping = mapOf(
                        "resolve-library-id" to "libraryName",
                        "get-library-docs" to "context7CompatibleLibraryID"
                    ),
                    toolWorkflow = MCPToolWorkflowConfig(
                        steps = listOf(
                            MCPToolWorkflowStep(
                                toolName = "resolve-library-id",
                                inputMapping = mapOf(
                                    "libraryName" to "query"
                                ),
                                outputMapping = mapOf(
                                    "libraryId" to "results[0].id"
                                )
                            ),
                            MCPToolWorkflowStep(
                                toolName = "get-library-docs",
                                inputMapping = mapOf(
                                    "context7CompatibleLibraryID" to "var:libraryId",
                                    "topic" to "query"
                                )
                            )
                        )
                    ),
                    promptsEnabled = true
                )
            }
        ),

        // === DevOps & Monitoring ===
        Preset(
            id = "sentry",
            label = "Sentry",
            description = "Error tracking and performance monitoring",
            category = MCPPresetCategory.DEVOPS,
            build = {
                MCPServerConfig(
                    id = "sentry",
                    displayName = "Sentry",
                    description = "Access Sentry issues, errors, and performance data",
                    type = MCPServerType.HTTP_SSE,
                    url = "https://mcp.sentry.dev/mcp",
                    serverInstructions = "Use Sentry to investigate errors and exceptions. Can search issues, view stack traces, and analyze error trends.",
                    accessMode = MCPAccessMode.READ,
                    enabled = true,
                    resourcesEnabled = true,
                    toolsEnabled = false,
                    promptsEnabled = true
                )
            }
        ),

        // === Version Control ===
        Preset(
            id = "gitlab",
            label = "GitLab",
            description = "GitLab repositories, issues, and merge requests",
            category = MCPPresetCategory.VERSION_CONTROL,
            build = { projectRoot ->
                MCPServerConfig(
                    id = "gitlab",
                    displayName = "GitLab",
                    description = "Access GitLab repositories, issues, and merge requests",
                    type = MCPServerType.STDIO,
                    command = "npx",
                    args = listOf("@modelcontextprotocol/server-gitlab"),
                    workingDirectory = projectRoot,
                    env = listOf(
                        MCPEnvVariable("GITLAB_TOKEN", "\${GITLAB_TOKEN}", isSecret = true),
                        MCPEnvVariable("GITLAB_URL", "https://gitlab.com", isSecret = false)
                    ),
                    serverInstructions = "Use GitLab MCP to interact with GitLab repositories and merge requests.",
                    accessMode = MCPAccessMode.READ_WRITE,
                    enabled = true,
                    resourcesEnabled = true,
                    toolsEnabled = true,
                    promptsEnabled = true
                )
            }
        ),

        // === Databases ===
        Preset(
            id = "postgres",
            label = "PostgreSQL",
            description = "PostgreSQL database access",
            category = MCPPresetCategory.DATABASE,
            build = {
                MCPServerConfig(
                    id = "postgres",
                    displayName = "PostgreSQL",
                    description = "Read-only access to PostgreSQL databases",
                    type = MCPServerType.STDIO,
                    command = "npx",
                    args = listOf("@modelcontextprotocol/server-postgres"),
                    env = listOf(
                        MCPEnvVariable("POSTGRES_CONNECTION_STRING", "\${POSTGRES_CONNECTION_STRING}", isSecret = true)
                    ),
                    serverInstructions = "Use to query PostgreSQL databases. Only read operations are allowed. Use for schema exploration and data queries.",
                    accessMode = MCPAccessMode.READ,
                    enabled = true,
                    resourcesEnabled = true,
                    toolsEnabled = false,
                    promptsEnabled = true
                )
            }
        ),
        Preset(
            id = "sqlite",
            label = "SQLite",
            description = "SQLite database access",
            category = MCPPresetCategory.DATABASE,
            build = {
                MCPServerConfig(
                    id = "sqlite",
                    displayName = "SQLite",
                    description = "Access local SQLite databases",
                    type = MCPServerType.STDIO,
                    command = "npx",
                    args = listOf("@modelcontextprotocol/server-sqlite", "--db-path", "\${SQLITE_DB_PATH}"),
                    serverInstructions = "Use to query SQLite databases. Specify the database file path.",
                    accessMode = MCPAccessMode.READ,
                    enabled = true,
                    resourcesEnabled = true,
                    toolsEnabled = false,
                    promptsEnabled = true
                )
            }
        ),

        // === Search ===
        Preset(
            id = "brave-search",
            label = "Brave Search",
            description = "Web search via Brave Search API",
            category = MCPPresetCategory.SEARCH,
            build = {
                MCPServerConfig(
                    id = "brave-search",
                    displayName = "Brave Search",
                    description = "Web search powered by Brave Search API",
                    type = MCPServerType.STDIO,
                    command = "npx",
                    args = listOf("@modelcontextprotocol/server-brave-search"),
                    env = listOf(
                        MCPEnvVariable("BRAVE_API_KEY", "\${BRAVE_API_KEY}", isSecret = true)
                    ),
                    serverInstructions = "Use Brave Search for web queries. Prefer specific, targeted searches over broad queries.",
                    accessMode = MCPAccessMode.READ,
                    enabled = true,
                    resourcesEnabled = true,
                    toolsEnabled = false,
                    promptsEnabled = true
                )
            }
        ),
        Preset(
            id = "exa",
            label = "Exa",
            description = "AI-powered web search",
            category = MCPPresetCategory.SEARCH,
            build = {
                MCPServerConfig(
                    id = "exa",
                    displayName = "Exa Search",
                    description = "AI-powered semantic web search",
                    type = MCPServerType.STDIO,
                    command = "npx",
                    args = listOf("exa-mcp-server"),
                    env = listOf(
                        MCPEnvVariable("EXA_API_KEY", "\${EXA_API_KEY}", isSecret = true)
                    ),
                    serverInstructions = "Use Exa for semantic web search. Good for finding specific content and research.",
                    accessMode = MCPAccessMode.READ,
                    enabled = true,
                    resourcesEnabled = true,
                    toolsEnabled = false,
                    promptsEnabled = true
                )
            }
        ),

        // === File System ===
        Preset(
            id = "google-drive",
            label = "Google Drive",
            description = "Google Drive file access",
            category = MCPPresetCategory.FILESYSTEM,
            build = {
                MCPServerConfig(
                    id = "google-drive",
                    displayName = "Google Drive",
                    description = "Access files from Google Drive",
                    type = MCPServerType.STDIO,
                    command = "npx",
                    args = listOf("@modelcontextprotocol/server-gdrive"),
                    oauth = MCPOAuthConfig(
                        enabled = true,
                        scopes = listOf("https://www.googleapis.com/auth/drive.readonly")
                    ),
                    serverInstructions = "Use to read files from Google Drive. Supports searching and reading documents.",
                    accessMode = MCPAccessMode.READ,
                    enabled = true,
                    resourcesEnabled = true,
                    toolsEnabled = false,
                    promptsEnabled = true
                )
            }
        ),

        // === Communication ===
        Preset(
            id = "slack",
            label = "Slack",
            description = "Slack workspace access",
            category = MCPPresetCategory.COMMUNICATION,
            build = {
                MCPServerConfig(
                    id = "slack",
                    displayName = "Slack",
                    description = "Access Slack channels and messages",
                    type = MCPServerType.STDIO,
                    command = "npx",
                    args = listOf("@modelcontextprotocol/server-slack"),
                    env = listOf(
                        MCPEnvVariable("SLACK_BOT_TOKEN", "\${SLACK_BOT_TOKEN}", isSecret = true),
                        MCPEnvVariable("SLACK_TEAM_ID", "\${SLACK_TEAM_ID}", isSecret = false)
                    ),
                    serverInstructions = "Use Slack MCP to read and send messages. Be careful with message sending in production channels.",
                    accessMode = MCPAccessMode.READ_WRITE,
                    enabled = true,
                    resourcesEnabled = true,
                    toolsEnabled = true,
                    promptsEnabled = true
                )
            }
        ),

        // === Cloud Providers ===
        Preset(
            id = "aws",
            label = "AWS",
            description = "Amazon Web Services access",
            category = MCPPresetCategory.CLOUD,
            build = {
                MCPServerConfig(
                    id = "aws",
                    displayName = "AWS",
                    description = "Access AWS services (S3, Lambda, EC2, etc.)",
                    type = MCPServerType.STDIO,
                    command = "npx",
                    args = listOf("@anthropic-ai/mcp-server-aws"),
                    env = listOf(
                        MCPEnvVariable("AWS_ACCESS_KEY_ID", "\${AWS_ACCESS_KEY_ID}", isSecret = true),
                        MCPEnvVariable("AWS_SECRET_ACCESS_KEY", "\${AWS_SECRET_ACCESS_KEY}", isSecret = true),
                        MCPEnvVariable("AWS_REGION", "\${AWS_REGION}", isSecret = false)
                    ),
                    serverInstructions = "Use AWS MCP to interact with AWS services. Be cautious with write operations.",
                    accessMode = MCPAccessMode.READ_WRITE,
                    enabled = true,
                    resourcesEnabled = true,
                    toolsEnabled = true,
                    promptsEnabled = true
                )
            }
        ),

        // === Memory & Knowledge ===
        Preset(
            id = "memory",
            label = "Memory",
            description = "Persistent memory for conversations",
            category = MCPPresetCategory.MEMORY,
            build = {
                MCPServerConfig(
                    id = "memory",
                    displayName = "Memory",
                    description = "Store and retrieve information across conversations",
                    type = MCPServerType.STDIO,
                    command = "npx",
                    args = listOf("@modelcontextprotocol/server-memory"),
                    serverInstructions = "Use Memory to store important facts and retrieve them later. Good for maintaining context across sessions.",
                    accessMode = MCPAccessMode.READ_WRITE,
                    enabled = true,
                    resourcesEnabled = true,
                    toolsEnabled = true,
                    promptsEnabled = true
                )
            }
        ),

        // === Development Tools ===
        Preset(
            id = "puppeteer",
            label = "Puppeteer",
            description = "Browser automation and web scraping",
            category = MCPPresetCategory.DEVELOPMENT,
            build = {
                MCPServerConfig(
                    id = "puppeteer",
                    displayName = "Puppeteer",
                    description = "Automated browser for web scraping and testing",
                    type = MCPServerType.STDIO,
                    command = "npx",
                    args = listOf("@modelcontextprotocol/server-puppeteer"),
                    serverInstructions = "Use Puppeteer for web automation tasks. Can navigate pages, take screenshots, and extract data.",
                    accessMode = MCPAccessMode.READ_WRITE,
                    enabled = true,
                    resourcesEnabled = true,
                    toolsEnabled = true,
                    promptsEnabled = true
                )
            }
        ),
        Preset(
            id = "sequential-thinking",
            label = "Sequential Thinking",
            description = "Step-by-step reasoning assistant",
            category = MCPPresetCategory.DEVELOPMENT,
            build = {
                MCPServerConfig(
                    id = "sequential-thinking",
                    displayName = "Sequential Thinking",
                    description = "Helps with complex reasoning tasks by breaking them into steps",
                    type = MCPServerType.STDIO,
                    command = "npx",
                    args = listOf("@modelcontextprotocol/server-sequential-thinking"),
                    serverInstructions = "Use for complex reasoning tasks that benefit from step-by-step analysis.",
                    accessMode = MCPAccessMode.READ,
                    enabled = true,
                    resourcesEnabled = true,
                    toolsEnabled = false,
                    promptsEnabled = true
                )
            }
        )
    )

    fun getByCategory(category: MCPPresetCategory): List<Preset> {
        return ALL.filter { it.category == category }
    }

    fun getById(id: String): Preset? {
        return ALL.find { it.id == id }
    }
}
