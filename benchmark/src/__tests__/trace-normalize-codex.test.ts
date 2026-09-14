// @vitest-environment node
import { describe, it, expect } from "vitest";
import { normalizeCodexJsonl } from "@/lib/trace/normalize-codex";
import type { TimedLine } from "@/lib/trace/types";

const lines: TimedLine[] = [
  { line: JSON.stringify({ type: "thread.started", thread_id: "x" }), tMs: 0 },
  {
    line: JSON.stringify({
      type: "item.completed",
      item: { type: "command_execution", command: "ls", exit_code: 0 },
    }),
    tMs: 500,
  },
  {
    line: JSON.stringify({
      type: "item.completed",
      item: { type: "file_change", changes: [{ path: "src/server.js", kind: "add" }] },
    }),
    tMs: 900,
  },
  {
    line: JSON.stringify({ type: "item.completed", item: { type: "agent_message", text: "Done." } }),
    tMs: 1100,
  },
  {
    line: JSON.stringify({ type: "item.completed", item: { type: "todo_list", items: [] } }),
    tMs: 1200,
  },
  { line: JSON.stringify({ type: "turn.completed", usage: { input_tokens: 10 } }), tMs: 1500 },
];

describe("normalizeCodexJsonl", () => {
  it("reads a shell command as a call plus its outcome", () => {
    const events = normalizeCodexJsonl(lines);
    const shell = events.find((e) => e.kind === "tool_call" && e.cls === "shell");
    expect(shell?.args).toBe("command=ls");
    expect(events.find((e) => e.kind === "tool_result")?.ok).toBe(true);
  });

  it("reads a file change as a write naming the paths it touched", () => {
    const write = normalizeCodexJsonl(lines).find((e) => e.cls === "write");
    expect(write?.args).toBe("path=src/server.js");
  });

  it("counts an agent message as a turn", () => {
    const events = normalizeCodexJsonl(lines);
    expect(events.some((e) => e.kind === "assistant_text" && e.text === "Done.")).toBe(true);
    expect(Math.max(...events.map((e) => e.turn))).toBe(1);
  });

  // Codex item names are not a stable contract, so an unknown one is kept as an
  // unclassified call instead of disappearing from the comparison.
  it("keeps an item type it does not know", () => {
    const other = normalizeCodexJsonl(lines).find((e) => e.tool === "todo_list");
    expect(other).toMatchObject({ kind: "tool_call", cls: "other" });
  });

  it("ends the trace on the turn event", () => {
    expect(normalizeCodexJsonl(lines)[normalizeCodexJsonl(lines).length - 1].kind).toBe("run_end");
  });
});
