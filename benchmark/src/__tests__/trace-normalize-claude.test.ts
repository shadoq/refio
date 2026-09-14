// @vitest-environment node
import { describe, it, expect } from "vitest";
import { normalizeClaudeStreamJson } from "@/lib/trace/normalize-claude";
import type { TimedLine } from "@/lib/trace/types";

const lines: TimedLine[] = [
  { line: JSON.stringify({ type: "system", subtype: "init", model: "opus" }), tMs: 0 },
  {
    line: JSON.stringify({
      type: "assistant",
      message: {
        content: [
          { type: "text", text: "Reading the fixture." },
          { type: "tool_use", id: "t1", name: "Read", input: { file_path: "index.html" } },
        ],
      },
    }),
    tMs: 1200,
  },
  { line: "not json", tMs: 1300 },
  {
    line: JSON.stringify({
      type: "user",
      message: {
        content: [{ type: "tool_result", tool_use_id: "t1", is_error: false, content: "ok" }],
      },
    }),
    tMs: 1400,
  },
  {
    line: JSON.stringify({
      type: "assistant",
      message: {
        content: [
          { type: "tool_use", id: "t2", name: "Write", input: { file_path: "snake.html", content: "<html>" } },
        ],
      },
    }),
    tMs: 2000,
  },
  { line: JSON.stringify({ type: "result", subtype: "success", is_error: false }), tMs: 2500 },
];

describe("normalizeClaudeStreamJson", () => {
  it("reads turns, tool calls and their results out of the event stream", () => {
    const events = normalizeClaudeStreamJson(lines);
    expect(Math.max(...events.map((e) => e.turn))).toBe(2);
    const calls = events.filter((e) => e.kind === "tool_call");
    expect(calls.map((c) => c.tool)).toEqual(["Read", "Write"]);
    expect(calls.map((c) => c.cls)).toEqual(["read", "write"]);
    expect(calls[1].args).not.toContain("<html>");
    expect(events[events.length - 1].kind).toBe("run_end");
  });

  it("names the tool a result belongs to", () => {
    const result = normalizeClaudeStreamJson(lines).find((e) => e.kind === "tool_result");
    expect(result).toMatchObject({ tool: "Read", ok: true });
  });

  // A progress line or a crash dump in the middle of the stream must not cost the
  // whole run its trace.
  it("skips a line that is not an event", () => {
    const events = normalizeClaudeStreamJson(lines);
    expect(events.every((e) => e.tMs !== 1300)).toBe(true);
  });
});
