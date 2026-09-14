// `codex exec --json` prints one event per line. Codex reports outcomes rather than
// tool names: a shell command it ran, a set of files it changed, a message it wrote.
// Its item names are not a stable contract, so an unfamiliar one is kept as an
// unclassified call - a comparison that silently drops what an agent did is worse
// than one that shows an unnamed step.
import { classifyShellCommand, classifyTool, summarizeArgs } from "./tool-classes";
import type { TimedLine, TraceEvent } from "./types";
import { shorten } from "./types";

const HARNESS = "codex";

// One item can carry several changed files. They are emitted as one call each, so a
// write means the same thing here as in a harness that edits one file per call.
function changedPaths(changes: unknown): string[] {
  if (!Array.isArray(changes)) return ["?"];
  const paths = changes.map((raw) => String(((raw ?? {}) as Record<string, unknown>).path ?? "?"));
  return paths.length > 0 ? paths : ["?"];
}

export function normalizeCodexJsonl(lines: TimedLine[]): TraceEvent[] {
  const events: TraceEvent[] = [];
  let turn = 0;
  let i = 0;
  let lastTMs: number | null = null;
  let sawEnd = false;

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

    if (type === "error") {
      events.push({
        i: i++, tMs, turn, kind: "error", tool: null, cls: null, args: null, ok: null,
        text: shorten(JSON.stringify(event.message ?? event)),
      });
      continue;
    }
    if (type === "turn.completed" || type === "turn.failed") {
      sawEnd = true;
      events.push({
        i: i++, tMs, turn, kind: "run_end", tool: null, cls: null, args: null, ok: null,
        text: type === "turn.completed" ? "SUCCESS" : "FAILED",
      });
      continue;
    }
    if (type !== "item.completed") continue;

    const item = (event.item ?? {}) as Record<string, unknown>;
    const itemType = String(item.type ?? "unknown");

    if (itemType === "reasoning") continue;

    if (itemType === "agent_message") {
      turn += 1;
      events.push({
        i: i++, tMs, turn, kind: "assistant_text", tool: null, cls: null, args: null, ok: null,
        text: shorten(String(item.text ?? "")),
      });
    } else if (itemType === "command_execution") {
      const command = String(item.command ?? "");
      const cls = classifyShellCommand(command);
      events.push({
        i: i++, tMs, turn, kind: "tool_call", tool: itemType, cls,
        args: summarizeArgs({ command }), ok: null, text: null,
      });
      const exit = typeof item.exit_code === "number" ? item.exit_code : null;
      events.push({
        i: i++, tMs, turn, kind: "tool_result", tool: itemType, cls,
        // The command ran; whether it liked what it found is the exit code's business.
        args: null, ok: true, exit, text: null,
      });
    } else if (itemType === "file_change") {
      for (const path of changedPaths(item.changes)) {
        events.push({
          i: i++, tMs, turn, kind: "tool_call", tool: itemType, cls: classifyTool(HARNESS, itemType),
          args: summarizeArgs({ path }), ok: null, text: null,
        });
      }
    } else {
      events.push({
        i: i++, tMs, turn, kind: "tool_call", tool: itemType, cls: "other",
        args: summarizeArgs(item), ok: null, text: null,
      });
    }
  }

  if (!sawEnd) {
    events.push({
      i, tMs: lastTMs, turn, kind: "run_end", tool: null, cls: null, args: null, ok: null,
      text: "INCOMPLETE",
    });
  }
  return events;
}
