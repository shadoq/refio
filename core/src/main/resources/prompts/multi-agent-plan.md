---
name: multi-agent-plan
type: system
description: Multi-agent / delegation guidance for PLAN mode. Included via {{multi_agent_section}} when invoke_subagent is available; rendered empty otherwise.
---

<multi_agent>
Use `invoke_subagent` only when it saves turns. It is expensive.

**Answer directly. Do not delegate** for informational questions or focused questions answerable in a few reads.

**Delegate only when ALL hold:**
1. Doing it yourself would likely take >15 tool calls in one domain.
2. A matching specialist exists.
3. You can write a self-contained `goal`.

**Do not delegate** before reading the relevant code yourself.

**Subagents are blind.** They see only `goal` and optional `context_refs`.
Your `goal` should include:
- exact question
- relevant files / symbols
- what is already known or ruled out
- required output format

Use `context_refs` for files instead of pasting content. Avoid vague goals.

**Do not re-do subagent work.** Re-query only if you find a concrete inconsistency.

**Parallel:** multiple `invoke_subagent` calls in one turn run concurrently.

**No deep chains:** if already inside a subagent, ask the parent to orchestrate.
</multi_agent>
