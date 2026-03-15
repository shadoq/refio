# JetBrains Marketplace Listing — v2 (Improved)

## Short Description (≤ 80 znaków)

```
Deterministic AI agent for IntelliJ — transparent, local-first, open source
```

Alternatywy:
```
AI coding agent with full execution control — local models, snapshots, no black box
```
```
Open-source AI agent for IntelliJ: control, transparency, local-first workflows
```

---

## Full Description — HTML (plugin.xml / Marketplace)

```html
<description><![CDATA[

<h2>Refio — Deterministic AI Agent for IntelliJ IDEA</h2>

<p><b>Built for developers who want control — not black-box automation.</b></p>

<p>Most AI coding tools send your code to opaque cloud prompts, make unpredictable
changes, and give you no insight into what's happening. Refio is different:
every prompt is inspectable, every tool call is explicit, every file change is
snapshotted and reversible. You choose the level of autonomy — the agent never
acts beyond what you allow.</p>

<h3>Why developers choose Refio</h3>

<ul>
  <li><b>Deterministic execution</b> — know exactly what's sent to the model.
      Transparent context, explicit tool calls, no hidden injections.</li>
  <li><b>Three execution modes</b> — Chat (think), Plan (inspect), Agent (execute).
      You pick the level of autonomy. Plan mode is read-only by design.</li>
  <li><b>Safe by default</b> — Agent mode creates file snapshots before every edit.
      One click to roll back any change.</li>
  <li><b>Local-first</b> — run 100% offline with Ollama. Free. Your code never
      leaves your machine. No telemetry, no tracking.</li>
  <li><b>Works with ANY model</b> — universal tool-calling protocol works even with
      local models that don't support native function calling.</li>
  <li><b>50-70% lower API costs</b> — semantic RAG sends only relevant code fragments,
      not the whole codebase.</li>
  <li><b>Native IntelliJ UI</b> — pure Swing, no WebView lag. Feels like a
      first-party JetBrains tool.</li>
  <li><b>Open source</b> — MIT licensed. Inspect, fork, extend freely.</li>
</ul>

<h3>Three Modes — You Choose the Control Level</h3>

<ul>
  <li><b>Chat — Think</b>: Ask questions, get explanations, explore code.
      No file changes, no tools.</li>
  <li><b>Plan — Inspect</b>: Model reads your codebase and builds a step-by-step
      action plan. Guaranteed read-only. You approve before anything executes.</li>
  <li><b>Agent — Execute</b>: Autonomous editing across files. 12 tools, automatic
      snapshots, subtask tracking, and turn-based execution. Always reversible.</li>
</ul>

<h3>21 Built-in Specialized Subagents</h3>

<p>Invoke expert agents for specific tasks:</p>
<ul>
  <li><code>!code-reviewer</code> — thorough code review with actionable suggestions</li>
  <li><code>!security-reviewer</code> — OWASP audit: injection, auth, data exposure</li>
  <li><code>!refactor</code>, <code>!test-writer</code>, <code>!documenter</code> — and 16 more</li>
  <li>Define your own in Markdown + YAML frontmatter</li>
</ul>

<h3>Transparent Context — 14 @Mention Providers</h3>

<p>You control exactly what the model sees:</p>
<ul>
  <li><code>@codebase</code> — semantic RAG search: relevant fragments, not the whole repo</li>
  <li><code>@diff</code> — uncommitted git changes only</li>
  <li><code>@problems</code> — live IDE compilation errors</li>
  <li><code>@terminal</code> — recent terminal output</li>
  <li><code>@file</code>, <code>@folder</code>, <code>@url</code>, <code>@commit</code>,
      <code>@docs</code>, <code>@clipboard</code>, <code>@grep</code> — and more</li>
</ul>

<h3>All Major LLM Providers</h3>

<ul>
  <li><b>Local (Free):</b> Ollama, LM Studio — offline, private, $0</li>
  <li><b>Cloud:</b> OpenAI (GPT-4o, o3), Anthropic (Claude 3.7, Opus 4),
      Google Gemini (2.5 Flash/Pro), OpenRouter (100+ models)</li>
</ul>

<h3>18 MCP Integrations — Ready Out of the Box</h3>

<p>Connect to external tools with one click:</p>
<ul>
  <li><b>Version control:</b> GitHub, GitLab</li>
  <li><b>Databases:</b> PostgreSQL, SQLite</li>
  <li><b>Search:</b> Brave Search, Exa</li>
  <li><b>Cloud &amp; storage:</b> Google Drive, AWS</li>
  <li><b>Communication:</b> Slack</li>
  <li><b>Dev tools:</b> Filesystem, Puppeteer, custom APIs</li>
</ul>

<h3>Safety &amp; Privacy</h3>

<ul>
  <li>Plan mode is read-only — guaranteed no file changes during analysis</li>
  <li>Agent mode snapshots every file before editing — one-click rollback</li>
  <li>All data stored locally in SQLite — no cloud sync, no telemetry</li>
  <li>No-egress mode: completely block all cloud LLM calls</li>
  <li>Path sandboxing: agent can only touch files inside your project</li>
</ul>

<h3>Get Started in 3 Steps</h3>

<ol>
  <li>Install from JetBrains Marketplace</li>
  <li>Open the Refio panel (right sidebar) and go to <b>Refio → Settings</b></li>
  <li>Pick your LLM — or run locally:
      <code>ollama pull nomic-embed-text &amp;&amp; ollama pull qwen2.5-coder:7b</code></li>
</ol>

<h4>Free local setup with Ollama:</h4>
<pre>
ollama pull nomic-embed-text   # required for RAG
ollama pull qwen2.5-coder:7b   # recommended coding model
</pre>

<h3>Requirements</h3>
<ul>
  <li>IntelliJ IDEA 2024.1 or newer (Community or Ultimate)</li>
  <li>JDK 17+</li>
  <li>Ollama (optional — for local LLM)</li>
</ul>

<p>
  <a href="https://plugins.jetbrains.com/plugin/30487-refio">Marketplace</a> |
  <a href="https://github.com/shadoq/refio">GitHub (MIT)</a> |
  <a href="https://github.com/shadoq/refio/issues">Report Issues</a>
</p>

<p><i>Built with Kotlin. Made for developers who care about privacy and cost.</i></p>

]]></description>
```

---

## Change Notes — HTML

```html
<change-notes><![CDATA[
<h3>0.0.1 — Initial Release</h3>

<h4>Core</h4>
<ul>
  <li>Universal model protocol — works with models without native tool calling</li>
  <li>Three execution modes: Chat, Plan, Agent</li>
  <li>AgentTurnLoop — self-directing turn-based execution (Codex CLI-style)</li>
  <li>Snapshot and rollback for all agent file changes</li>
</ul>

<h4>Context System</h4>
<ul>
  <li>14 @mention context providers</li>
  <li>RAG semantic search with 5 language analyzers (Kotlin, Java, Python, TS, HTML)</li>
  <li>Token budget system — scales automatically to model context window size</li>
  <li>Tool result compression (3 levels) and conversation compaction at 85% usage</li>
</ul>

<h4>Models &amp; Providers</h4>
<ul>
  <li>6 LLM adapters: Ollama, LM Studio, OpenAI, Anthropic, Google Gemini, OpenRouter</li>
  <li>Cost tracking dashboard with per-session statistics</li>
</ul>

<h4>Extensibility</h4>
<ul>
  <li>21 built-in specialized subagents</li>
  <li>18 MCP server presets (GitHub, PostgreSQL, Slack, AWS, and more)</li>
  <li>Custom subagents via Markdown + YAML frontmatter</li>
</ul>
]]></change-notes>
```

---

## Tagline — krotki opis (opcje rankingowe)

Rekomendowana kolejność do testów A/B:

| # | Tekst | Dlaczego |
|---|-------|----------|
| 1 | `AI coding assistant that works with ANY model — local or cloud` | Uderza w główny wyróżnik, jasny benefit |
| 2 | `Local-first AI for IntelliJ: Ollama, Claude, GPT-4 — one plugin` | SEO: nazwy modeli/providerów |
| 3 | `Free AI coding assistant with RAG, agents and local LLM support` | Keywords: free, RAG, agents, local |

---

## Keywords / Tagi (zoptymalizowane)

**Tier 1 — Wysoki wolumen wyszukiwań:**
```
ai coding assistant
ai agent intellij
local llm intellij
ollama intellij
ai code generation
deterministic ai
```

**Tier 2 — Intencja zakupu:**
```
ai agent autonomous coding
code review ai
rag code search
mcp intellij
local ai coding
open source ai coding
```

**Tier 3 — Long-tail / niszowe:**
```
deterministic ai agent intellij
transparent ai coding
offline ai coding intellij
ollama intellij plugin
ai coding no black box
```

---

## Co zmieniono vs v1 i dlaczego

| Element | v1 | v2 | v3 (aktualna) | Powód |
|---------|----|----|----------------|-------|
| **Headline** | "50-70% lower costs" | "works with ANY model" | "Deterministic AI Agent" | Unikalne, spójne z landing page |
| **Pierwsze zdanie** | Feature list | "Your code stays on your machine" | "Built for developers who want control" | Trafia w ból: unpredictable AI |
| **Hierarchia USP** | Costs → Local | Local → Universal | Control → Local → Costs | Control jest najsilniejszym wyróżnikiem |
| **Modes description** | Feature list | Feature list | "Think / Inspect / Execute" | Podkreśla celowe wybieranie autonomii |
| **Privacy sekcja** | Standalone | Standalone | Zintegrowana z Safety | Rollback + read-only są częścią "control" story |
| **@mentions header** | "Smart Context" | "Smart Context" | "Transparent Context" | "Transparent" wzmacnia główny kąt |
| **Tagline** | "50-70% lower costs" | "local-first" | "deterministic / control" | Spójne z landing page |
