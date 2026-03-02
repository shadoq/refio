# Refio

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![IntelliJ](https://img.shields.io/badge/IntelliJ-2024.x-orange.svg)](https://www.jetbrains.com/idea/)
[![Version](https://img.shields.io/badge/version-0.0.1-green.svg)](CHANGELOG.md)

**Local-first AI coding assistant for IntelliJ IDEA.**
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

```bash
# 1. Install Ollama + models
ollama pull nomic-embed-text    # Required for RAG embeddings
ollama pull qwen2.5-coder:14b   # Recommended coding model

# 2. Build & install plugin
git clone https://github.com/shadoq/refio.git && cd refio
./gradlew buildPlugin           # Output: build/distributions/refio-*.zip
# Install ZIP via: Settings -> Plugins -> Install from Disk

# 3. Or run in sandbox IDE for development
./gradlew runIde
```

Then open the **Refio** tool window (View -> Tool Windows -> Refio), select a mode, choose your model, and start.

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
- **MCP protocol support** — 16 built-in server presets (GitHub, PostgreSQL, Brave Search, etc.)
- **21 built-in subagents** — specialized agents invocable with `!agent-name` prefix
- **Custom subagents** — define your own in `.refio/agents/*.md`
- **Token budgeting** — per-section context limits (~28K tokens default)
- **File snapshots** — automatic backup before every write operation
- **Auto-compaction** — prevents context overflow during long agent sessions
- **Parallel tool execution** — READ_ONLY tools run concurrently (~2-3x faster)
- **Native Swing UI** — no WebView, no Electron, pure IntelliJ components

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
