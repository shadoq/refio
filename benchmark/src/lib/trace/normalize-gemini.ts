// Gemini CLI's `-o stream-json` prints one event per line, each with a type and an ISO
// timestamp: init, message, tool_use, tool_result, error, result. The event names and
// fields are the ones the installed CLI declares, so a release that renames them shows
// up as an unclassified call rather than an empty trace.
import { classifyTool, summarizeArgs } from "./tool-classes";
import type { TimedLine, TraceEvent } from "./types";
import { shorten } from "./types";

const HARNESS = "gemini-cli";

export function normalizeGeminiStreamJson(lines: TimedLine[]): TraceEvent[] {
  const events: TraceEvent[] = [];
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

    if (type === "message" && event.role === "assistant") {
      turn += 1;
      events.push({
        i: i++, tMs, turn, kind: "assistant_text", tool: null, cls: null, args: null, ok: null,
        text: shorten(String(event.content ?? "")),
      });
    } else if (type === "tool_use") {
      const tool = String(event.tool_name ?? "unknown");
      if (typeof event.tool_id === "string") toolById.set(event.tool_id, tool);
      events.push({
        i: i++, tMs, turn, kind: "tool_call", tool, cls: classifyTool(HARNESS, tool),
        args: summarizeArgs(event.parameters), ok: null, text: null,
      });
    } else if (type === "tool_result") {
      const tool = typeof event.tool_id === "string" ? toolById.get(event.tool_id) ?? null : null;
      events.push({
        i: i++, tMs, turn, kind: "tool_result", tool,
        cls: tool ? classifyTool(HARNESS, tool) : null, args: null,
        ok: event.status !== "error",
        text: typeof event.output === "string" ? shorten(event.output) : null,
      });
    } else if (type === "error") {
      events.push({
        i: i++, tMs, turn, kind: "error", tool: null, cls: null, args: null, ok: null,
        text: shorten(String(event.message ?? "")),
      });
    } else if (type === "result") {
      sawResult = true;
      events.push({
        i: i++, tMs, turn, kind: "run_end", tool: null, cls: null, args: null, ok: null,
        text: String(event.status ?? "unknown"),
      });
    }
  }

  if (!sawResult) {
    events.push({
      i, tMs: lastTMs, turn, kind: "run_end", tool: null, cls: null, args: null, ok: null,
      text: "INCOMPLETE",
    });
  }
  return events;
}
