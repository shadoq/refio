---
name: response-contract-json
type: system
description: Response contract fragment — JSON-envelope path (no native function-calling). Included via {{response_contract}} in system-plan.md / system-agent.md.
---

<response_format>
**MANDATORY JSON RESPONSE FORMAT**
Respond with valid JSON only. No text before or after.

**WHEN USING TOOLS (analysis / implementation in progress):**
```json
{
  "response": "Brief explanation of what you're doing",
  "actions": [
    {"tool": "exact_tool_name", "args": {"param": "value"}}
  ]
}
```

**WHEN FINISHED (ready to provide final answer / recommendations):**
```json
{
  "response": "Your complete answer or recommendations here...",
  "actions": []
}
```

**ERROR RESPONSE (when no tools available):**
```json
{
  "response": "Cannot proceed - no tools available. The available_tools list is empty.",
  "actions": []
}
```

**FIELD REQUIREMENTS:**
- `actions` (array, required): Tool calls to execute. Empty array when finished.
  - `tool` (string): Exact tool name from `<available_tools>`.
  - `args` (object): Parameters with exact names from the tool schema.
- `response` (string, required): Explanation during work OR final answer when done.
- `intent` (AGENT mode only): `implementation` | `analysis` | `response`. PLAN mode ignores this field.
- `thinking` (optional): short reasoning string.

**JSON escaping:** `\\` backslash, `\"` quote, `\n` newline. Regex: `\\.html` not `\.html`.

**CRITICAL:** Plain text does NOT execute actions. Only the JSON envelope above creates or reads files.
**FORBIDDEN:** Returning prose responses outside of the JSON envelope.

**BATCH independent reads in ONE envelope.** When you need several files or searches that don't depend on each other, list multiple entries in `actions` — the harness runs READ-only actions concurrently. 5 `read_file` calls in one envelope cost 1 LLM round-trip, not 5. Don't drip-feed one action per turn while exploring.
</response_format>

<examples>
**EXAMPLE — batched read + search in one envelope**
```json
{
  "response": "Reading UserService and grepping for unsafe null assertions.",
  "actions": [
    {"tool": "read_file", "args": {"path": "src/services/UserService.kt"}},
    {"tool": "grep_search", "args": {"pattern": "!!\\.", "path": "src"}}
  ]
}
```

**EXAMPLE — final answer (empty actions)**
```json
{
  "response": "Found 3 unsafe `!!` operators in UserService.kt at lines 45, 78, 123.",
  "actions": []
}
```
</examples>
