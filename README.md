# Refio

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![IntelliJ](https://img.shields.io/badge/IntelliJ-2024.x-orange.svg)](https://www.jetbrains.com/idea/)
[![Version](https://img.shields.io/badge/version-0.0.1-green.svg)](CHANGELOG.md)

**Local-first AI coding assistant for IntelliJ IDEA and the terminal.**
Built for developers who want control over context, tools, and execution.

---

## Why Refio?

- **Deterministic execution modes** — think, inspect, then execute. Three distinct modes so you always know what the AI can and cannot do.
- **Designed for local models** — works fully offline with Ollama and LM Studio. No cloud account required.
- **Full context visibility** — see exactly what's sent to the model. 14 context providers with token budgeting.
- **50-70% lower API costs** — RAG-powered selective context injection instead of sending entire codebases.
- **Open source, MIT license** — free forever. No telemetry, no vendor lock-in.

---

## Refio vs Alternatives

| Feature | Refio | JetBrains AI | Copilot | Cursor |
|---------|-------|-------------|---------|--------|
| Price | Free | $10/mo | $10-39/mo | $20-40/mo |
| Local LLM support | Yes | No | No | No |
| Open Source | Yes | No | No | No |
| Agent Mode | Yes | Limited | Limited | Yes |
| Context Control | Full | No | No | Partial |
| No-Egress Mode | Yes | No | No | No |
| Native IDE UI | Yes | Yes | Yes | N/A |

---

## Three Modes

**Chat** — Ask questions, reason about code. No tools, no changes. Safe conversation with full project context.

**Plan** — Analyze with read-only tools. The model reads files, searches code, and explores your codebase — but cannot modify anything.

**Agent** — Full read/write with file snapshots. Automated coding with subtask tracking, rollback support, and safety limits.

Plus **21 built-in subagents** for specialized tasks: code review, security audit, architecture analysis, documentation, and more.

---

## Quick Start

### IntelliJ Plugin

```bash
# 1. Install Ollama + models
ollama pull nomic-embed-text    # Required for RAG embeddings
ollama pull qwen2.5-coder:7b    # Recommended coding model

# 2. Build & install plugin
git clone https://github.com/shadoq/refio.git && cd refio
./gradlew buildPlugin           # Output: build/distributions/refio-*.zip
# Install ZIP via: Settings -> Plugins -> Install from Disk

# 3. Or run in sandbox IDE for development
./gradlew runIde
```

Then open the **Refio** tool window (View -> Tool Windows -> Refio), select a mode, choose your model, and start.

### CLI (Terminal User Interface)

```bash
# Build the CLI
./gradlew :cli:installDist

# Run
./cli/build/install/cli/bin/cli --project /path/to/your/project

# Options
./cli/build/install/cli/bin/cli --project . --mode AGENT --model ollama/qwen2.5-coder:7b --no-egress
```

The CLI provides a full-featured TUI that mirrors the IntelliJ plugin GUI — no IDE required.

---

## Who Is This For?

- Developers using **local models** who need a proper IDE integration
- Power users who want **deterministic, predictable** AI behavior
- Engineers who need **full context visibility** — no black box
- Teams that require **no-egress mode** for sensitive codebases

*If you just want autocomplete — use Copilot. If you want control — use Refio.*

---

## Features

- **14 context providers** — @file, @folder, @codebase, @grep, @diff, @url, @docs, @clipboard, and more
- **RAG-powered semantic search** — automatic project indexing with 5 language analyzers
- **12 tools** — 5 read-only + 6 write + invoke_subagent, with per-mode permissions
- **6 LLM providers** — Ollama, OpenAI, Anthropic, Gemini, OpenRouter, LM Studio
- **MCP protocol support** — 18 built-in server presets (GitHub, PostgreSQL, Brave Search, etc.)
- **21 built-in subagents** — specialized agents invocable with `!agent-name` prefix
- **Project instructions** — auto-loads `AGENTS.md` and `.refio/agent.md` into LLM context; conditional rules via `.refio/rules/*.md` with glob-based activation
- **Custom subagents** — define your own in `.refio/agents/*.md`
- **Token budgeting** — per-section context limits (~28K tokens default)
- **File snapshots** — automatic backup before every write operation
- **Auto-compaction** — prevents context overflow during long agent sessions
- **Parallel tool execution** — READ_ONLY tools run concurrently (~2-3x faster)
- **Native Swing UI** — no WebView, no Electron, pure IntelliJ components

---

## Terminal User Interface (TUI)

Refio includes a standalone CLI with a full-screen TUI that mirrors the IntelliJ plugin GUI. No IDE required — works in any terminal emulator.

### Layout

```
┌─F1:Chat│F2:Steps│F3:Context│F4:RAG│F5:Logs│F6:Debug│F7:API│F8:Set  [CHAT|default] $0.02│5K tok [Ctrl+Q]─┐
├────────────────────────────────────────────────────────────────────────────────────────────────────────────┤
│ ## Architektura                        │ Steps                                                            │
│                                        │                                                                  │
│ Projekt stosuje warstwowa             │  [OK] analyze_codebase                                           │
│ architekture (Layered Architecture):   │  [OK] identify_patterns                                         │
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

- **Split-pane layout** — Chat on the left (55%), active tab on the right (45%). Full-width when Chat tab is active.
- **7 tabs + Settings** — F1-F7 for main tabs, F8 for settings. Tab bar shows mode, cost, and streaming status.
- **Two input modes** — Raw TTY (real terminal: F-keys, Ctrl+Q, single-char dispatch) and line mode (IDE terminal, pipes: /commands, :tab shortcuts).
- **@context autocomplete** — Type `@` for a popup with 14 context prefixes (file, folder, grep, diff, etc.).
- **Settings screen** — 11 sub-tabs covering providers, models, prompts, context/RAG, MCP, tools, subagents, and more.
- **Resize-responsive** — UI adapts to terminal window size changes in real time.
- **Multi-agent visualization** — 8 ANSI colors for agent messages, status badges, streaming indicators.

### Keyboard Shortcuts

#### Navigation

| Key | Action |
|-----|--------|
| F1-F7 | Switch tabs (Chat, Steps, Context, RAG, Logs, Debug, API) |
| F8 | Open Settings screen |
| Ctrl+S | Settings |
| Ctrl+H | Session history |
| Escape | Back to main screen / dismiss popup |
| Ctrl+Q | Quit |
| Arrow Up/Down | Scroll chat / navigate lists |
| Page Up/Down | Scroll chat (10 lines) |

#### Session Management

| Key | Action |
|-----|--------|
| Ctrl+W | **New session** (start a fresh conversation) |
| Ctrl+H | Browse session history (switch to previous sessions) |
| Ctrl+L | Continue conversation (resume after interruption) |
| Ctrl+D | Summarize conversation (compact long history) |

#### Chat & Input

| Key | Action |
|-----|--------|
| Enter | Send message |
| Alt+M | Cycle mode (Chat → Plan → Agent) |
| Ctrl+O | Select model (popup selector) |
| Ctrl+T | Toggle thinking/reasoning mode |
| Ctrl+E | Toggle execution mode (AUTO / INTERACTIVE) |
| Ctrl+N | Toggle no-egress mode (local-only) |
| Ctrl+C | Cancel current operation |
| Arrow Left/Right | Move cursor in input |

#### Message Selection

| Key | Action |
|-----|--------|
| Ctrl+P | Select previous message |
| Ctrl+B | Select next message |
| Ctrl+Y | Copy selected (or last) message to clipboard |
| Ctrl+F | Filter chat by agent (multi-agent mode) |

#### Autocomplete

| Key | Action |
|-----|--------|
| `@` | Open context autocomplete (@file, @folder, @grep, @diff, etc.) |
| `!` | Open subagent autocomplete (!review, !security, etc.) |
| `/` | Open slash command autocomplete (/explain, /refactor, etc.) |
| Tab / Arrow Down | Next autocomplete candidate |
| Arrow Up | Previous autocomplete candidate |
| Enter | Accept autocomplete selection |
| Escape | Dismiss autocomplete |

#### Input Features

- **Multi-line input** — input expands up to 4 visible lines as you type
- **Paste support** — large pastes (>200 chars) show a preview marker
- **Slash commands** — type `/` at the start for prompt templates (/explain, /test, /fix, /refactor, etc.)
- **System commands** — /help, /quit, /clear, /history, /export, /resend, /rewind, /edit, and more (type `/help` for full list)

### Architecture

The TUI is built with **Mordant 3.0.1** (ANSI terminal rendering) and **JLine3 3.26.3** (raw input handling), following an MVVM pattern:

- **TuiViewModel** — 20 StateFlows merged into a single reactive `TuiState`. Any flow change triggers a re-render.
- **TuiRenderer** — Compositor that builds the full screen from `TuiRenderBuffer` components. In-place overwrite with cursor positioning to avoid flicker.
- **TuiRenderBuffer** — ANSI-aware line buffer that handles escape code measurement, truncation, padding, and side-by-side merging for the split-pane layout.
- **TuiInputHandler** — Detects TTY vs dumb terminal and provides raw key dispatch or line-based input accordingly.

```
TuiApp (entry point)
├── TuiViewModel (state management, 20 StateFlows → merge → TuiState)
│   ├── TuiWorkflowListener (streaming bridge)
│   └── TuiChatMessageMapper (AgentEvent → TuiChatMessage)
├── TuiRenderer (full-screen compositor)
│   ├── TuiRenderBuffer (ANSI-aware split-pane composition)
│   ├── TuiChatView (messages + prompt)
│   ├── TuiStepsView, TuiContextView, TuiRagView, TuiLogsView, ...
│   └── TuiSettingsScreen (11 sub-tabs, live ConfigRouter read/write)
├── TuiInputHandler (raw TTY / line mode)
│   └── TuiKeybindings (F-keys, Ctrl+combinations, escape sequences)
└── TuiColors (ANSI palette: roles, agents, status, logs, context)
```

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
    chat: "ollama/qwen2.5:7b"
    coding: "ollama/qwen2.5-coder:7b"
    embedding: "ollama/nomic-embed-text"
```

See [docs/config.md](docs/config.md) for full configuration reference.

---

## Project Status

| | |
|---|---|
| **Version** | 0.0.1 |
| **Status** | Early-stage, actively developed |
| **License** | MIT |
| **Focus** | Stability, deterministic behavior, local-first experience |

---

## Documentation

- [Architecture Reference](docs/ARCHITECTURE.md) — internal architecture, components, data flows
- [Technical Overview](docs/overview.md) — detailed technical documentation
- [Configuration Guide](docs/config.md) — full configuration reference
- [Changelog](CHANGELOG.md) — version history

---

## Privacy

See [PRIVACY.md](PRIVACY.md) for details about local storage, cloud-provider behavior, no-egress mode, and secret handling.

## Contributing

```bash
./gradlew runIde              # Run in sandbox IDE
./gradlew buildPlugin         # Build plugin ZIP
./gradlew detekt              # Static analysis
./gradlew ktlintCheck         # Lint check
./gradlew test                # Run tests
```

**Prerequisites:** JDK 17, IntelliJ IDEA 2024.x, Ollama with `nomic-embed-text` model

---

## License

MIT License. See [LICENSE](LICENSE).
