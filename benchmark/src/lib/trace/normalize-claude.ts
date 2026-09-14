// Claude Code's `--output-format stream-json` prints one event per line: assistant
// messages carrying text and tool_use blocks, user messages carrying tool results,
// and a final result event. A line that is not an event (a warning, a truncated pipe)
// is skipped rather than failing the run's trace.
import { classifyTool, summarizeArgs } from "./tool-classes";
import type { TimedLine, TraceEvent } from "./types";
import { shorten } from "./types";

const HARNESS = "claude-code";

export function normalizeClaudeStreamJson(lines: TimedLine[]): TraceEvent[] {
  const events: TraceEvent[] = [];
  // A tool result names only the id of the call it answers, so the tool name is
  // carried over from the call itself.
  const toolById = new Map<string, string>();
  let turn = 0;
  let i = 0;
  let lastTMs: number | null = null;
  let sawResult = false;

  for (const { line, tMs } of lines) {
    const trimmed = line.trim();
    if (trimmed === "") continue;
    let event: Record<string, unknown>;
    try {
      event = JSON.parse(trimmed) as Record<string, unknown>;
    } catch {
      continue;
    }
    lastTMs = tMs;
    const type = String(event.type ?? "");
    const message = (event.message ?? {}) as Record<string, unknown>;
    const content = Array.isArray(message.content) ? message.content : [];

    if (type === "assistant") {
      turn += 1;
      const texts = content
        .filter((b): b is Record<string, unknown> => typeof b === "object" && b !== null)
        .filter((b) => b.type === "text" && typeof b.text === "string")
        .map((b) => String(b.text));
      if (texts.length > 0) {
        events.push({
          i: i++, tMs, turn, kind: "assistant_text", tool: null, cls: null, args: null, ok: null,
          text: shorten(texts.join(" ")),
        });
      }
      for (const raw of content) {
        const block = (raw ?? {}) as Record<string, unknown>;
        if (block.type !== "tool_use") continue;
        const tool = String(block.name ?? "unknown");
        if (typeof block.id === "string") toolById.set(block.id, tool);
        events.push({
          i: i++, tMs, turn, kind: "tool_call", tool, cls: classifyTool(HARNESS, tool),
          args: summarizeArgs(block.input), ok: null, text: null,
        });
      }
    } else if (type === "user") {
      for (const raw of content) {
        const block = (raw ?? {}) as Record<string, unknown>;
        if (block.type !== "tool_result") continue;
        const tool = typeof block.tool_use_id === "string" ? toolById.get(block.tool_use_id) ?? null : null;
        events.push({
          i: i++, tMs, turn, kind: "tool_result", tool,
          cls: tool ? classifyTool(HARNESS, tool) : null, args: null,
          ok: block.is_error !== true,
          text: typeof block.content === "string" ? shorten(block.content) : null,
        });
      }
    } else if (type === "result") {
      sawResult = true;
      events.push({
        i: i++, tMs, turn, kind: "run_end", tool: null, cls: null, args: null, ok: null,
        text: event.is_error === true ? "FAILED" : String(event.subtype ?? "SUCCESS"),
      });
    }
  }

  // A run killed by the timeout never prints its result event; the trace still has to
  // end somewhere so the viewer and the metrics see a complete document.
  if (!sawResult) {
    events.push({
      i, tMs: lastTMs, turn, kind: "run_end", tool: null, cls: null, args: null, ok: null,
      text: "INCOMPLETE",
    });
  }
  return events;
}
