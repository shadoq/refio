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
│  5. Run-scope overrides (CLI --config)  ← Highest Priority  │
├─────────────────────────────────────────────────────────────┤
│  4. Task scope (per-session overrides)                      │
├─────────────────────────────────────────────────────────────┤
│  3. Project scope (from .refio/config.yaml)                 │
├─────────────────────────────────────────────────────────────┤
│  2. App scope (Settings UI + ~/.refio/config.yaml)          │
├─────────────────────────────────────────────────────────────┤
│  1. Built-in Defaults                   ← Lowest Priority  │
└─────────────────────────────────────────────────────────────┘
```

### How It Works

Config files are **applied into the store on startup**, not consulted on every read. Each file
lands in its own scope, and the store resolves a key as TASK, then PROJECT, then APP:

1. **Built-in defaults**: hardcoded in `ConfigDefaultsInitializer`, seeded into APP scope on first run
2. **User config** (`~/.refio/config.yaml`): applied into APP scope for keys that are still unset
3. **Project config** (`<project>/.refio/config.yaml`): applied into PROJECT scope, so it outranks
   both the built-in defaults and the user file
4. **Settings UI**: writes APP scope (or TASK scope for a per-session toggle)
5. **Run-scope overrides** (`--config key=value` in the CLI): win over everything, are read-only and
   are never written back to the database

### Files vs the Settings UI

The project file is re-applied whenever **its content changes**. Between those moments the Settings
UI stays in charge: changing a setting there drops the project-scoped value for that one key, so
your click always takes effect. Edit the project file again and it wins again on the next start.

Deleting `<project>/.refio/config.yaml` drops everything it had applied, and the settings fall back
to the user file / built-in defaults on the next start.

A value outside a key's accepted range (for example `limits.maxContextSize: 10`) is refused with
the offending key named, in both files, rather than being quietly replaced by a default. Since the
project file is usually committed, fix it in the repository - the whole file stays inert until then.

### What the project file can set

Anything with a YAML mapping in the [Key Reference](#complete-key-mapping) - general toggles,
limits, providers, models, RAG, context and UI keys. Sections that are not plain key/value settings
(`prompts`, `mcp`, `docs`, `hooks`) are read straight from the file by the components that own them.

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
- Any setting this project must pin regardless of personal preference (models, limits, tool
  permissions, no-egress); values here outrank the user file and the built-in defaults

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
  formatMarkdown: true        # Format LLM responses as markdown
  streamingEnabled: true      # Stream responses in real-time
  advancedView: false         # Show advanced UI tabs (Steps, Context, RAG, Debug)
  reasoningEffort: "OFF"      # Reasoning strength: OFF/LOW/MEDIUM/HIGH (replaces the old thinkingEnabled boolean)
  noEgressEnabled: false      # Block outbound network calls (local-only mode)
  executionMode: "AUTO"       # AUTO (runs steps automatically) or INTERACTIVE (waits for confirmation)
```

All six fields are edited from the **General** tab in Settings (plugin and TUI). `reasoningEffort`, `noEgressEnabled`, and `executionMode` used to live under `ui:`; they moved to `general:` so the storage layout mirrors the UI tab 1:1. `reasoningEffort` replaces the former `thinkingEnabled` boolean (old config files are still read: `true` maps to `MEDIUM`, `false` to `OFF`).

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

  generic_openai:                # Any OpenAI-compatible server (llama.cpp, vLLM, ...)
    baseUrl: "http://localhost:8080/v1"
    apiKey: ""                   # Optional, only if your server requires one
    model: "qwen3-coder"
    contextSize: 32768           # Declare it yourself, see below
    rawRequest: false            # Let the server own sampling, see below
```

The Ollama endpoint is shared by chat/completions and embeddings and can be configured in Settings -> Providers.

#### Embeddings on your own server

RAG embeddings can come from any server speaking the OpenAI `/embeddings` shape - llama.cpp,
vLLM, text-embeddings-inference. Point `models.defaults.embedding` at the
`openai_compatible` provider and give the endpoint its own section, because embedding models
usually run as a separate process on a different port:

```yaml
providers:
  embeddings:
    baseUrl: "http://localhost:8081/v1"   # /embeddings is appended
    apiKey: ""                            # Optional, local servers need none

models:
  defaults:
    embedding: "openai_compatible/jina-embeddings-v5"
```

Three provider ids are accepted for embeddings: `ollama`, `openai`, `openai_compatible`. Anything
else is an error - it used to fall back to `api.openai.com`, which meant a typo could upload the
indexed project.

`general.noEgressEnabled` covers embeddings too. A local or private-network endpoint stays
allowed; a public one is blocked.

**Changing the embedding model requires regenerating the vectors.** Embeddings are stored per
model, so old and new vectors do not mix, but a search only matches chunks embedded with the
currently selected model until you re-run indexing.

#### Context window for local servers

`contextSize` is how you tell Refio how large a prompt the server accepts. It matters most for
`generic_openai`: servers like llama.cpp do not report `context_length` in `/v1/models`, so the
window cannot be discovered and defaults to 32768 until you declare it. The key has no upper
bound, so a server running a 760000-token window can be declared as-is.

Settings -> Providers offers sizes that double up to 262144 and then grow in 131072 steps up to
1048576. Each provider has its own option set, so a limit specific to one runtime does not
constrain the others. A value outside a provider's set stays valid in `config.yaml`; the dropdown
then displays the nearest lower offered value, and only overwrites your value if you actually pick
something from the list.

The resolution order for the effective window is: this per-provider `contextSize`, then Refio's
built-in table of known cloud models, then whatever the provider reported when its model list was
fetched, then `limits.maxContextSize` as a last resort. On top of that, `limits.maxContextSize`
acts as a **ceiling whenever you set it explicitly** - see [Limits](#limits).

#### Raw request mode

`rawRequest: true` (Settings -> Providers -> "Raw request") stops Refio from putting its own
generation settings in the request body: `temperature`, `max_tokens` and the non-standard
`request_id` are omitted, so whatever your server is configured with applies. Useful because
`max_tokens` is otherwise always sent and clamped to `limits.maxOutputSize`.

What it does **not** remove, deliberately:

- `stream` and `stream_options` - without them token usage falls back to local estimates
- `tools` and `tool_choice` - without them AGENT mode has no tools to call

Reasoning effort is not affected because it was never sent to this provider; only OpenAI and
OpenRouter receive it. The flag applies to `generic_openai` alone, not to Z.AI, which shares the
same adapter.

**Security Note:** API keys should be in your **user config only**, not in project config files that may be committed to version control.

### Model Configuration

Configure default models and visibility.

```yaml
models:
  # Default models per operation mode (format: "provider/model-id")
  defaults:
    chat: "ollama/qwen3.5:9b"           # Chat/conversation model
    plan: "ollama/qwen3.5:9b"           # Planning operations
    coding: "ollama/qwen3.5:9b"   # Coding/agent tasks (agent turn + file edits)
    weak: "ollama/qwen3.5:4b"           # Auxiliary operations (summaries)
    embedding: "ollama/nomic-embed-text" # RAG embeddings

  # Control which models appear in the dropdown
  visibility:
    "ollama/qwen3.5:9b": true
    "ollama/qwen3.5:14b": true
    "openai/gpt-4o-mini": true
    "openai/gpt-4o": false              # Hidden (expensive)
    "anthropic/claude-3-opus-20240229": false  # Hidden (expensive)

  # Custom model presets (appear in Settings → Models → Quick Presets)
  presets:
    - name: "My Cloud Setup"
      description: "Mixed cloud models for daily work"
      defaultModel: "openai/gpt-4.1-mini"
      planModel: "openai/gpt-4.1"
      codingModel: "anthropic/claude-sonnet-4-5-20250929"
      weakModel: "openai/gpt-4.1-nano"
      visibleModels:
        - "openai/gpt-4.1-mini"
        - "openai/gpt-4.1"
        - "openai/gpt-4.1-nano"
        - "anthropic/claude-sonnet-4-5-20250929"
    - name: "Local Dev"
      description: "Fully local models"
      defaultModel: "ollama/qwen3:14b"
      # planModel, codingModel, weakModel default to defaultModel when omitted
```

**Coding model.** In AGENT mode the turn loop and the file-editing tools (`advance_code_editing`, `multi_line_editor`) both resolve the `coding` slot (`default_model.agent`); PLAN mode uses `plan`, CHAT uses `chat`. When a slot is unset it inherits `chat` (the default model), so single-model setups need only set `chat`.

### System Limits

Configure timeouts and size limits.

```yaml
limits:
  apiCallTimeout: 240            # API call timeout (seconds)
  toolExecutionTimeout: 240      # Tool execution timeout (seconds)
  streamingReadTimeout: 240      # Time between streaming chunks (seconds)
  streamingRequestTimeout: 1800  # Total streaming duration (seconds)
  maxContextSize: 128000         # Ceiling on context tokens, see below
  maxOutputSize: 16384           # Maximum output tokens
  maxFileSize: 10                # Maximum file size (MB)
```

**`maxContextSize` is a ceiling once you set it.** Set explicitly (here or in Settings ->
Advanced), it means "never send more than this", and the model's real window stops mattering -
useful for capping spend on a model with a very large window. Left unset, it is only the
last-resort fallback for a model whose window cannot be determined, so its default can never
shrink a window you declared per provider. If you declare `contextSize: 524288` for a local
server, do not also set `maxContextSize: 128000` unless you actually want the smaller limit.

### Advanced Settings

Security and optimization settings.

```yaml
advanced:
  readOnlyMode: false            # Prevent all file write operations
  autoOptimizePercentage: 85     # Auto-optimize context at this % of limit

security:
  allowSymlinks: false           # Unsafe opt-in: allow symbolic links in PathSandbox
```

> **Note:** `noEgressDefault` was removed. Use `general.noEgressEnabled` instead — it is now the single source of truth for blocking outbound network calls (edited from the **General** tab / same key in YAML).

### Tool Permissions

Control which tools are available in each mode. Every tool is one entry under
`tools.permissions`, and each entry needs **both** modes with one of `ON`, `ASK`, `OFF`:

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
      agentMode: "OFF"       # Disabled for security
```

An entry that names only one mode, or uses a value other than `ON`/`ASK`/`OFF`, is reported in the
log and skipped - a malformed line never opens a tool up by accident.

Tools you do not list keep whatever they already have (their smart default derived from the tool's
read/write mode, or a level you set in Settings). So a file with two entries adjusts those two
tools and leaves the rest alone, both on startup and via **Settings → Reload from YAML**.

### RAG Configuration

Configure the Retrieval-Augmented Generation system.

> **Navigation default (docs/0060):** agentic `grep_search` / `file_search` is the primary
> code-navigation path; vector RAG is an **opt-in aid** (best for prose/docs). Auto-indexing is
> therefore **OFF by default** — a cold start pays no CPU/embedding cost. The `rag_search` tool
> stays available; set `indexOnStartup` / `autoIndexOnContextBuild` to `true` to index your project.

```yaml
rag:
  enabled: true                  # Enable RAG features (rag_search tool stays available)
  indexOnStartup: false          # OFF by default — opt in to index project at IDE startup
  autoIndexOnContextBuild: false # OFF by default — opt in to auto-index when building context
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

### Context and Working Memory

How much of the prompt budget goes to recent work and to the facts the agent carries between
iterations.

```yaml
context:
  recentWorkFullDataLimit: 5     # Tool results shown in full before summarizing
  recentWorkSummaryMaxLength: 1000 # Character budget for a summarized tool result
  budgetTotalTokens: 0           # 0 = derive the budget from the model's context window
  budgetInputRatio: 0.85         # Share of the window available for input
  workingMemoryMaxFacts: 20      # Facts kept per task (must be > 0)
  budgetSections:                # Per-section token caps, by section name
    recent_work: 4000
```

### UI State

Persisted UI preferences.

```yaml
ui:
  selectedMode: "CHAT"          # CHAT, PLAN, or AGENT
  selectedModel: ""             # Currently selected model (empty = auto)
```

The `ui:` section only holds transient session state (what the user last picked in the sidebar). User-facing preferences (`reasoningEffort`, `noEgressEnabled`, `executionMode`) now live under `general:` (see *General Settings* above).

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
| `general.reasoningEffort` | `general.reasoning_effort` | `OFF` |
| `general.noEgressEnabled` | `general.no_egress_enabled` | `false` |
| `general.executionMode` | `general.execution_mode` | `AUTO` |
| `providers.ollama.endpoint` | `ollama_endpoint` | `http://localhost:11434` |
| `providers.ollama.contextSize` | `providers.ollama.ollama_context_size` | `32768` |
| `providers.anthropic.apiKey` | `anthropic_api_key` | - |
| `providers.openai.apiKey` | `openai_api_key` | - |
| `providers.openrouter.apiKey` | `openrouter_api_key` | - |
| `providers.gemini.apiKey` | `gemini_api_key` | - |
| `providers.lmstudio.baseUrl` | `lmstudio_base_url` | `http://localhost:1234/v1` |
| `providers.lmstudio.contextSize` | `providers.lmstudio.lmstudio_context_size` | `32768` |
| `providers.generic_openai.baseUrl` | `providers.generic_openai.generic_openai_base_url` | - |
| `providers.generic_openai.apiKey` | `providers.generic_openai.generic_openai_api_key` | - |
| `providers.generic_openai.model` | `providers.generic_openai.generic_openai_model` | - |
| `providers.generic_openai.contextSize` | `providers.generic_openai.generic_openai_context_size` | `32768` |
| `providers.generic_openai.rawRequest` | `providers.generic_openai.generic_openai_raw_request` | `false` |
| `providers.embeddings.baseUrl` | `providers.embeddings.embeddings_base_url` | - |
| `providers.embeddings.apiKey` | `providers.embeddings.embeddings_api_key` | - |
| `models.defaults.chat` | `default_model.chat` | `qwen3.5:9b` |
| `models.defaults.plan` | `default_model.plan` | `qwen3.5:9b` |
| `models.defaults.coding` | `default_model.agent` | `qwen3.5:9b` |
| `models.defaults.weak` | `default_model.weak` | `qwen3.5:9b` |
| `models.defaults.embedding` | `models.embedding_model` | `nomic-embed-text` |
| `models.defaults.strong` | `default_model.strong` | - (optional, no fallback) |
| `limits.apiCallTimeout` | `limits.api_call_timeout` | `240` |
| `limits.toolExecutionTimeout` | `limits.tool_execution_timeout` | `240` |
| `limits.maxContextSize` | `limits.max_context_size` | `128000` |
| `limits.maxOutputSize` | `limits.max_output_size` | `16384` |
| `limits.maxFileSize` | `limits.max_file_size` | `10` |
| `advanced.readOnlyMode` | `advanced.read_only_mode` | `false` |
| `security.allowSymlinks` | `security.allow_symlinks` | `false` |
| `security.allowLoopback` | `security.allow_loopback` | `false` |
| `rag.enabled` | `rag.enabled` | `true` |
| `rag.indexOnStartup` | `rag.index_on_startup` | `false` |
| `rag.autoIndexOnContextBuild` | `rag.auto_index_on_context_build` | `false` |
| `rag.searchSimilarityThreshold` | `rag.search_similarity_threshold` | `0.5` |
| `rag.searchTopK` | `rag.search_top_k` | `5` |
| `rag.searchHybridEnabled` | `rag.search_hybrid_enabled` | `false` |
| `rag.searchSemanticWeight` | `rag.search_semantic_weight` | `0.7` |
| `rag.searchIncludeContextChunks` | `rag.search_include_context_chunks` | `false` |
| `ui.intentClassificationEnabled` | `ui.intent_classification_enabled` | `false` |
| `ui.selectedMode` | `ui.selected_mode` | `CHAT` |
| `ui.selectedModel` | `ui.selected_model` | - |
| `context.recentWorkFullDataLimit` | `context.recent_work.full_data_limit` | `5` |
| `context.recentWorkSummaryMaxLength` | `context.recent_work.summary_max_length` | `1000` |
| `context.budgetTotalTokens` | `context.budget.total_tokens` | `0` (derive from model) |
| `context.budgetInputRatio` | `context.budget.input_ratio` | `0.85` |
| `context.workingMemoryMaxFacts` | `working_memory.max_facts` | `20` |
| `context.budgetSections.<name>` | `context.budget.section.<name>` | - |
| `tools.permissions.<tool>` | `tools.permissions` (one JSON document) | per-tool smart default |

---

Note: `ui.selected_model` stores the model chosen in the chat UI. It does not overwrite
`models.defaults.*`. When the UI is set to Auto, the per-operation defaults are used.

## Examples

### Minimal User Config

```yaml
# ~/.refio/config.yaml
providers:
  ollama:
    endpoint: "http://localhost:11434"

models:
  defaults:
    chat: "ollama/qwen3.5:9b"
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
    weak: "ollama/qwen3.5:4b"
    embedding: "ollama/nomic-embed-text"
    strong: "anthropic/claude-3-5-sonnet-20241022"  # optional, enables delegate_to_strong_model tool

  visibility:
    "ollama/qwen3.5:9b": true
    "ollama/qwen3.5:14b": true
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
2. **Validate YAML syntax**: Use a YAML validator to check for syntax errors - a file that fails to
   parse is reported in the log and the previous values stay in effect
3. **Restart or reload**: files are applied at startup; **Settings → Reload from YAML** re-applies
   both the user and the project file without a restart
4. **Did you change the same setting in the UI?** That change wins until the file is edited again.
   Touch the file (any real content change) and restart, or use "Reload from YAML"

### API Keys Not Working

1. **Check key format**: Ensure the key is quoted if it contains special characters
2. **Check provider section**: Keys must be under the correct provider
3. **Don't commit keys**: Keep API keys in user config only

### Models Not Appearing

1. **Check visibility settings**: Ensure the model is set to `true` in visibility
2. **Check provider connection**: Verify the provider (e.g., Ollama) is running
3. **Check model format**: Use `provider/model-id` format (e.g., `ollama/qwen3.5:9b`)

### Debugging Config Loading

Enable debug logging to see which config sources are being loaded:

```
-Didea.log.debug.categories=#pl.jclab.refio.core.config
```

Check the IDE log for messages like:
```
INFO: Loaded user config from ~/.refio/config.yaml
INFO: Loaded project config from /path/to/project/.refio/config.yaml
INFO: Applied project config: general.no_egress_enabled = true
INFO: Materialized project config from /path/to/project/.refio/config.yaml: 4 keys
INFO: Using chat model from YAML: qwen3.5:9b
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

