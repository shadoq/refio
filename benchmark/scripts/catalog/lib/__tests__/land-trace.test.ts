// @vitest-environment node
import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { mkdtemp, rm, readFile, writeFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { buildTraceForRun, landTrace } from "../land-trace";
import type { TraceEvent } from "../../../../src/lib/trace/types";

const events: TraceEvent[] = [
  { i: 0, tMs: 0, turn: 1, kind: "assistant_text", tool: null, cls: null, args: null, ok: null, text: "hi" },
  { i: 1, tMs: 10, turn: 1, kind: "tool_call", tool: "Write", cls: "write", args: "file_path=a.html", ok: null, text: null },
  { i: 2, tMs: 20, turn: 1, kind: "run_end", tool: null, cls: null, args: null, ok: null, text: "SUCCESS" },
];

let dataDir = "";

beforeEach(async () => {
  dataDir = await mkdtemp(join(tmpdir(), "refio-trace-test-"));
});
afterEach(async () => {
  await rm(dataDir, { recursive: true, force: true });
});

describe("landTrace", () => {
  it("writes the event log and the raw agent output next to the artifact", async () => {
    const summary = await landTrace({
      entryId: "task__claude-code__m__1",
      dataDir,
      source: "claude-stream-json",
      events,
      rawLog: "raw agent output",
      persist: true,
    });

    expect(summary.path).toBe("attachments/task__claude-code__m__1/_trace/trace.jsonl");
    expect(summary.rawPath).toBe("attachments/task__claude-code__m__1/_trace/raw.log");
    expect(summary.writes).toBe(1);
    const jsonl = await readFile(join(dataDir, summary.path), "utf8");
    expect(jsonl.split("\n")).toHaveLength(3);
    expect(await readFile(join(dataDir, summary.rawPath!), "utf8")).toBe("raw agent output");
  });

  it("leaves no raw log when the harness has none", async () => {
    const summary = await landTrace({
      entryId: "e2",
      dataDir,
      source: "refio-run-json",
      events,
      persist: true,
    });
    expect(summary.rawPath).toBeUndefined();
    expect(existsSync(join(dataDir, "attachments", "e2", "_trace", "raw.log"))).toBe(false);
  });

  it("keeps the run document a refio run was read from", async () => {
    const src = join(dataDir, "run.json");
    await writeFile(src, '{"session":{"status":"SUCCESS"}}');
    const summary = await landTrace({
      entryId: "e3",
      dataDir,
      source: "refio-run-json",
      events,
      runJsonSrc: src,
      persist: true,
    });
    expect(summary.runJsonPath).toBe("attachments/e3/_trace/run.json");
    expect(existsSync(join(dataDir, summary.runJsonPath!))).toBe(true);
  });

  // A raw log big enough to bloat the repository keeps its head and its tail: the
  // metrics were already computed from the whole stream in memory.
  it("truncates a raw log that is too large to keep whole", async () => {
    const summary = await landTrace({
      entryId: "e4",
      dataDir,
      source: "claude-stream-json",
      events,
      rawLog: "x".repeat(9 * 1024 * 1024),
      persist: true,
    });
    const written = await readFile(join(dataDir, summary.rawPath!), "utf8");
    expect(written).toContain("[truncated");
    expect(written.length).toBeLessThan(9 * 1024 * 1024);
  });

  it("writes nothing on a dry run but still reports where it would have written", async () => {
    const summary = await landTrace({
      entryId: "e5",
      dataDir,
      source: "codex-jsonl",
      events,
      rawLog: "x",
      persist: false,
    });
    expect(summary.path).toBe("attachments/e5/_trace/trace.jsonl");
    expect(existsSync(join(dataDir, "attachments", "e5"))).toBe(false);
  });

  // Refio's loop runs the build or tests itself after a writing turn; no external agent does.
  // Recorded separately from selfVerified so the comparison of "did the MODEL check its own work"
  // stays like-for-like while the harness's own verification is still visible.
  describe("loop verification", () => {
    const runJson = (ran: boolean) => ({
      session: { status: "SUCCESS" },
      metrics: { verification: { ran, attempts: ran ? 1 : 0, result: ran ? "PASSED" : null } },
      conversation: [
        {
          role: "ASSISTANT",
          contentPreview: "writing",
          createdAt: 0,
          toolCallDetails: [{ name: "advance_code_editing", arguments: '{"path":"a.js"}', ok: true }],
        },
        { role: "TOOL", contentPreview: "written", createdAt: 1 },
      ],
    });

    it("marks a run whose loop verified the work", async () => {
      const summary = await buildTraceForRun({
        harnessId: "refio", entryId: "v1", dataDir, persist: false, runJson: runJson(true),
      });

      expect(summary?.toolHistogram["__loop_verification__"]).toBe(1);
      expect(summary?.selfVerified).toBe(false);
    });

    it("leaves the marker off when the loop never verified", async () => {
      const summary = await buildTraceForRun({
        harnessId: "refio", entryId: "v2", dataDir, persist: false, runJson: runJson(false),
      });

      expect(summary?.toolHistogram).not.toHaveProperty("__loop_verification__");
    });
  });
});
