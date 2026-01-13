# Refio Configuration System

This document describes the hierarchical configuration system used in Refio.

## Table of Contents

- [Configuration Hierarchy](#configuration-hierarchy)
- [Configuration Files](#configuration-files)
- [Configuration Sections](#configuration-sections)
- [Key Reference](#key-reference)
- [Examples](#examples)
- [Troubleshooting](#troubleshooting)

---

## Configuration Hierarchy

Refio uses a layered configuration system with the following priority (highest to lowest):

```
┌─────────────────────────────────────────────────────────────┐
│  4. Database (Settings UI)           ← Highest Priority    │
├─────────────────────────────────────────────────────────────┤
│  3. Project Config (.refio/config.yaml)                     │
├─────────────────────────────────────────────────────────────┤
│  2. User Config (~/.refio/config.yaml)                      │
├─────────────────────────────────────────────────────────────┤
│  1. Built-in Defaults                 ← Lowest Priority    │
└─────────────────────────────────────────────────────────────┘
```

### How It Works

1. **Built-in Defaults**: Hardcoded values in `ConfigService.kt` (always available)
2. **User Config**: Personal settings in `~/.refio/config.yaml` (applies to all projects)
3. **Project Config**: Project-specific settings in `<project>/.refio/config.yaml`
4. **Database**: Settings changed via the Settings UI (highest priority)

When a configuration value is requested:
1. First check if there's a value in the database
2. If not found, check the project config file
3. If not found, check the user config file
4. If not found, use the built-in default

---

## Configuration Files

### User Configuration

**Location:**
- Linux/macOS: `~/.refio/config.yaml`
- Windows: `%USERPROFILE%\.refio\config.yaml`

This file contains your personal preferences and API keys. Settings here apply to all projects.

**Recommended content:**
- Provider API keys
- Default models
- General preferences (streaming, markdown formatting)
- System limits

### Project Configuration

**Location:** `<project_root>/.refio/config.yaml`

This file contains project-specific settings. It's checked into version control (optional) to share configuration across team members.

**Recommended content:**
- Project-specific prompts
- MCP server configurations
- Model visibility (which models to show for this project)
- Custom RAG settings

### Creating Configuration Files

You can create a configuration file manually or use the example template:

```bash
# Create user config directory
mkdir -p ~/.refio

# Create example config
cat > ~/.refio/config.yaml << 'EOF'
# Refio User Configuration
general:
  formatMarkdown: true
  streamingEnabled: true

providers:
  ollama:
    endpoint: "http://localhost:11434"
EOF
```

---

## Configuration Sections

### General Settings

Controls basic UI behavior.

```yaml
general:
  formatMarkdown: true      # Format LLM responses as markdown
  streamingEnabled: true    # Stream responses in real-time
  advancedView: false       # Show advanced UI tabs (Steps, Context, RAG, Debug)
```

### Provider Configuration

Configure LLM provider connections and API keys.

```yaml
providers:
  ollama:
    endpoint: "http://localhost:11434"
    contextSize: 32768          # Context window in tokens

  anthropic:
    apiKey: "sk-ant-..."        # Anthropic API key

  openai:
    apiKey: "sk-..."            # OpenAI API key

  openrouter:
    apiKey: "sk-or-..."         # OpenRouter API key

  gemini:
    apiKey: "AIza..."           # Google Gemini API key

  lmstudio:
    baseUrl: "http://localhost:1234/v1"
    contextSize: 32768
```

The Ollama endpoint is shared by chat/completions and embeddings and can be configured in Settings -> Providers.

**Security Note:** API keys should be in your **user config only**, not in project config files that may be committed to version control.

### Model Configuration

Configure default models and visibility.

```yaml
models:
  # Default models per operation mode (format: "provider/model-id")
  defaults:
    chat: "ollama/qwen2.5:7b"           # Chat/conversation model
    plan: "ollama/qwen2.5:7b"           # Planning operations
    coding: "ollama/qwen2.5-coder:7b"   # Coding/agent tasks
    weak: "ollama/qwen2.5:3b"           # Auxiliary operations (summaries)
    embedding: "ollama/nomic-embed-text" # RAG embeddings

  # Control which models appear in the dropdown
  visibility:
    "ollama/qwen2.5:7b": true
    "ollama/qwen2.5:14b": true
    "openai/gpt-4o-mini": true
    "openai/gpt-4o": false              # Hidden (expensive)
    "anthropic/claude-3-opus-20240229": false  # Hidden (expensive)
```

### System Limits

Configure timeouts and size limits.

```yaml
limits:
  apiCallTimeout: 240            # API call timeout (seconds)
  toolExecutionTimeout: 240      # Tool execution timeout (seconds)
  streamingReadTimeout: 240      # Time between streaming chunks (seconds)
  streamingRequestTimeout: 1800  # Total streaming duration (seconds)
  maxContextSize: 128000         # Maximum context tokens
  maxOutputSize: 16384           # Maximum output tokens
  maxFileSize: 10                # Maximum file size (MB)
```

### Advanced Settings

Security and optimization settings.

```yaml
advanced:
  noEgressDefault: false         # Block external network calls by default
  readOnlyMode: false            # Prevent all file write operations
  autoOptimizePercentage: 85     # Auto-optimize context at this % of limit
```

### Tool Permissions

Control which tools are available in each mode.

```yaml
tools:
  permissions:
    read_file:
      planMode: "ON"
      agentMode: "ON"
      create_new_file:
        planMode: "OFF"
        agentMode: "ON"
      multi_line_editor:
        planMode: "OFF"
        agentMode: "ON"
      run_terminal_command:
        planMode: "OFF"
        agentMode: "OFF"     # Disabled for security
```

### RAG Configuration

Configure the Retrieval-Augmented Generation system.

```yaml
rag:
  enabled: true                 # Enable RAG features
  indexOnStartup: true          # Index project at IDE startup
  autoIndexOnContextBuild: true # Auto-index when building context
  maxFileSizeMB: 2              # Max file size for indexing
  maxChunksPerFile: 100         # Max chunks per file
  indexBatchSize: 10            # Files per indexing batch
  embeddingsBatchSize: 50       # Embeddings per batch
  cacheTtlMs: 300000            # Cache TTL (5 minutes)
  maxConcurrentJobs: 4          # Max concurrent indexing jobs
  searchSimilarityThreshold: 0.5 # Minimum similarity (0.0-1.0)
  searchTopK: 5                 # Default results count
  searchHybridEnabled: false    # Enable hybrid search
  searchSemanticWeight: 0.7     # Semantic vs keyword weight
  searchIncludeContextChunks: false # Include same-file context chunks

  # Directories to exclude from indexing
  ignoredDirectories:
    - ".git"
    - ".idea"
    - ".vscode"
    - ".gradle"
    - "node_modules"
    - "build"
    - "dist"
    - "out"
    - "target"
    - "__pycache__"
    - ".venv"
    - "*.log"
    - "*.tmp"
```

If a project-level `.aiignore` file exists, it overrides `rag.ignoredDirectories` and the default UI ignore list for RAG indexing, project analysis, and automatic searches (for example `@codebase` and `@grep`). Explicit `@file` and `@folder` selections are not filtered. The `.aiignore` syntax follows `.gitignore` patterns.

### UI State

Persisted UI preferences.

```yaml
ui:
  thinkingEnabled: false        # Show LLM thinking process
  noEgressEnabled: false        # Block external network calls
  orchestrationEnabled: true    # Enable orchestration toggle
  intentClassificationEnabled: false # Enable LLM intent classification
  executionMode: "AUTO"         # AUTO or INTERACTIVE
  selectedMode: "CHAT"          # CHAT, PLAN, or AGENT
  selectedModel: ""             # Currently selected model (empty = auto)
```

### Custom Prompts (Project-Specific)

Define custom system prompts for your project.

```yaml
prompts:
  # Override the default chat system prompt
  systemChat: |
    You are a helpful coding assistant for this Kotlin project.
    Follow the project's coding conventions documented in CLAUDE.md.
    Use 4-space indentation and prefer immutable data structures.

  # Override the default planning prompt
  systemPlan: |
    You are a planning assistant. Create detailed, step-by-step plans.
    Consider edge cases and error handling in your plans.

  # Override the default agent prompt
  systemAgent: |
    You are an autonomous coding agent.
    Always run tests after making changes.
    Prefer small, focused commits.

  # Define custom slash commands
  commands:
    - name: "fix"
      description: "Fix code issues"
      content: "Analyze and fix any issues in the selected code. Explain what was wrong."
      enabled: true

    - name: "refactor"
      description: "Refactor code"
      content: "Refactor the selected code for better readability and maintainability."
      enabled: true

    - name: "test"
      description: "Generate tests"
      content: "Generate comprehensive unit tests for the selected code."
      enabled: true

  # Define custom rules (always included in context)
  rules:
    - name: "coding-style"
      content: "Always use 4-space indentation. Prefer val over var."
      enabled: true

    - name: "testing"
      content: "Include unit tests for all new functionality."
      enabled: true
```

### MCP Server Configuration (Project-Specific)

Configure Model Context Protocol servers.

```yaml
mcp:
  servers:
    - id: "github"
      displayName: "GitHub"
      type: "STDIO"
      command: "npx"
      args: ["-y", "@modelcontextprotocol/server-github"]
      accessMode: "READ"
      enabled: true
      env:
        - name: "GITHUB_TOKEN"
          value: "${GITHUB_TOKEN}"    # Use environment variable
          isSecret: true

    - id: "filesystem"
      displayName: "Filesystem"
      type: "STDIO"
      command: "npx"
      args: ["-y", "@modelcontextprotocol/server-filesystem", "./docs"]
      accessMode: "READ_WRITE"
      enabled: true

    - id: "postgres"
      displayName: "PostgreSQL"
      type: "STDIO"
      command: "npx"
      args: ["-y", "@modelcontextprotocol/server-postgres"]
      accessMode: "READ"
      enabled: false
      env:
        - name: "POSTGRES_CONNECTION_STRING"
          value: "postgresql://user:pass@localhost:5432/db"
          isSecret: true
```

---

## Key Reference

### Complete Key Mapping

| YAML Path | ConfigService Key | Default Value |
|-----------|-------------------|---------------|
| `general.formatMarkdown` | `general.format_markdown` | `true` |
| `general.streamingEnabled` | `general.streaming_enabled` | `true` |
| `general.advancedView` | `general.advanced_view` | `false` |
| `providers.ollama.endpoint` | `ollama_endpoint` | `http://localhost:11434` |
| `providers.ollama.contextSize` | `providers.ollama.ollama_context_size` | `32768` |
| `providers.anthropic.apiKey` | `anthropic_api_key` | - |
| `providers.openai.apiKey` | `openai_api_key` | - |
| `providers.openrouter.apiKey` | `openrouter_api_key` | - |
| `providers.gemini.apiKey` | `gemini_api_key` | - |
| `providers.lmstudio.baseUrl` | `lmstudio_base_url` | `http://localhost:1234/v1` |
| `models.defaults.chat` | `default_model.chat` | `qwen2.5:7b` |
| `models.defaults.plan` | `default_model.plan` | `qwen2.5:7b` |
| `models.defaults.coding` | `default_model.agent` | `qwen2.5:7b` |
| `models.defaults.weak` | `default_model.weak` | `qwen2.5:7b` |
| `models.defaults.embedding` | `models.embedding_model` | `nomic-embed-text` |
| `limits.apiCallTimeout` | `limits.api_call_timeout` | `240` |
| `limits.toolExecutionTimeout` | `limits.tool_execution_timeout` | `240` |
| `limits.maxContextSize` | `limits.max_context_size` | `128000` |
| `limits.maxOutputSize` | `limits.max_output_size` | `16384` |
| `limits.maxFileSize` | `limits.max_file_size` | `10` |
| `advanced.noEgressDefault` | `advanced.no_egress_default` | `false` |
| `advanced.readOnlyMode` | `advanced.read_only_mode` | `false` |
| `rag.enabled` | `rag.enabled` | `true` |
| `rag.indexOnStartup` | `rag.index_on_startup` | `true` |
| `rag.searchSimilarityThreshold` | `rag.search_similarity_threshold` | `0.5` |
| `rag.searchTopK` | `rag.search_top_k` | `5` |
| `rag.searchHybridEnabled` | `rag.search_hybrid_enabled` | `false` |
| `rag.searchSemanticWeight` | `rag.search_semantic_weight` | `0.7` |
| `rag.searchIncludeContextChunks` | `rag.search_include_context_chunks` | `false` |
| `ui.thinkingEnabled` | `ui.thinking_enabled` | `false` |
| `ui.noEgressEnabled` | `ui.no_egress_enabled` | `false` |
| `ui.intentClassificationEnabled` | `ui.intent_classification_enabled` | `false` |
| `ui.selectedMode` | `ui.selected_mode` | `CHAT` |
| `ui.selectedModel` | `ui.selected_model` | - |

---

## Examples

### Minimal User Config

```yaml
# ~/.refio/config.yaml
providers:
  ollama:
    endpoint: "http://localhost:11434"

models:
  defaults:
    chat: "ollama/qwen2.5:7b"
```

### Full User Config

```yaml
# ~/.refio/config.yaml
general:
  formatMarkdown: true
  streamingEnabled: true
  advancedView: true

providers:
  ollama:
    endpoint: "http://localhost:11434"
    contextSize: 65536

  anthropic:
    apiKey: "sk-ant-api03-..."

  openai:
    apiKey: "sk-proj-..."

models:
  defaults:
    chat: "anthropic/claude-3-5-sonnet-20241022"
    plan: "anthropic/claude-3-5-sonnet-20241022"
    coding: "anthropic/claude-3-5-sonnet-20241022"
    weak: "ollama/qwen2.5:3b"
    embedding: "ollama/nomic-embed-text"

  visibility:
    "ollama/qwen2.5:7b": true
    "ollama/qwen2.5:14b": true
    "anthropic/claude-3-5-sonnet-20241022": true
    "openai/gpt-4o": true

limits:
  apiCallTimeout: 300
  maxContextSize: 200000

advanced:
  orchestrationEnabled: true
```

### Project Config for a Kotlin Project

```yaml
# <project>/.refio/config.yaml
prompts:
  systemChat: |
    You are a Kotlin expert working on this IntelliJ plugin.
    Follow the guidelines in CLAUDE.md.
    Use Kotlin idioms and prefer immutable data.

  commands:
    - name: "review"
      description: "Code review"
      content: "Review this code for Kotlin best practices, potential bugs, and performance issues."
      enabled: true

rag:
  ignoredDirectories:
    - ".git"
    - ".idea"
    - "build"
    - "gradle"
    - ".gradle"

mcp:
  servers:
    - id: "github"
      displayName: "GitHub"
      type: "STDIO"
      command: "npx"
      args: ["-y", "@modelcontextprotocol/server-github"]
      accessMode: "READ"
      enabled: true
```

---

## Troubleshooting

### Config Not Being Applied

1. **Check file location**: Ensure the config file is in the correct location
2. **Validate YAML syntax**: Use a YAML validator to check for syntax errors
3. **Check hierarchy**: Remember that database values override YAML values
4. **Reload config**: Use Settings UI → "Reload from YAML" button

### API Keys Not Working

1. **Check key format**: Ensure the key is quoted if it contains special characters
2. **Check provider section**: Keys must be under the correct provider
3. **Don't commit keys**: Keep API keys in user config only

### Models Not Appearing

1. **Check visibility settings**: Ensure the model is set to `true` in visibility
2. **Check provider connection**: Verify the provider (e.g., Ollama) is running
3. **Check model format**: Use `provider/model-id` format (e.g., `ollama/qwen2.5:7b`)

### Debugging Config Loading

Enable debug logging to see which config sources are being loaded:

```
-Didea.log.debug.categories=#pl.jclab.refio.core.config
```

Check the IDE log for messages like:
```
INFO: Loaded user config from ~/.refio/config.yaml
INFO: Loaded project config from /path/to/project/.refio/config.yaml
INFO: Using chat model from YAML: qwen2.5:7b
```

---

## Programmatic Access

For plugin developers, configuration can be accessed via `ConfigService`:

```kotlin
val configService = ConfigService(configRepository, projectId, projectRoot)

// Get value with full hierarchy lookup
val value = configService.get("general.format_markdown")

// Get value from YAML only
val yamlValue = configService.getFromYaml("general.format_markdown")

// Reload YAML config
configService.reloadYamlConfig()

// Get merged YAML config object
val config = configService.getYamlConfig()
```

