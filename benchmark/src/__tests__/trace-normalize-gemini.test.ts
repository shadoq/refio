// @vitest-environment node
import { describe, it, expect } from "vitest";
// The sample is one recorded shape of the Gemini event stream; keeping it in a file
// makes it cheap to replace with a fresh capture when the CLI changes.
import sample from "./fixtures/gemini-stream-sample.jsonl?raw";
import { normalizeGeminiStreamJson } from "@/lib/trace/normalize-gemini";
import type { TimedLine } from "@/lib/trace/types";

const lines: TimedLine[] = sample
  .split("\n")
  .filter((line: string) => line.trim() !== "")
  .map((line: string, index: number) => ({ line, tMs: index * 1000 }));

describe("normalizeGeminiStreamJson", () => {
  it("reads the write and the read back out of the event stream", () => {
    const events = normalizeGeminiStreamJson(lines);
    const calls = events.filter((e) => e.kind === "tool_call");
    expect(calls.map((c) => c.tool)).toEqual(["write_file", "read_file"]);
    expect(calls.map((c) => c.cls)).toEqual(["write", "read"]);
    expect(calls[0].args).toContain("file_path=hello.txt");
    expect(calls[0].args).not.toContain("hello\"");
  });

  it("counts an assistant message as a turn and ends on the result event", () => {
    const events = normalizeGeminiStreamJson(lines);
    expect(events.filter((e) => e.kind === "assistant_text")).toHaveLength(2);
    expect(events[events.length - 1]).toMatchObject({ kind: "run_end", text: "success" });
  });

  it("names the tool each result belongs to and whether it worked", () => {
    const results = normalizeGeminiStreamJson(lines).filter((e) => e.kind === "tool_result");
    expect(results.map((r) => r.tool)).toEqual(["write_file", "read_file"]);
    expect(results.every((r) => r.ok === true)).toBe(true);
  });

  it("skips a line that is not an event", () => {
    const events = normalizeGeminiStreamJson([{ line: "boom", tMs: 0 }, ...lines]);
    expect(events.filter((e) => e.kind === "tool_call")).toHaveLength(2);
  });
});
