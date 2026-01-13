# Architektura UI dla Refio MVP

> **⚠️ Status dokumentu:**
> Opisuje rzeczywistą implementację v1.0 (zweryfikowaną ze zrzutów ekranu) oraz oryginalny plan.
> Rzeczywista implementacja jest **prostsza** niż oryginalny plan.

## 1. Rzeczywista implementacja v1.0

### 1.1 Hierarchia wizualna

```
┌─────────────────────────────────────────────────────────────┐
│  TOOL WINDOW HEADER                                         │
│  Refio                   [New Session]        🎨 ☀️ ⚙️ ❓  │
├─────────────────────────────────────────────────────────────┤
│  CONTENT TABS                                               │
│  [Chat] [Steps] [Logs] [Debug State] [Debug Theme]         │
├─────────────────────────────────────────────────────────────┤
│  MAIN CONTENT AREA (Full Width)                            │
│  - Chat: Message list + conversation                        │
│  - Steps: Step cards queue (empty state: "No steps planned")│
│  - Logs: Log entries                                        │
│  - Debug: Debug panels                                      │
├─────────────────────────────────────────────────────────────┤
│  PROMPT INPUT                                               │
│  [Multi-line text input]                                    │
├─────────────────────────────────────────────────────────────┤
│  BOTTOM CONTROL PANEL                                       │
│  [Chat ▾] [Ollama/qwen2.5:7b ▾] 💡 🛡️              [Send]  │
├─────────────────────────────────────────────────────────────┤
│  STATUS BAR (3 linie)                                       │
│  🟢 Core: Ready | CPU: 0% | RAM: 0MB | 559 px              │
│  Session: 0.0K tokens | Context: 0% (0/8.2K) | Cost: $0.00 │
│  Execution: Step 0/0                          [■ Stop]      │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 Kluczowe komponenty

**Tool Window Header:**
- Tytuł: "Refio"
- Przycisk: [New Session]
- Ikony: 🎨 Theme, ☀️ Light/Dark, ⚙️ Settings, ❓ Help

**Content Tabs:**
- Chat (default) - konwersacja z LLM
- Steps - kolejka kroków agenta
- Logs - szczegółowe logi
- Debug State - stan debugowania
- Debug Theme - motywy debugowania

**Bottom Control Panel:**
- Mode Selector: Dropdown [Chat | Plan | Agent]
- Model Selector: Dropdown "Provider/Model"
- Thinking Toggle: 💡 (żółta lampka)
- No-Egress Toggle: 🛡️ (tarcza)
- Send Button: Primary action

**Status Bar (3 linie):**
1. System: Core health, CPU, RAM, window width
2. Session: Tokens, Context (progress bar + %), Cost
3. Execution: Current step count, Stop button

### 1.3 Chat View (obserwacje ze screenshotów)

- Banner informacyjny: "Chat | interactive" (niebieski)
- Wiadomości użytkownika: prawo, niebieskie tło
- Błędy LLM: czerwony tekst (np. "Connection refused: getsockopt")
- Empty state (do zaimplementowania): suggested prompts

### 1.4 Steps View (obserwacje ze screenshotów)

- Empty state: "No steps planned" (wyśrodkowany)
- Sugestia: "Use Plan/Agent mode to create execution plan"
- Full-width layout (nie sidebar)

---

## 2. Różnice: Plan vs Rzeczywistość v1.0

| Aspekt | Oryginalny plan | Rzeczywistość v1.0 |
|--------|-----------------|-------------------|
| **Layout** | Split pane 70/30 (content + sidebar) | Full-width single pane, Steps jako tab |
| **Toolbar** | Górny toolbar z segmented control | Bottom Panel z dropdown |
| **Session Bar** | Osobny pasek z session info | Brak (w status bar) |
| **Status** | 2 linie | 3 linie |
| **Mode** | Segmented [Chat\|Plan\|Agent] | Dropdown selector |
| **Steps** | Right sidebar 30%, collapsible | Full-width zakładka |
| **Tabs** | [Chat\|Logs\|Index\|Settings] | [Chat\|Steps\|Logs\|Debug State\|Debug Theme] |
| **Multi-session** | Session tabs | Nie zaimplementowane |
| **History** | Slide-in panel | Nie zaimplementowane |
| **Errors** | Error Modal | Prosty tekst w chat |

### Uzasadnienie uproszczeń:

**1. Steps jako tab (nie sidebar 30%):**
- ✅ Więcej miejsca na szczegóły kroków
- ✅ Łatwiejsze layout management
- ⚠️ Brak jednoczesnego widoku Chat + Steps

**2. Brak Session Context Bar:**
- ✅ Mniej elementów UI, więcej miejsca
- ⚠️ Brak szybkiego dostępu do metadata

**3. Mode jako dropdown:**
- ✅ Standardowy komponent IntelliJ
- ✅ Łatwiejsze rozszerzenie
- ⚠️ Mniej prominent

---

## 3. Implementacja kluczowych komponentów

### 3.1 Bottom Control Panel

```kotlin
class BottomControlPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val modeSelector = ComboBox<String>().apply {
        addItem("Chat"); addItem("Plan"); addItem("Agent")
    }
    private val modelDropdown = ComboBox<String>()
    private val thinkingToggle = JButton(AllIcons.Actions.LightningBolt)
    private val noEgressToggle = JButton(AllIcons.Actions.Shield)
    private val sendButton = JButton("Send")

    init {
        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0))
        leftPanel.add(modeSelector)
        leftPanel.add(modelDropdown)
        leftPanel.add(thinkingToggle)
        leftPanel.add(noEgressToggle)

        add(leftPanel, BorderLayout.WEST)
        add(sendButton, BorderLayout.EAST)
    }
}
```

### 3.2 Status Bar

```kotlin
class IntegratedStatusBar : JPanel(GridLayout(3, 1)) {
    private val coreStatus = JLabel("🟢 Core: Ready")
    private val cpuRam = JLabel("CPU: 0% | RAM: 0MB")
    private val sessionTokens = JLabel("Session: 0.0K tokens")
    private val contextBar = JProgressBar(0, 100)
    private val cost = JLabel("Cost: $0.00")
    private val execution = JLabel("Execution: Step 0/0")

    init {
        add(createRow(coreStatus, cpuRam))
        add(createRow(sessionTokens, contextBar, cost))
        add(createRow(execution, stopButton))
    }
}
```

### 3.3 Chat View

```kotlin
class ChatView(private val project: Project) : JBPanel<ChatView>(BorderLayout()) {
    private val messageListPanel: JPanel
    private val promptInput: JBTextArea
    private val sendButton: JButton

    init {
        messageListPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        val scrollPane = JBScrollPane(messageListPanel)
        add(scrollPane, BorderLayout.CENTER)
        add(createPromptPanel(), BorderLayout.SOUTH)
    }

    private fun createUserMessagePanel(message: Message): JPanel {
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 40, 4, 8) // Right aligned
            val bgColor = JBColor(0x4A90E2, 0x365880) // Blue
            add(JEditorPane().apply {
                contentType = "text/html"
                text = markdownToHtml(message.content)
                background = bgColor
            }, BorderLayout.CENTER)
        }
    }
}
```

---

## 4. Roadmap implementacji

### ✅ Zaimplementowane (v1.0)
- Tool Window z header i zakładkami
- Bottom Control Panel (mode, model, akcje)
- Status Bar (3-liniowy)
- Chat View (basic: user messages, errors)
- Steps View (empty state)
- Prompt Input Area

### ⏳ High Priority (do zaimplementowania)
- **Code blocks** w odpowiedziach asystenta
- **Tool calls display** (collapsible components)
- **Diff summary badges** (+N -M, X files)
- **Interactive approval buttons** w Steps
- **Assistant messages** z markdown rendering
- **Thinking section** (collapsible)
- **Error handling** - lepsze formatowanie

### ⏳ Medium Priority
- **Logs View** - zawartość zakładki
- **Settings Dialog** - pełna funkcjonalność
- **Context Preview Panel** - @file, @folder pills
- **History Panel** - przeglądanie sesji
- **Snapshot indicators** - rollback capability
- **Session Stats Dialog** - kliknięcie w tokens

### ❌ Low Priority (Post-MVP)
- Multi-session tabs
- Session Context Bar (może w przyszłości)
- Index View (RAG management)
- Advanced Error Modals

---

## 5. Nawigacja

### Aktualnie zaimplementowane:
- **Content Tabs**: Click zakładki → zmiana widoku (Chat/Steps/Logs/Debug)
- **Mode Selector**: Dropdown → wybór trybu (Chat/Plan/Agent)
- **Model Selector**: Dropdown → wybór providera i modelu
- **Action Icons**: Click ⚙️ Settings, ❓ Help, 🎨 Theme, ☀️ Light/Dark
- **New Session**: Click button → nowa sesja
- **Send Prompt**: Click [Send] lub Ctrl+Enter

### Keyboard shortcuts (do zdefiniowania):
- `Ctrl+Enter` → Send prompt
- `Ctrl+Tab` → Next tab
- `Ctrl+Shift+Tab` → Previous tab
- `Esc` → Close modals/panels
- `Alt+1/2/3` → Switch mode (TBD)

---

## 6. Kluczowe zasady projektowe

1. **Natywność**: IntelliJ Swing components (JBPanel, JBScrollPane)
2. **Single-pane**: Jedna główna powierzchnia, tabs przełączają widoki
3. **Bottom-centric**: Kontrolki na dole (mode, model, send)
4. **Status-aware**: 3-liniowy status bar z wszystkimi metrykami
5. **Simplicity**: Mniej custom UI, więcej standardowych komponentów

### Bezpieczeństwo (planowane):
- Interactive approval (default w Agent mode)
- Snapshot przed zapisem
- No-egress mode (🛡️ toggle)
- Path sandbox (walidacja API)
- Read-only mode (global toggle)
