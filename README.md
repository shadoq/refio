# RefIo

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![IntelliJ](https://img.shields.io/badge/IntelliJ-2024.1+-orange.svg)](https://www.jetbrains.com/idea/)
[![Version](https://img.shields.io/badge/version-0.0.1.10-green.svg)](CHANGELOG.md)
[![Stage](https://img.shields.io/badge/stage-early--active-yellow.svg)](docs/ROADMAP.md)

## A coding agent for developers who want control

Cursor and Copilot optimize for the magic feeling: hit Tab, code appears, don't ask what happened underneath. RefIo optimizes for the opposite - for **knowing what just happened**.

You see every prompt before it's sent. You choose what context goes in. You pick the model - local or cloud, your call. Tool calls are explicit, not hidden behind animation. File writes go through snapshots before they touch your tree. No-egress mode is a hard switch, not a marketing line - flip it and your code does not leave the machine.

This is not a faster autocomplete. It's a bet that professional work needs auditability more than seamlessness - and that the developer, not the tool, decides where the line between Chat, Plan, and Agent gets drawn.

Concretely:

- **Local-first by default.** Ollama and LM Studio are first-class. Cloud adapters (OpenAI, Anthropic, Gemini, OpenRouter, Z.AI) are there when you want them, never when you don't.
- **Native JetBrains.** Pure Swing UI, no WebView shell. RefIo lives where your project, diffs, files, and errors already are.
- **Three modes with code-enforced boundaries.** Plan can read but physically cannot write. Agent edits with per-tool, per-mode permissions and a `SnapshotService` rollback path.
- **Visible everything.** Each tool call, each token, each cost, each file write - surfaced in the chat stream, not summarized away.
- **MIT licensed.** Inspect it, fork it, audit it. The repo *is* the spec.

**Stage:** v0.0.1.10, early and actively developed. JetBrains-only by design - no VS Code plans. Not a drop-in replacement for inline completion (different category) and not competing head-on with mature agents like Claude Code (RefIo is earlier on the curve). If you want a polished, mass-market AI coding tool *today*, pick something else. If you want the leverage of AI without giving up observability - read on.

See the [**Roadmap**](docs/ROADMAP.md) for where it's heading and where you can help.

---

## Three modes

**Chat** - Ask questions about your code. Full project context via `@mentions`. No tools, no file changes.

**Plan** - Read-only analysis. The agent explores the codebase, builds a step-by-step approach, and reports back - but it *cannot* modify anything. Read-only is **code-enforced**, not just convention.

**Agent** - Full read/write with automatic file snapshots. Per-tool, per-mode permissions (`ON` / `ASK` / `OFF`). Rollback via `SnapshotService`. Visible tool calls.

Plus built-in **subagents** for specialized tasks (`!code-reviewer`, `!security-reviewer`, …) and custom ones as Markdown + YAML in `.refio/agents/`.

---

## Quick start

### IntelliJ plugin

```bash
# 1. Install Ollama + models (for local-first setup)
ollama pull nomic-embed-text       # required for RAG
ollama pull qwen3.5:9b             # small & fast, good for laptops
# or for bigger hardware:
ollama pull qwen3.5:35b
ollama pull qwen3.5:122b

# 2. Build & install plugin
git clone https://github.com/shadoq/refio.git && cd refio
./gradlew :intellij-plugin:buildPlugin   # → intellij-plugin/build/distributions/refio-*.zip
# Install ZIP via: Settings → Plugins → Install from Disk

# 3. Or run in sandbox IDE for development
./gradlew :intellij-plugin:runIde
```

Then open the **RefIo** tool window (View → Tool Windows → RefIo), pick a model, start.

### Terminal (TUI)

```bash
./gradlew :cli:installDist
./cli/build/install/cli/bin/cli --project /path/to/your/project

# Options
./cli/build/install/cli/bin/cli --project . --mode AGENT --model ollama/qwen3.5:35b --no-egress
```

Same core engine, full-screen terminal interface.

---

## What's under the hood

**Execution modes** are dispatched by `WorkflowOrchestrator` → mode-specific executors (`ChatExecutor`, `PlanExecutor`, `StepExecutor`) and the `AgentTurnLoop` (Plan / Agent) with iteration limits and cycle detection.

**Tool system** - file ops, grep, terminal, HTTP, code runner, subagent invocation, snapshots. Per-mode permissions via `ToolPermissionsService`. Session-scoped approval via `ToolApprovalService`.

**Context system** - `@mention` providers for directing context, RAG with semantic chunking (5 language analyzers: Kotlin, Java, Python, TypeScript, HTML), token budget scaled to the active model's context window, tool-result compression with graceful step-down (FULL → DETAILED → SUMMARY) when context fills up. Conversation compaction at ~85% usage. **Content-aware diff compression** elides the body of large pure-create diffs (a 700-line `+`-only generated file collapses to head + tail + a `memory(get_subtask_output)` recovery hint) - the wrap-up turn no longer pays for the file the agent just wrote.

**Security layers** - `PathSandbox` with symlink resolution + parent-chain check + TOCTOU revalidation. `CommandRule` (regex `ALLOW` / `BLOCK` / `ASK`). Secret redaction in logs. `detectSensitiveLogging` Gradle task fails the build if an API-key pattern appears in a log statement.

**Models** - 8 providers: Ollama, LM Studio, OpenAI, Anthropic, Gemini, OpenRouter, Custom OpenAI, Z.AI. Universal tool-calling protocol works with models that lack native function calling; native function calling is now wired across the OpenAI-compatible adapters (OpenRouter / Z.AI / Generic OpenAI / LM Studio) too. Models that fail the native-tools probe are remembered across restarts via `models.native_tools_fallbacks`, so users don't pay the probe cost on every fresh process.

**Extensibility** - subagents as Markdown + YAML (Claude Code compatible format). MCP protocol support (STDIO + HTTP/SSE) with built-in presets. Project instructions via `AGENTS.md`, `.refio/agent.md`, `.refio/rules/*.md` with glob-based activation. `.aiignore` for RAG exclusions.

**Two front-ends, one core** - the same `:core` Gradle module drives the IntelliJ plugin and the standalone CLI/TUI.

---

## Status & known limitations

Honest assessment - developers deserve to know what they're looking at:

- **Orchestration is a light router + executors**, not a deep agent engine. `IntentRouter` maps modes and dispatches; `WorkflowOrchestrator` coordinates executors (~200 LOC).
- **Multi-agent infrastructure exists but A2A messaging is incomplete.** `AgentEventBus`, `MultiAgentRunner`, and parallel orchestration are wired; peer-to-peer `send_message` → `answer_message` loop is not yet production-ready. Subagents currently run as isolated invocations.
- **No git worktree isolation per task.** Agents edit files directly (with snapshot rollback), not in an isolated branch.
- **Planning loop is basic.** Plan executor works, but no plan-refinement iterations (plan → execute → evaluate → refine → continue) yet.
- **Security layers are pragmatic v1.** Working, but this is defense-at-depth-MVP, not hardened multi-layered security.
- **No agent dashboard.** Tool calls are visible in the chat stream, but no dedicated command center UI for long-running tasks.
- **Small community, fast changes** - v0.0.1.x. Breaking changes possible pre-1.0. Not yet battle-tested at scale.

See [**docs/ROADMAP.md**](docs/ROADMAP.md) for where each of these is heading.

---

## Features

- **@mentions** - `@file`, `@folder`, `@codebase` (RAG), `@grep`, `@diff`, `@commit`, `@problems`, `@terminal`, `@docs`, `@url`, `@clipboard`, `@current`, `@recent`, `@open`
- **RAG-powered semantic search** - automatic project indexing with 5 language analyzers; stored in SQLite; circuit breaker for graceful degradation
- **Tool library** - 7 read-only + 8 write tools (`http_request`, `run_code`, `invoke_subagent`, `delegate_to_strong_model`, and more), with per-mode permissions
- **LLM providers** - Ollama, OpenAI, Anthropic, Gemini, OpenRouter, LM Studio, Custom OpenAI, Z.AI
- **MCP protocol support** - STDIO + HTTP/SSE, with built-in presets (GitHub, PostgreSQL, Brave Search, …)
- **Built-in subagents** - specialized roles invocable with `!agent-name` prefix
- **Project instructions** - `AGENTS.md`, `.refio/agent.md`, `.refio/rules/*.md` (glob-activated)
- **Custom subagents** - define your own in `.refio/agents/*.md`
- **Token budgeting** - per-section context limits scaled to the active model
- **File snapshots** - automatic backup before every write operation, zlib compressed with SHA-256 dedup
- **Auto-compaction** - prevents context overflow during long agent sessions
- **Parallel tool execution** - READ_ONLY tools run concurrently (~2-3x faster for multi-file analysis)
- **Native Swing UI + TUI** - IntelliJ Swing components and standalone terminal interface, no WebView

---

## Terminal User Interface (TUI)

RefIo ships a standalone CLI with a full-screen TUI that mirrors the IntelliJ plugin GUI - works in any terminal emulator.

### Layout

```
┌─F1:Help│F2:Steps│F3:Context│F4:RAG│F5:Logs│F6:Debug│F7:API│F8:Files  F9:Set  [CHAT|model] $0.02│5K tok─┐
├────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ ## Architecture                        │ Steps                                                            │
│                                        │                                                                  │
│ The project uses a layered            │  [OK] analyze_codebase                                           │
│ architecture:                          │  [OK] identify_patterns                                         │
│  1. API Layer (routers, api)           │  [>>] generate_report                                           │
│  2. Service Layer                      │  [ ] review_output                                               │
│  3. Domain Layer                       │                                                                  │
│                                        │                                                                  │
│────────────────────────────────────────│                                                                  │
│ [CHAT]                                 │                                                                  │
│ > your message here_                   │                                                                  │
└────────────────────────────────────────┴──────────────────────────────────────────────────────────────────┘
```

### Features

- **Split-pane layout** - Chat on the left (55%), active tab on the right (45%)
- **8 tabs + 2 screens** - F1 Help, F2–F7 tabs (Steps, Context, RAG, Logs, Debug, API), F8 Files, F9 Settings
- **Two input modes** - raw TTY (real terminal) and line mode (IDE terminal, pipes)
- **`@context` autocomplete** - typing `@` opens a popup with context prefixes
- **Settings screen** - 11 sub-tabs covering providers, models, prompts, context/RAG, MCP, tools, subagents
- **Resize-responsive** - UI adapts to terminal window size changes in real time

### Keyboard shortcuts (selection)

| Key | Action |
|-----|--------|
| F1 | Help screen |
| F2–F7 | Switch tabs |
| F9 / Ctrl+S | Settings |
| Shift+Tab | Cycle mode (Chat → Plan → Agent) |
| Ctrl+O | Select model |
| Ctrl+T | Toggle thinking/reasoning mode |
| Ctrl+E | Toggle execution mode (AUTO / INTERACTIVE) |
| Ctrl+N | Toggle no-egress mode |
| Ctrl+W | New session |
| Alt+H | Browse session history |
| Ctrl+L | Continue conversation |
| Ctrl+D | Summarize conversation |
| Ctrl+Y | Copy selected (or last) message |
| `@` / `!` / `/` | Context / subagent / prompt autocomplete |
| Ctrl+Q | Quit |

### Architecture

The TUI is built with **Mordant 3.0.1** (ANSI rendering) and **JLine3 3.26.3** (raw input), following an MVVM pattern. Same `:core` as the IntelliJ plugin.

---

## Configuration

```yaml
# ~/.refio/config.yaml
providers:
  ollama:
    endpoint: "http://localhost:11434"
  anthropic:
    apiKey: "sk-ant-..."

models:
  defaults:
    chat: "ollama/qwen3.5:9b"
    coding: "ollama/qwen3.5:35b"
    embedding: "ollama/nomic-embed-text"
```

See [docs/config.md](docs/config.md) for full configuration reference.

---

## Project status

| |                                          |
|---|------------------------------------------|
| **Version** | 0.0.1.10                                  |
| **Stage** | Early-stage - active development         |
| **License** | MIT                                      |
| **Community** | Small, growing - PRs and issues welcome  |
| **Change cadence** | Fast. Breaking changes possible pre-1.0. |

---

## Documentation

- [**Roadmap**](docs/ROADMAP.md) - where the project is heading
- [Architecture Reference](docs/ARCHITECTURE.md) - internal architecture, components, data flows
- [Technical Overview](docs/overview.md) - detailed technical documentation (~1500 lines)
- [Configuration Guide](docs/config.md) - full configuration reference
- [Changelog](CHANGELOG.md) - version history
- [Privacy](PRIVACY.md) - local storage, cloud behavior, no-egress mode, secret handling

---

## Contributing

Early-stage projects benefit enormously from contributions. Good entry points:

- **Issues & discussions** - bug reports, design questions, feature requests
- **Roadmap items** - see [docs/ROADMAP.md](docs/ROADMAP.md) for open areas
- **Docs & onboarding** - always useful, low-friction contributions
- **Tests** - the `:core:jacocoTestCoverageVerification` gate enforces coverage

```bash
./gradlew :intellij-plugin:runIde       # Run in sandbox IDE
./gradlew :intellij-plugin:buildPlugin  # Build plugin ZIP
./gradlew :cli:installDist              # Build standalone CLI
./gradlew test                          # Run all tests
./gradlew detekt                        # Static analysis
./gradlew ktlintCheck                   # Lint check
```

**Prerequisites:** JDK 17, IntelliJ IDEA 2024.1+, Ollama with `nomic-embed-text` model for RAG.

---

## License

MIT License. See [LICENSE](LICENSE).
