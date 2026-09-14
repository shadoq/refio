// @vitest-environment node
import { describe, it, expect } from "vitest";
import { normalizeRefioRunJson } from "@/lib/trace/normalize-refio";

const runJson = {
  session: { status: "SUCCESS" },
  conversation: [
    {
      role: "ASSISTANT",
      contentPreview: "I will read the fixture first.",
      createdAt: 1_000_000,
      toolCalls: ["read_file", "advance_code_editing"],
      toolCallDetails: [
        { name: "read_file", arguments: '{"file_path":"index.html"}' },
        { name: "advance_code_editing", arguments: '{"file_path":"snake.html","content":"<html>"}' },
      ],
    },
    { role: "TOOL", contentPreview: "file written", createdAt: 1_002_000 },
    { role: "ASSISTANT", contentPreview: "Done.", createdAt: 1_003_500, toolCalls: [] },
  ],
};

describe("normalizeRefioRunJson", () => {
  it("turns the headless conversation into turns, calls and results", () => {
    const events = normalizeRefioRunJson(runJson);
    expect(events.filter((e) => e.kind === "assistant_text")).toHaveLength(2);
    const calls = events.filter((e) => e.kind === "tool_call");
    expect(calls.map((c) => c.cls)).toEqual(["read", "write"]);
    expect(calls[1].args).toContain("file_path=snake.html");
    expect(calls[1].args).not.toContain("<html>");
    expect(events.filter((e) => e.kind === "tool_result")).toHaveLength(1);
    expect(events[events.length - 1]).toMatchObject({ kind: "run_end", text: "SUCCESS" });
  });

  it("counts time from the first message of the run", () => {
    const events = normalizeRefioRunJson(runJson);
    expect(events[0].tMs).toBe(0);
    expect(events.find((e) => e.kind === "tool_result")?.tMs).toBe(2000);
  });

  it("marks a failing tool result", () => {
    const events = normalizeRefioRunJson({
      session: { status: "FAILED" },
      conversation: [{ role: "TOOL", contentPreview: "Error: no such file", createdAt: 5 }],
    });
    expect(events.find((e) => e.kind === "tool_result")?.ok).toBe(false);
  });

  it("degrades to a bare run end when the document has no conversation", () => {
    const events = normalizeRefioRunJson({});
    expect(events).toHaveLength(1);
    expect(events[0].kind).toBe("run_end");
  });
});
