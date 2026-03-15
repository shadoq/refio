# Refio - Analiza Projektu i Pozycjonowanie

## Executive Summary

**Refio** to local-first AI coding assistant dla IntelliJ IDEA, napisany w 100% w Kotlin. Plugin dziaĹ‚a w procesie IDE (bez HTTP), przechowuje dane lokalnie w SQLite i uĹĽywa natywnych komponentĂłw Swing.

**Kluczowa filozofia:** Minimalizacja kontekstu LLM poprzez RAG i analizÄ™ kodu zamiast wysyĹ‚ania wszystkiego do modelu.

---

## 1. Profil Produktu

### 1.1 Dane Techniczne

| Parametr | WartoĹ›Ä‡ |
|----------|---------|
| **Wersja** | v0.0.1 |
| **JÄ™zyk** | Kotlin 100% |
| **LOC** | ~6,600 linii w 285 plikach |
| **Platforma** | IntelliJ IDEA 2024.x+ |
| **Licencja** | MIT (Open Source) |
| **Baza danych** | SQLite (WAL mode) |
| **UI** | Native Swing (brak WebView) |

### 1.2 Architektura

```
â”Śâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
â”‚        UI Layer (IntelliJ Swing)        â”‚
â”śâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¤
â”‚     Session Manager (8 komponentĂłw)     â”‚
â”śâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¤
â”‚   Workflow Orchestrator (Intent-based)  â”‚
â”śâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¤
â”‚     CoreApiRouter (10 domain routers)   â”‚
â”śâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¤
â”‚ Services | Tools | LLM | RAG | MCP      â”‚
â”śâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¤
â”‚           SQLite (Exposed ORM)          â”‚
â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”
```

---

## 2. FunkcjonalnoĹ›ci Kluczowe

### 2.1 Trzy Tryby Pracy

| Tryb | Opis | Use Case |
|------|------|----------|
| **CHAT** | Konwersacja z LLM o projekcie | Pytania, wyjaĹ›nienia, konsultacje |
| **PLAN** | Generowanie planu, zatwierdzanie krokĂłw | Planowanie refaktoryzacji, zmian |
| **AGENT** | PeĹ‚ny read/write z autonomicznym wykonaniem | Automatyczne zmiany kodu |

### 2.2 System NarzÄ™dzi (12 Tools)

**Read-Only (5):**
- `read_file` - Odczyt pliku (limit 2MB)
- `read_directory` - Listowanie katalogĂłw
- `file_search` - Glob pattern search
- `grep_search` - Regex content search
- `view_diff` - PorĂłwnanie plikĂłw

**Write (6):**
- `create_new_file` - Tworzenie plikĂłw
- `code_editing` - Search-and-replace (free)
- `multi_edit` - Atomic multi-file edit
- `multi_line_editor` - LLM-assisted (~$0.02)
- `advance_code_editing` - Full file regeneration (~$0.06)
- `invoke_subagent` - Delegacja do wbudowanego lub wlasnego subagenta

### 2.3 Wsparcie LLM (6 AdapterĂłw)

| Provider | Modele | Typ |
|----------|--------|-----|
| **Anthropic** | Claude 3.5/3.7, Opus 4.1 | Cloud |
| **OpenAI** | GPT-4o, GPT-5.x, O1/O3 | Cloud |
| **Gemini** | 2.5 Flash/Pro | Cloud |
| **Ollama** | Lokalne modele | Local (FREE) |
| **OpenRouter** | Wszystkie | Gateway |
| **LM Studio** | Lokalne modele | Local (FREE) |

### 2.4 MCP (Model Context Protocol) - 18 PresetĂłw

**Wbudowane integracje:**
- GitHub, GitLab (Version Control)
- PostgreSQL, SQLite (Databases)
- Brave Search, Exa (Search)
- Google Drive, AWS (Cloud)
- Slack (Communication)
- Puppeteer, Filesystem (Development)

### 2.5 System Kontekstu (@Mentions) - 14 Providers

```
@file      â†’ Interaktywny picker plikĂłw
@folder    â†’ PrzeglÄ…darka katalogĂłw
@current   â†’ Aktualnie otwarty plik
@recent    â†’ Ostatnio edytowane pliki@open      -> Wszystkie otwarte pliki
@codebase  â†’ RAG semantic search
@docs      â†’ Zindeksowana dokumentacja
@url       â†’ Pobieranie treĹ›ci z web
@grep      â†’ Regex search
@commit    â†’ SzczegĂłĹ‚y git commit
@diff      â†’ Niezatwierdzone zmiany
@terminal  â†’ Output terminala
@problems  â†’ BĹ‚Ä™dy kompilacji IDE
@clipboard â†’ ZawartoĹ›Ä‡ schowka
```

### 2.6 RAG System

- **5 analizatorĂłw jÄ™zykĂłw:** Kotlin, Java, Python, TypeScript, HTML
- **Chunking liniowy z analizÄ… jÄ™zykowÄ…:** Fragmenty kodu indeksowane dla RAG bez deklarowanego semantic chunking
- **Embeddings:** OpenAI text-embedding-3-small / Ollama nomic-embed-text
- **Hybrid search:** Semantic + keyword
- **Automatic indexing:** Przy starcie IDE z checksum detection

### 2.7 Subagents System

21 wbudowanych wyspecjalizowanych subagentow wywolywanych przez `!agent-name`:
- `!security-engineer` - Audyty bezpieczenstwa i analiza ryzyka
- `!code-reviewer` - Code review
- `!technical-writer`, `!frontend-developer`, `!workflow-orchestrator` i 17 kolejnych
- Custom agents w Markdown + YAML
---

## 3. Unique Selling Points (USP)

### 3.1 USP #1: 50-70% Redukcja KosztĂłw API

```
Tradycyjne podejĹ›cie:           Refio:
â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€          â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
â€˘ CaĹ‚e repozytorium            â€˘ Semantic search
â€˘ PeĹ‚na historia               â€˘ Relevant chunks
â€˘ Wszystkie zaleĹĽnoĹ›ci         â€˘ Smart summaries
= ~500K tokenĂłw               = ~150K tokenĂłw
```

**KorzyĹ›Ä‡:** $100/miesiÄ…c â†’ $30-50/miesiÄ…c przy tym samym uĹĽyciu

### 3.2 USP #2: True Local-First

| Aspekt | Refio | Konkurencja |
|--------|-------|-------------|
| Praca offline | âś… 100% z Ollama | âťŚ/CzÄ™Ĺ›ciowa |
| Dane lokalne | âś… SQLite w projekcie | âťŚ/Cloud sync |
| No-egress mode | âś… Wbudowany | âťŚ |
| HTTP calls | âś… Brak (in-process) | âťŚ API calls |

### 3.3 USP #3: Natywna Integracja IntelliJ

- **Pure Swing UI** - brak WebView, bĹ‚yskawiczna reakcja
- **In-process CoreApiRouter** - wszystko w JVM
- **Deep IDE integration** - completion, actions, tool windows

### 3.4 USP #4: PeĹ‚ne MCP Ecosystem

- 18 wbudowanych presetĂłw vs konfiguracja rÄ™czna
- STDIO + HTTP/SSE + OAuth support
- Dynamiczne rozszerzanie moĹĽliwoĹ›ci

### 3.5 USP #5: Turn-Based Execution With Explicit Control

Aktualny model wykonania:
1. Model planuje nastepny ruch w turze
2. Refio wykonuje jawne narzedzia lub konczy odpowiedz
3. Wyniki trafiaja do kolejnej tury
4. Uzytkownik zachowuje kontrole przez trzy tryby pracy i snapshoty
---

## 4. Grupa Docelowa

### 4.1 Primary Target: Enterprise Java/Kotlin Developers

**Profil:**
- UĹĽywajÄ… IntelliJ IDEA Professional
- PracujÄ… z duĹĽymi codebase (100K+ LOC)
- MajÄ… ograniczenia security (no cloud data)
- LiczÄ… koszty API

**Pain Points:**
- GitHub Copilot/Cursor nie obsĹ‚ugujÄ… IntelliJ natywnie
- JetBrains AI Assistant jest drogi i zamkniÄ™ty
- Brak kontroli nad danymi wysyĹ‚anymi do chmury

### 4.2 Secondary Target: Cost-Conscious Startups

**Profil:**
- MaĹ‚e zespoĹ‚y (2-10 dev)
- Ograniczony budĹĽet na tooling
- PreferujÄ… open-source

**Pain Points:**
- $10-40/dev/miesiÄ…c to znaczÄ…cy koszt
- ChcÄ… AI assistance bez vendor lock-in

### 4.3 Tertiary Target: Privacy-Focused Developers

**Profil:**
- Fintech, Healthcare, Government contractors
- Strict compliance requirements
- Need audit trails

**Pain Points:**
- Nie mogÄ… wysyĹ‚aÄ‡ kodu do cloud AI
- PotrzebujÄ… local-only solution

---

## 5. Competitive Positioning

### 5.1 Positioning Statement

> **Refio** to jedyny AI coding assistant dla IntelliJ IDEA, ktĂłry oferuje **100% local-first** architekturÄ™ z **50-70% niĹĽszymi kosztami API** dziÄ™ki inteligentnemu RAG, przy zachowaniu **peĹ‚nej kontroli nad danymi**.

### 5.2 Positioning Matrix

```
                    Cloud-First â†â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â†’ Local-First
                         â”‚                           â”‚
    High Cost â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”Ľâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”Ľâ”€â”€â”€ Low Cost
                         â”‚    Cursor                 â”‚
                         â”‚      â†‘                    â”‚
                         â”‚  Copilot                  â”‚
                         â”‚      â†‘                    â”‚
                         â”‚  JetBrains AI            â… Refio
                         â”‚                           â”‚
                         â”‚              Continue â†â”€â”€â”€â”¤
                         â”‚                           â”‚
```

### 5.3 Key Differentiators vs Competition

| vs | Refio Advantage |
|----|-----------------|
| **GitHub Copilot** | Native IntelliJ, local-first, 50% taĹ„szy |
| **Cursor** | Nie wymaga zmiany IDE, local-first, 60% taĹ„szy |
| **JetBrains AI** | Open-source, 70% taĹ„szy, peĹ‚na kontrola |
| **Continue** | MCP presets, turn-based execution, native UI |
| **Codeium/Windsurf** | Agent mode, subagents, deep IntelliJ integration |

---

## 6. Value Proposition Canvas

### 6.1 Customer Jobs
- Pisanie kodu szybciej
- Zrozumienie legacy code
- Refaktoryzacja duĹĽych systemĂłw
- Utrzymanie compliance (data locality)
- Kontrola kosztĂłw

### 6.2 Pains
- Wysokie koszty API ($30-100/dev/miesiÄ…c)
- Brak kontroli nad danymi
- SĹ‚aba integracja z IntelliJ
- Vendor lock-in

### 6.3 Gains
- ProduktywnoĹ›Ä‡ +76% (branĹĽowy standard)
- Koszty -50-70%
- PeĹ‚na prywatnoĹ›Ä‡ danych
- Open-source (fork moĹĽliwy)

### 6.4 Pain Relievers (Refio)
- RAG minimalizuje tokeny â†’ niĹĽsze koszty
- SQLite + Ollama â†’ 100% local
- Native Swing â†’ bez kompromisĂłw UX
- MIT license â†’ zero vendor lock-in

### 6.5 Gain Creators (Refio)
- 3 tryby pracy â†’ elastycznoĹ›Ä‡
- MCP ecosystem â†’ rozszerzalnoĹ›Ä‡
- Subagents â†’ specjalizacja
- Intent routing â†’ inteligentne workflow

---

## 7. Pricing Strategy Recommendations

### 7.1 Konkurencyjny Benchmark

| Tool | Cena/miesiÄ…c | Model |
|------|--------------|-------|
| GitHub Copilot | $10-39 | Subscription |
| Cursor | $20-40 | Subscription + usage |
| JetBrains AI | $10/miesiÄ…c + IDE | Bundle |
| Windsurf | $15 | Subscription |
| Continue | $0 | Open-source |
| **Refio** | $0 | Open-source |

### 7.2 Rekomendowany Model

**Open-Core Strategy:**

| Tier | Cena | ZawartoĹ›Ä‡ |
|------|------|-----------|
| **Community** | $0 | Core plugin, Ollama support, basic MCP |
| **Pro** | $12/miesiÄ…c | Priority support, advanced MCP presets, custom subagents |
| **Enterprise** | $29/dev/miesiÄ…c | SSO, audit logs, dedicated support, SLA |

### 7.3 Monetization Options

1. **Cloud Embeddings Service** - hosted embedding API dla tych, ktĂłrzy nie chcÄ… Ollama
2. **MCP Marketplace** - premium integracje (Jira, Confluence, etc.)
3. **Training & Consulting** - enterprise onboarding
4. **Custom Development** - enterprise-specific features

---

## 8. SWOT Analysis

### Strengths (Mocne strony)
- âś… 100% local-first architecture
- âś… 50-70% cost reduction
- âś… Native IntelliJ integration
- âś… Open-source (MIT)
- âś… Full Kotlin codebase
- âś… MCP ecosystem support

### Weaknesses (SĹ‚abe strony)
- âš ď¸Ź Early stage (v0.0.1)
- âš ď¸Ź Only IntelliJ (no VS Code)
- âš ď¸Ź Small community
- âš ď¸Ź No marketing presence
- âš ď¸Ź Known security issues (PathSandbox)

### Opportunities (Szanse)
- đźš€ RosnÄ…cy rynek ($7B â†’ $24B do 2030)
- đźš€ Enterprise shift to local-first
- đźš€ Growing privacy concerns
- đźš€ JetBrains ecosystem dominance in enterprise
- đźš€ MCP standard adoption

### Threats (ZagroĹĽenia)
- âšˇ JetBrains moĹĽe ulepszyÄ‡ AI Assistant
- âšˇ GitHub Copilot moĹĽe dodaÄ‡ better IntelliJ support
- âšˇ Continue moĹĽe dodaÄ‡ native UI
- âšˇ Fast-moving competitive landscape

---

## 9. Go-to-Market Readiness

### 9.1 Product Readiness Checklist

| Aspekt | Status | Priority |
|--------|--------|----------|
| Core functionality | âś… Ready | - |
| Documentation | âš ď¸Ź Partial | High |
| Website/Landing | âťŚ Missing | Critical |
| Demo video | âťŚ Missing | Critical |
| JetBrains Marketplace | âťŚ Not listed | Critical |
| Security audit | âš ď¸Ź Known issues | High |
| Test coverage | âš ď¸Ź Basic | Medium |

### 9.2 Pre-Launch Requirements

1. **Critical:** Fix PathSandbox vulnerability
2. **Critical:** Landing page + demo video
3. **Critical:** JetBrains Marketplace listing
4. **High:** Comprehensive documentation
5. **High:** Getting started guide
6. **Medium:** More test coverage

---

## 10. Key Metrics to Track

### 10.1 Product Metrics
- Plugin downloads (JetBrains Marketplace)
- Active installations
- Sessions per user per week
- Token savings ratio (actual vs baseline)
- Error rate

### 10.2 Business Metrics
- GitHub stars
- Community size (Discord/Slack)
- Contributors
- Enterprise inquiries
- NPS score

### 10.3 Marketing Metrics
- Website traffic
- Demo video views
- Social mentions
- Blog post reads
- Email signups


