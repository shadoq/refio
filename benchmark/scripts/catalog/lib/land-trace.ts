// Writes one run's action log next to its artifact and returns the summary that goes
// on the queue entry. The normalizing and the arithmetic live in src/lib/trace (pure,
// unit-tested); this file is only the IO around them, shared by both importers so the
// two cannot drift apart.
import { mkdir, writeFile, copyFile } from "node:fs/promises";
import { existsSync } from "node:fs";
import { join } from "node:path";
import {
  normalizeRefioRunJson,
  normalizeClaudeStreamJson,
  normalizeCodexJsonl,
  normalizeGeminiStreamJson,
  summarize,
  toJsonl,
} from "../../../src/lib/trace";
import {
  claudeThinkingEvidence,
  refioThinkingEvidence,
  buildThinking,
} from "../../../src/lib/trace/thinking";
import type { TimedLine, TraceEvent, TraceSource } from "../../../src/lib/trace/types";
import type { Thinking, TraceSummary } from "../../../src/schema/results";

// The raw agent log is kept as evidence, but a run that printed hundreds of megabytes
// of file content must not be committed whole. Head and tail are what a reader needs.
const MAX_RAW_BYTES = 8 * 1024 * 1024;
const RAW_KEEP = MAX_RAW_BYTES / 2;

export function capRawLog(rawLog: string): string {
  if (Buffer.byteLength(rawLog) <= MAX_RAW_BYTES) return rawLog;
  const dropped = rawLog.length - RAW_KEEP * 2;
  return `${rawLog.slice(0, RAW_KEEP)}\n...[truncated ${dropped} bytes]...\n${rawLog.slice(-RAW_KEEP)}`;
}

export interface LandTraceInput {
  entryId: string;
  dataDir: string;
  source: TraceSource;
  events: TraceEvent[];
  rawLog?: string;
  // Path of the run document to keep beside the trace (the Refio headless run.json).
  runJsonSrc?: string;
  persist: boolean;
}

export async function landTrace(input: LandTraceInput): Promise<TraceSummary> {
  const relDir = `attachments/${input.entryId}/_trace`;
  const paths = {
    path: `${relDir}/trace.jsonl`,
    ...(input.rawLog !== undefined ? { rawPath: `${relDir}/raw.log` } : {}),
    ...(input.runJsonSrc ? { runJsonPath: `${relDir}/run.json` } : {}),
  };
  // The metrics are computed from the whole stream in memory, never from the capped
  // file, so a truncated log cannot quietly lower the numbers.
  const summary = summarize(input.events, input.source, paths);
  if (!input.persist) return summary;

  const absDir = join(input.dataDir, relDir);
  await mkdir(absDir, { recursive: true });
  await writeFile(join(absDir, "trace.jsonl"), toJsonl(input.events));
  if (input.rawLog !== undefined) {
    await writeFile(join(absDir, "raw.log"), capRawLog(input.rawLog));
  }
  if (input.runJsonSrc && existsSync(input.runJsonSrc)) {
    await copyFile(input.runJsonSrc, join(absDir, "run.json"));
  }
  return summary;
}

export interface TraceForRunInput {
  harnessId: string;
  entryId: string;
  dataDir: string;
  persist: boolean;
  // Refio: the parsed run document and the file it came from.
  runJson?: unknown;
  runJsonSrc?: string | null;
  // External agents: their own event stream.
  timedLines?: TimedLine[];
  rawLog?: string;
  source?: TraceSource;
  // What the run's own loop reported about itself, for a harness that reports it.
  loop?: TraceSummary["loop"];
}

// Normalize whichever stream this harness produced, then land it. Returns undefined
// when there is nothing to trace, so the caller simply leaves the field off the entry.
// Refio's loop runs the project's build or tests itself after a file-writing turn, which no
// external agent does: there, verification happens only if the model decides to ask for it. That
// difference has to stay visible without being folded into selfVerified, which answers the narrower
// and fairly comparable question "did the MODEL check its own work". So it is recorded as its own
// histogram entry instead.
const LOOP_VERIFICATION_KEY = "__loop_verification__";

function withLoopVerification(summary: TraceSummary, runJson: unknown): TraceSummary {
  const metrics = ((runJson ?? {}) as Record<string, unknown>).metrics as
    | Record<string, unknown>
    | undefined;
  const verification = metrics?.verification as Record<string, unknown> | undefined;
  if (verification?.ran !== true) return summary;
  return {
    ...summary,
    toolHistogram: { ...summary.toolHistogram, [LOOP_VERIFICATION_KEY]: 1 },
  };
}

export async function buildTraceForRun(
  input: TraceForRunInput,
): Promise<TraceSummary | undefined> {
  const withLoop = (summary: TraceSummary): TraceSummary =>
    input.loop && Object.keys(input.loop).length > 0 ? { ...summary, loop: input.loop } : summary;

  if (input.harnessId === "refio") {
    if (input.runJson === undefined || input.runJson === null) return undefined;
    return withLoop(withLoopVerification(await landTrace({
      entryId: input.entryId,
      dataDir: input.dataDir,
      source: "refio-run-json",
      events: normalizeRefioRunJson(input.runJson),
      ...(input.runJsonSrc ? { runJsonSrc: input.runJsonSrc } : {}),
      persist: input.persist,
    }), input.runJson));
  }

  const lines = input.timedLines;
  const source = input.source;
  if (!lines || !source) return undefined;

  const events =
    source === "claude-stream-json"
      ? normalizeClaudeStreamJson(lines)
      : source === "codex-jsonl"
        ? normalizeCodexJsonl(lines)
        : normalizeGeminiStreamJson(lines);

  return withLoop(
    await landTrace({
      entryId: input.entryId,
      dataDir: input.dataDir,
      source,
      events,
      rawLog: input.rawLog ?? "",
      persist: input.persist,
    }),
  );
}

// What the run says about reasoning: what the caller asked the harness for, and what
// the run's own stream shows actually happened. Only Claude Code and Refio report it
// today; for the others the evidence stays empty and only the request is recorded.
export function thinkingForRun(input: {
  harnessId: string;
  requested: Thinking["requested"];
  runJson?: unknown;
  timedLines?: TimedLine[];
}): Thinking {
  if (input.harnessId === "refio") {
    return buildThinking(input.requested, refioThinkingEvidence(input.runJson));
  }
  if (input.harnessId === "claude-code") {
    return buildThinking(input.requested, claudeThinkingEvidence(input.timedLines ?? []));
  }
  return buildThinking(input.requested, { observed: false, tokens: null });
}
