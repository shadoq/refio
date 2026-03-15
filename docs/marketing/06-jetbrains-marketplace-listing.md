# JetBrains Marketplace - Kompletne Dane do Listingu

## Informacje Podstawowe

| Pole | Wartość |
|------|---------|
| **Plugin ID** | `pl.jclab.refio` |
| **Nazwa** | Refio |
| **Wersja** | 0.0.1 |
| **Vendor** | jclab team |
| **Email** | refio@shad.net.pl |
| **URL** | https://github.com/shadoq/refio |
| **Licencja** | MIT |
| **Kompatybilność** | IntelliJ IDEA 2024.1 - 2024.3.x (builds 241 - 243.*) |
| **Platforma** | IC (IntelliJ Community), IU (IntelliJ Ultimate) |

---

## 1. Krótki Opis (Plugin Tagline)

**Max 80 znaków:**
```
Local-first AI coding assistant with 50-70% lower API costs
```

**Alternatywy:**
```
AI coding assistant that keeps your code private and costs low
```
```
Free, open-source AI assistant for IntelliJ with local LLM support
```

---

## 2. Opis Pełny (Plugin Description)

### HTML Format (do plugin.xml)

```html
<description><![CDATA[
<h2>Refio - Local-First AI Coding Assistant</h2>

<p><b>The only AI coding assistant that combines native IntelliJ integration with 100% local data control and 50-70% lower API costs.</b></p>

<h3>Why Refio?</h3>
<ul>
  <li><b>50-70% Lower API Costs</b> - Intelligent RAG system minimizes tokens sent to LLM</li>
  <li><b>100% Local Option</b> - Full offline support with Ollama (free!)</li>
  <li><b>Native Integration</b> - Pure Swing UI, no WebView, instant response</li>
  <li><b>Open Source</b> - MIT licensed, fork and customize freely</li>
</ul>

<h3>Three Powerful Modes</h3>
<ul>
  <li><b>Chat Mode</b> - Conversational AI with intelligent code context</li>
  <li><b>Plan Mode</b> - Step-by-step planning with approval workflow</li>
  <li><b>Agent Mode</b> - Autonomous coding with snapshot/rollback safety</li>
</ul>

<h3>Smart Context with @Mentions</h3>
<p>Reference any part of your project with simple @mentions:</p>
<ul>
  <li><code>@file</code> - Select specific files</li>
  <li><code>@codebase</code> - Semantic search across project</li>
  <li><code>@diff</code> - Uncommitted git changes</li>
  <li><code>@problems</code> - IDE compilation errors</li>
  <li>...and 10 more context providers</li>
</ul>

<h3>Multiple LLM Providers</h3>
<ul>
  <li><b>Local (Free):</b> Ollama, LM Studio</li>
  <li><b>Cloud:</b> OpenAI, Anthropic Claude, Google Gemini, OpenRouter</li>
</ul>

<h3>18 MCP Integrations</h3>
<p>Connect to external tools out-of-the-box:</p>
<ul>
  <li>GitHub, GitLab (version control)</li>
  <li>PostgreSQL, SQLite (databases)</li>
  <li>Slack (communication)</li>
  <li>Google Drive, AWS (cloud)</li>
  <li>Brave Search, Exa (web search)</li>
  <li>...and more</li>
</ul>

<h3>Built for Privacy</h3>
<ul>
  <li>All data stored locally in SQLite</li>
  <li>No telemetry, no tracking</li>
  <li>Optional no-egress mode blocks all cloud calls</li>
  <li>Secret redaction in logs</li>
</ul>

<h3>Getting Started</h3>
<ol>
  <li>Install plugin from JetBrains Marketplace</li>
  <li>Configure your LLM provider in Settings → Tools → Refio</li>
  <li>Open Refio tool window (right sidebar)</li>
  <li>Start with <code>@codebase how does authentication work?</code></li>
</ol>

<h3>For Local-Only Setup (Free)</h3>
<pre>
# Install Ollama
curl -fsSL https://ollama.com/install.sh | sh

# Pull required models
ollama pull nomic-embed-text
ollama pull qwen2.5-coder:7b
</pre>

<h3>Links</h3>
<ul>
  <li><a href="https://github.com/shadoq/refio">GitHub Repository</a></li>
  <li><a href="https://github.com/shadoq/refio/wiki">Documentation</a></li>
  <li><a href="https://github.com/shadoq/refio/issues">Report Issues</a></li>
</ul>

<p><b>MIT License</b> | Built with Kotlin | Made for developers who value privacy</p>
]]></description>
```

### Markdown Format (dla strony Marketplace)

```markdown
# Refio - Local-First AI Coding Assistant

**The only AI coding assistant that combines native IntelliJ integration with 100% local data control and 50-70% lower API costs.**

## Why Refio?

| Feature | Benefit |
|---------|---------|
| **50-70% Lower API Costs** | Intelligent RAG system minimizes tokens |
| **100% Local Option** | Full offline support with Ollama (free!) |
| **Native Integration** | Pure Swing UI, no WebView, instant response |
| **Open Source** | MIT licensed, fork and customize freely |

## Three Powerful Modes

### Chat Mode
Conversational AI with intelligent code context. Ask questions, get explanations, receive suggestions.

### Plan Mode
Step-by-step planning with approval workflow. Review each step before execution.

### Agent Mode
Autonomous coding with snapshot/rollback safety. Let AI handle complex multi-step tasks.

## Smart Context with @Mentions

Reference any part of your project:

- `@file` - Select specific files
- `@folder` - Browse directories
- `@codebase` - Semantic search across project
- `@diff` - Uncommitted git changes
- `@commit` - Git commit details
- `@problems` - IDE compilation errors
- `@terminal` - Terminal output
- `@docs` - Indexed documentation
- `@url` - Web content
- `@clipboard` - Clipboard content
- `@recent` - Recently edited files
- `@current` - Currently open file
- `@grep` - Regex search
- `@mcp` - MCP resources

## Multiple LLM Providers

**Local (Free):**
- Ollama (recommended)
- LM Studio

**Cloud:**
- OpenAI (GPT-4o, GPT-4, etc.)
- Anthropic (Claude 3.5, Claude 3.7)
- Google Gemini (2.5 Flash, 2.5 Pro)
- OpenRouter (all providers)

## 18 MCP Integrations

Connect to external tools out-of-the-box:

**Version Control:** GitHub, GitLab
**Databases:** PostgreSQL, SQLite
**Communication:** Slack
**Cloud:** Google Drive, AWS
**Search:** Brave Search, Exa
**Development:** Filesystem, Puppeteer, Custom APIs

## Built for Privacy

- ✅ All data stored locally in SQLite
- ✅ No telemetry, no tracking
- ✅ Optional no-egress mode
- ✅ Secret redaction in logs
- ✅ Path sandboxing for file operations

## Getting Started

1. Install plugin from JetBrains Marketplace
2. Configure your LLM provider in **Settings → Tools → Refio**
3. Open Refio tool window (right sidebar)
4. Start with `@codebase how does authentication work?`

### Local-Only Setup (Free)

```bash
# Install Ollama
curl -fsSL https://ollama.com/install.sh | sh

# Pull required models
ollama pull nomic-embed-text
ollama pull qwen2.5-coder:7b
```

## Keyboard Shortcuts

| Action | Windows/Linux | macOS |
|--------|---------------|-------|
| Add selection to Refio | `Ctrl+I` | `Cmd+I` |
| Add to new session | `Ctrl+Shift+I` | `Cmd+Shift+I` |

## Requirements

- IntelliJ IDEA 2024.1 or newer
- JDK 17+
- Ollama (optional, for local LLM)

## Links

- [GitHub Repository](https://github.com/shadoq/refio)
- [Documentation](https://github.com/shadoq/refio/wiki)
- [Report Issues](https://github.com/shadoq/refio/issues)

---

**MIT License** | Built with Kotlin | Made for developers who value privacy
```

---

## 3. Change Notes (Changelog)

```html
<change-notes><![CDATA[
<h3>0.0.1 - Initial Release</h3>
<ul>
  <li><b>Core Features</b>
    <ul>
      <li>Three modes: Chat, Plan, and Agent</li>
      <li>14 context providers with @mention support</li>
      <li>12 built-in tools (5 read-only, 6 write, including invoke_subagent)</li>
      <li>RAG system with semantic search</li>
    </ul>
  </li>
  <li><b>LLM Support</b>
    <ul>
      <li>Ollama (local, free)</li>
      <li>LM Studio (local)</li>
      <li>OpenAI (GPT-4o, GPT-4)</li>
      <li>Anthropic (Claude 3.5, 3.7)</li>
      <li>Google Gemini (2.5 Flash/Pro)</li>
      <li>OpenRouter</li>
    </ul>
  </li>
  <li><b>MCP Integration</b>
    <ul>
      <li>18 built-in server presets</li>
      <li>STDIO and HTTP/SSE transports</li>
      <li>OAuth support</li>
    </ul>
  </li>
  <li><b>Developer Experience</b>
    <ul>
      <li>Native Swing UI</li>
      <li>Cost tracking dashboard</li>
      <li>Snapshot and rollback for file changes</li>
      <li>Keyboard shortcuts for quick access</li>
    </ul>
  </li>
</ul>
]]></change-notes>
```

---

## 4. Tagi / Keywords

**Primary Keywords (wysokie znaczenie):**
```
ai coding assistant
ai code completion
local llm
ollama intellij
code generation
ai pair programming
```

**Secondary Keywords:**
```
chatgpt intellij
claude intellij
gpt-4 plugin
ai refactoring
code review ai
mcp protocol
rag search
```

**Long-tail Keywords:**
```
local ai coding assistant intellij
free ai coding plugin
privacy-first ai coding
offline ai code assistant
open source ai coding
```

---

## 5. Kategorie

**Primary Category:**
- Code tools

**Secondary Categories:**
- AI Assistant
- Editor Enhancement

---

## 6. Wymagane Screenshoty

### Screenshot 1: Main Chat View
**Filename:** `screenshot-01-chat-view.png`
**Wymiary:** 1280 x 800 px
**Opis:** "Refio Chat Mode - Conversational AI with code context"
**Zawartość:**
- Refio tool window open on right side
- Chat conversation visible
- @codebase mention in use
- Code suggestion in response

### Screenshot 2: Plan Mode
**Filename:** `screenshot-02-plan-mode.png`
**Wymiary:** 1280 x 800 px
**Opis:** "Plan Mode - Step-by-step task planning with approval workflow"
**Zawartość:**
- Plan steps visible
- Approval buttons
- Step status indicators
- Task description

### Screenshot 3: Agent Mode Execution
**Filename:** `screenshot-03-agent-mode.png`
**Wymiary:** 1280 x 800 px
**Opis:** "Agent Mode - Autonomous task execution with progress tracking"
**Zawartość:**
- Agent executing steps
- Progress indicators
- File changes preview
- Rollback option visible

### Screenshot 4: Settings Panel
**Filename:** `screenshot-04-settings.png`
**Wymiary:** 1280 x 800 px
**Opis:** "Easy configuration - Multiple LLM providers supported"
**Zawartość:**
- Settings dialog open
- Provider selection dropdown
- API key field (masked)
- Model selection

### Screenshot 5: Context Mentions
**Filename:** `screenshot-05-mentions.png`
**Wymiary:** 1280 x 800 px
**Opis:** "@Mentions - Smart context injection with 14 providers"
**Zawartość:**
- Input field with @ autocomplete
- Dropdown showing available providers
- Icons for each provider type

### Screenshot 6: MCP Configuration
**Filename:** `screenshot-06-mcp.png`
**Wymiary:** 1280 x 800 px
**Opis:** "MCP Integrations - 18 built-in presets for external tools"
**Zawartość:**
- MCP settings panel
- List of available presets
- Connection status indicators

### Screenshot 7: Cost Tracking
**Filename:** `screenshot-07-cost-tracking.png`
**Wymiary:** 1280 x 800 px
**Opis:** "Cost Tracking - Monitor API usage in real-time"
**Zawartość:**
- Cost dashboard
- Token counts
- Per-session statistics

### Screenshot 8: RAG Search
**Filename:** `screenshot-08-rag.png`
**Wymiary:** 1280 x 800 px
**Opis:** "RAG System - Semantic search finds relevant code automatically"
**Zawartość:**
- RAG panel
- Search results
- Relevance scores
- File previews

---

## 7. Plugin Icon

**Wymagania:**
- Format: SVG (preferowane) lub PNG
- Wymiary: 40x40 px (display), 80x80 px (2x), 512x512 px (Marketplace)
- Tło: Przezroczyste
- Styl: Spójny z IntelliJ icon guidelines

**Aktualny plik:** `/icons/pluginIcon.svg`

**Sugestie dla ikony:**
- Prosty, rozpoznawalny kształt
- Kolory: Gradient niebieski/fioletowy (AI) + zielony akcent (local)
- Motyw: Mózg/AI + tarcza/zamek (privacy) lub kod

---

## 8. Demo Video

**Wymagania:**
- Format: MP4 lub YouTube link
- Długość: 60-180 sekund (zalecane 90s)
- Rozdzielczość: 1080p minimum
- Audio: Opcjonalne (ale zalecane)

**Struktura video (90 sekund):**

| Czas | Scena | Treść |
|------|-------|-------|
| 0:00-0:10 | Intro | Logo + tagline "Local-First AI Coding" |
| 0:10-0:25 | Problem | "AI tools cost $40/mo and send code to cloud" |
| 0:25-0:40 | Chat Demo | @codebase query, otrzymanie odpowiedzi |
| 0:40-0:55 | Agent Demo | Multi-step task execution |
| 0:55-1:10 | Features | MCP, cost tracking, local LLM |
| 1:10-1:20 | Install | "Install from Marketplace" |
| 1:20-1:30 | CTA | GitHub stars, free + open source |

---

## 9. Compatibility Matrix

```xml
<idea-version since-build="241" until-build="243.*"/>
```

| IDE | Wersja Min | Wersja Max | Status |
|-----|------------|------------|--------|
| IntelliJ IDEA Community | 2024.1 | 2024.3.x | ✅ Supported |
| IntelliJ IDEA Ultimate | 2024.1 | 2024.3.x | ✅ Supported |
| PyCharm | 2024.1 | 2024.3.x | ⚠️ Untested |
| WebStorm | 2024.1 | 2024.3.x | ⚠️ Untested |
| Android Studio | Koala+ | - | ⚠️ Untested |

---

## 10. Dependencies

```xml
<depends>com.intellij.modules.platform</depends>
```

**Brak zewnętrznych zależności plugin-to-plugin.**

---

## 11. Sekcja "Additional Info"

**Bug Tracker URL:**
```
https://github.com/shadoq/refio/issues
```

**Documentation URL:**
```
https://github.com/shadoq/refio/wiki
```

**Source Code URL:**
```
https://github.com/shadoq/refio
```

**Privacy Policy URL:** *(opcjonalne, ale zalecane)*
```
https://github.com/shadoq/refio/blob/main/PRIVACY.md
```

---

## 12. Checklist Przed Publikacją

### Techniczne
- [ ] Plugin builds without errors (`./gradlew buildPlugin`)
- [ ] Plugin runs in sandbox IDE (`./gradlew runIde`)
- [ ] All features work as documented
- [ ] No API keys hardcoded
- [ ] plugin.xml is valid
- [ ] Icons are present and correct size

### Marketplace Listing
- [ ] Short description ≤80 characters
- [ ] Full description complete (HTML)
- [ ] Change notes written
- [ ] 5-8 screenshots prepared (1280x800)
- [ ] Plugin icon 512x512 ready
- [ ] Demo video recorded (optional but recommended)
- [ ] All links working

### Legal
- [ ] LICENSE file present (MIT)
- [ ] PRIVACY.md linked and published
- [ ] No trademark issues
- [ ] Dependencies licenses compatible

### Quality
- [ ] Tested on IntelliJ 2024.1
- [ ] Tested on IntelliJ 2024.2
- [ ] Tested on IntelliJ 2024.3
- [ ] Memory usage acceptable
- [ ] No performance issues
- [ ] Error handling in place

---

## 13. Submission Process

### Krok 1: Przygotowanie
1. Zbuduj plugin: `./gradlew buildPlugin`
2. Output: `build/distributions/refio-0.0.1.zip`
3. Przetestuj lokalnie

### Krok 2: JetBrains Account
1. Zaloguj się na https://plugins.jetbrains.com
2. Idź do "Upload Plugin"

### Krok 3: Upload
1. Wybierz plik ZIP
2. Wypełnij formularz (użyj danych z tego dokumentu)
3. Dodaj screenshoty
4. Ustaw kategorię

### Krok 4: Review
1. JetBrains review (1-3 dni robocze)
2. Możliwe poprawki
3. Publikacja

### Krok 5: Po Publikacji
1. Ogłoś na social media
2. Aktualizuj GitHub README z linkiem
3. Monitoruj reviews i issues

---

## 14. Post-Publication Optimization

### Tydzień 1-2
- Monitoruj reviews
- Odpowiadaj na wszystkie komentarze
- Szybko naprawiaj zgłoszone błędy
- Zbieraj feedback

### Miesiąc 1
- Publikuj aktualizacje (bugfixes)
- Aktualizuj screenshoty jeśli UI się zmieni
- Dodaj więcej keywords bazując na search data
- Proś o reviews od zadowolonych użytkowników

### Ongoing
- Regularne aktualizacje (min. 1x/miesiąc)
- Odpowiadaj na reviews w <24h
- Aktualizuj kompatybilność z nowymi wersjami IDE
- Śledź konkurencję

---

## 15. Gotowy plugin.xml (Updated)

```xml
<!-- Plugin Configuration File -->
<idea-plugin>
  <id>pl.jclab.refio</id>
  <name>Refio</name>
  <version>0.0.1</version>

  <vendor email="refio@shad.net.pl" url="https://github.com/shadoq/refio">jclab team</vendor>

  <description><![CDATA[
    <h2>Refio - Local-First AI Coding Assistant</h2>

    <p><b>The only AI coding assistant that combines native IntelliJ integration
    with 100% local data control and 50-70% lower API costs.</b></p>

    <h3>Why Refio?</h3>
    <ul>
      <li><b>50-70% Lower API Costs</b> - Intelligent RAG system minimizes tokens</li>
      <li><b>100% Local Option</b> - Full offline support with Ollama (free!)</li>
      <li><b>Native Integration</b> - Pure Swing UI, no WebView, instant response</li>
      <li><b>Open Source</b> - MIT licensed, fork and customize freely</li>
    </ul>

    <h3>Three Powerful Modes</h3>
    <ul>
      <li><b>Chat Mode</b> - Conversational AI with intelligent code context</li>
      <li><b>Plan Mode</b> - Step-by-step planning with approval workflow</li>
      <li><b>Agent Mode</b> - Autonomous coding with snapshot/rollback safety</li>
    </ul>

    <h3>Smart Context with @Mentions</h3>
    <p>14 context providers: @file, @codebase, @diff, @problems, @docs, and more</p>

    <h3>Multiple LLM Providers</h3>
    <ul>
      <li><b>Local (Free):</b> Ollama, LM Studio</li>
      <li><b>Cloud:</b> OpenAI, Anthropic, Google Gemini, OpenRouter</li>
    </ul>

    <h3>18 MCP Integrations</h3>
    <p>GitHub, GitLab, PostgreSQL, SQLite, Slack, Google Drive, AWS, and more</p>

    <h3>Built for Privacy</h3>
    <ul>
      <li>All data stored locally in SQLite</li>
      <li>No telemetry, no tracking</li>
      <li>Optional no-egress mode</li>
    </ul>

    <p><a href="https://github.com/shadoq/refio">GitHub</a> |
    <a href="https://github.com/shadoq/refio/wiki">Documentation</a></p>

    <p><b>MIT License</b> | Built with Kotlin</p>
  ]]></description>

  <change-notes><![CDATA[
    <h3>0.0.1 - Initial Release</h3>
    <ul>
      <li>Three modes: Chat, Plan, and Agent</li>
      <li>14 context providers with @mention support</li>
      <li>6 LLM providers (Ollama, OpenAI, Anthropic, Gemini, OpenRouter, LM Studio)</li>
      <li>18 MCP integrations</li>
      <li>RAG system with semantic search</li>
      <li>Cost tracking dashboard</li>
      <li>Native Swing UI</li>
    </ul>
  ]]></change-notes>

  <idea-version since-build="241" until-build="243.*"/>

  <depends>com.intellij.modules.platform</depends>

  <!-- Extensions and actions remain the same -->
</idea-plugin>
```

---

## 16. Comparison Table for Marketing

| Feature | Refio | JetBrains AI | GitHub Copilot | Cursor |
|---------|-------|--------------|----------------|--------|
| **Price** | Free | $10/mo | $10-39/mo | $20-40/mo |
| **IntelliJ Native** | ✅ | ✅ | Plugin | ❌ |
| **Local LLM** | ✅ Ollama | ❌ | ❌ | ❌ |
| **Open Source** | ✅ MIT | ❌ | ❌ | ❌ |
| **MCP Support** | ✅ 16 presets | ❌ | ❌ | ✅ |
| **Agent Mode** | ✅ | Limited | Limited | ✅ |
| **RAG** | ✅ Built-in | ❌ | ❌ | ✅ |
| **Cost Tracking** | ✅ | ❌ | ❌ | ✅ |
| **No-Egress Mode** | ✅ | ❌ | ❌ | ❌ |

*Use this table in Marketplace description and marketing materials.*
