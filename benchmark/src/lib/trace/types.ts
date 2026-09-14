// The shape every harness's event stream is normalized into. One event per line in
// trace.jsonl, so two runs can be read side by side no matter which agent produced
// them. Pure types, shared by the normalizers and the viewer.
import type { TraceSummary } from "../../schema/results";

export type ToolClass = "read" | "write" | "shell" | "search" | "other";

export type TraceKind = "assistant_text" | "tool_call" | "tool_result" | "error" | "run_end";

export type TraceSource = TraceSummary["source"];

export interface TraceEvent {
  i: number;
  // Milliseconds since the run started; null when the source carries no clock.
  tMs: number | null;
  // Which assistant turn the event belongs to; 0 before the agent said anything.
  turn: number;
  kind: TraceKind;
  tool: string | null;
  cls: ToolClass | null;
  args: string | null;
  ok: boolean | null;
  // Exit code of a shell command, when the harness reports one. Kept apart from `ok`:
  // a grep that matches nothing exits non-zero without the tool call having failed,
  // and folding the two together made "tool errors" mean something different in every
  // harness.
  exit?: number | null;
  text: string | null;
}

// One line of an agent's stdout with the moment it arrived, so a streamed run keeps
// a wall clock the agent itself never reports.
export interface TimedLine {
  line: string;
  tMs: number;
}

export interface TracePaths {
  path: string;
  rawPath?: string;
  runJsonPath?: string;
}

export const MAX_TEXT = 300;

export function shorten(text: string, max = MAX_TEXT): string {
  const flat = text.replace(/\s+/g, " ").trim();
  return flat.length <= max ? flat : flat.slice(0, max - 1) + "…";
}
