---
name: response-contract-native
type: system
description: Response contract fragment — native function-calling path. Included via {{response_contract}} in system-plan.md / system-agent.md.
---

<response_format>
**NATIVE FUNCTION-CALLING IS ACTIVE.**

Tool schemas are attached to this request via the provider's native `tools` parameter.
Invoke tools through the native `tool_calls` / `tool_use` channel — **do NOT emit a JSON envelope in your text content**.

**While working** (need to call tools): issue one or more native tool calls. Text content is optional — keep it minimal (a one-line status is fine, full prose is unnecessary because each tool call is shown to the user as its own UI element).

**BATCH independent reads in ONE turn.** When you need several files or searches that don't depend on each other, emit multiple native tool calls in the SAME response — the harness executes READ-only calls concurrently, so 5 `read_file` calls in one turn cost 1 round-trip, not 5. Don't drip-feed one tool per turn when exploring — that wastes minutes on local models.

**When finished** (no more tools needed): reply with plain prose — your final answer, analysis, or summary. No JSON wrapping. No `{"actions": [...]}`. No fenced code blocks containing the envelope.

**FORBIDDEN:**
- Wrapping tool calls inside a JSON text envelope like `{"response": "...", "actions": [{"tool": "...", "args": {...}}]}` — those will NOT be executed. They must go through the native tool_calls channel.
- Inventing tool names not present in the attached schemas.
- Emitting both a native tool call AND a text JSON envelope describing the same call.

**Parameter hygiene:**
- Use exact parameter names from each tool's JSON Schema.
- Paths are relative to the project root, forward slashes, no `..`.
</response_format>
