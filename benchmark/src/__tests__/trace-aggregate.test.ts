// @vitest-environment node
import { describe, it, expect } from "vitest";
import { aggregateTraces } from "@/lib/trace/aggregate";
import type { Result, TraceSummary } from "@/schema/results";

function trace(over: Partial<TraceSummary>): TraceSummary {
  return {
    format: "refio-trace/1",
    source: "claude-stream-json",
    path: "p",
    turns: 4,
    toolCalls: 10,
    reads: 4,
    writes: 2,
    shellRuns: 1,
    searches: 3,
    otherCalls: 0,
    toolErrors: 0,
    firstWriteAtCall: 3,
    editsAfterFirstWrite: 1,
    timeToFirstWriteMs: 1000,
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
    ...over,
  };
}

function result(id: string, t?: TraceSummary): Result {
  return {
    id,
    taskId: "snake",
    modelId: "m",
    environmentId: "e",
    harnessId: "refio",
    attemptNumber: 1,
    scores: [{ criterionId: "compliance", value: 1 }],
    attachments: [],
    judgeScores: [],
    runAt: "2026-04-15T09:00:00.000Z",
    createdAt: "2026-04-15T09:00:00.000Z",
    ...(t ? { trace: t } : {}),
  };
}

describe("aggregateTraces", () => {
  // Results recorded before traces existed must not drag the averages down: they are
  // unmeasured, not zero.
  it("averages only the runs that actually carry a trace", () => {
    const agg = aggregateTraces([
      result("a", trace({ turns: 2, toolCalls: 6, writes: 1, selfVerified: true })),
      result("b", trace({ turns: 4, toolCalls: 10, writes: 3, selfVerified: false })),
      result("c"),
    ]);
    expect(agg.withTrace).toBe(2);
    expect(agg.avgTurns).toBeCloseTo(3);
    expect(agg.avgToolCalls).toBeCloseTo(8);
    expect(agg.avgWrites).toBeCloseTo(2);
    expect(agg.selfVerifiedRate).toBeCloseTo(0.5);
  });

  it("reports nothing measured for an empty list", () => {
    const agg = aggregateTraces([]);
    expect(agg).toEqual({
      withTrace: 0,
      avgTurns: null,
      avgToolCalls: null,
      avgReads: null,
      avgWrites: null,
      avgShellRuns: null,
      selfVerifiedRate: null,
      wastedCallRate: null,
      recoveryRate: null,
      unfinishedRate: null,
    });
  });

  // The metrics that separate a loop that made progress from one that thrashed.
  it("reports how much of the work was wasted and how often a failure was survived", () => {
    const agg = aggregateTraces([
      { trace: { ...trace({}), toolCalls: 10, duplicateCalls: 2, recoveredFromError: true } },
      { trace: { ...trace({}), toolCalls: 10, duplicateCalls: 0, recoveredFromError: false } },
    ]);
    expect(agg.wastedCallRate).toBeCloseTo(0.1);
    expect(agg.recoveryRate).toBeCloseTo(0.5);
  });

  // A run in which nothing failed is not a run that recovered, and counting it as one
  // would make every clean sweep look like a resilient agent.
  it("leaves recovery unmeasured when no run ever hit a failing call", () => {
    expect(aggregateTraces([{ trace: trace({}) }]).recoveryRate).toBeNull();
  });

  it("counts the runs that ended other than by finishing", () => {
    const agg = aggregateTraces([
      { trace: { ...trace({}), endReason: "completed" } },
      { trace: { ...trace({}), endReason: "incomplete" } },
    ]);
    expect(agg.unfinishedRate).toBeCloseTo(0.5);
  });
});
