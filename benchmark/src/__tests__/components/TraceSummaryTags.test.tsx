import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { TraceSummaryTags } from "@/components/results/TraceSummaryTags";
import type { TraceSummary } from "@/schema/results";

const trace: TraceSummary = {
  format: "refio-trace/1",
  source: "claude-stream-json",
  path: "attachments/x/_trace/trace.jsonl",
  turns: 5,
  toolCalls: 12,
  reads: 6,
  writes: 3,
  shellRuns: 2,
  searches: 1,
  otherCalls: 0,
  toolErrors: 0,
  firstWriteAtCall: 4,
  editsAfterFirstWrite: 2,
  timeToFirstWriteMs: 30000,
  selfVerified: true,
  endReason: "completed" as const,
  nonZeroExits: 0,
  duplicateCalls: 0,
  repeatedCallStreak: 0,
  repeatedFailedCallStreak: 0,
  recoveredFromError: null,
  readsBeforeFirstWrite: 0,
  searchesBeforeFirstWrite: 0,
  filesWritten: 1,
  toolHistogram: {},
};

describe("TraceSummaryTags", () => {
  it("says that the model ran a build or test itself", () => {
    render(<TraceSummaryTags trace={trace} />);
    expect(screen.getByText("self-check yes")).toBeTruthy();
  });

  it("says when it did not", () => {
    render(<TraceSummaryTags trace={{ ...trace, selfVerified: false }} />);
    expect(screen.getByText("self-check no")).toBeTruthy();
  });

  it("shows the shape of the run", () => {
    render(<TraceSummaryTags trace={trace} />);
    expect(screen.getByText("5 turns")).toBeTruthy();
    expect(screen.getByText("6R / 3W / 2sh")).toBeTruthy();
  });
});
