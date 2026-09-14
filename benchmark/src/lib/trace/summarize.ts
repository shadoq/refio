// Deterministic metrics over a normalized trace: how many turns the agent took, which
// classes of tool it used, how soon it wrote the deliverable, how much of its work was
// wasted and whether it verified its own output. Nothing here asks a model anything, so
// two harnesses are compared on the same arithmetic.
import { BUILD_OR_TEST_RE } from "./tool-classes";
import type { TraceEvent, TracePaths, TraceSource } from "./types";
import type { TraceSummary } from "../../schema/results";

// How a run ended, in the four words every harness's own end event can be read as.
// Without it a run killed by its timeout is indistinguishable from one that failed in
// ten seconds, which is exactly the difference worth seeing.
export function endReasonOf(text: string | null): TraceSummary["endReason"] {
  const t = (text ?? "").trim().toLowerCase();
  if (t === "") return "unknown";
  if (t === "incomplete") return "incomplete";
  if (t === "cancelled" || t === "canceled") return "cancelled";
  if (t.includes("max_turn") || t.includes("maximum iterations")) return "limit";
  if (t.includes("error") || t.startsWith("fail")) return "failed";
  if (t.includes("success") || t === "completed" || t === "done") return "completed";
  return "unknown";
}

const PATH_RE = /(?:^|, )(?:file_path|filePath|path)=([^,]*)/g;

// A harness with no edit tool of its own names the file it wrote inside the command
// itself, so these are the same information as a path argument, just written in shell.
const REDIRECT_TARGET_RE = /(?:^|[^0-9>&])>>?\s*(?!\/dev\/)([^\s|&;<>]+)/;
const IN_PLACE_TARGET_RE = /\b(?:sed|perl|ruby)\s+-i\b\s*(?:'[^']*'|"[^"]*")?\s*(?:'[^']*'|"[^"]*"|[^\s]+)?\s+([^\s|&;<>]+\.[A-Za-z0-9]+)/;

// Which file a writing call touched, as the argument summary recorded it. A call whose
// path we cannot read still counts as a write; it just adds no file to the set.
export function pathsInArgs(args: string | null): string[] {
  if (!args) return [];
  const out: string[] = [];
  for (const m of args.matchAll(PATH_RE)) {
    const value = m[1].trim();
    if (value !== "" && value !== "?") out.push(value);
  }
  if (out.length > 0) return out;
  const command = /(?:^|, )(?:command|cmd)=([\s\S]*)$/.exec(args);
  if (!command) return out;
  const target =
    IN_PLACE_TARGET_RE.exec(command[1])?.[1] ?? REDIRECT_TARGET_RE.exec(command[1])?.[1];
  if (target) out.push(target.trim());
  return out;
}

interface PairedCall {
  event: TraceEvent;
  index: number;
  key: string;
  ok: boolean | null;
  exit: number | null;
}

// Pair every call with the result that answered it. Harnesses emit a batch of calls
// and then the matching results in the same order, so the oldest unanswered call is
// the one a result belongs to.
function pairCalls(events: TraceEvent[]): PairedCall[] {
  const calls: PairedCall[] = [];
  const pending: PairedCall[] = [];
  let index = 0;
  for (const e of events) {
    if (e.kind === "tool_call") {
      const call: PairedCall = {
        event: e,
        index: index++,
        key: `${e.tool ?? "?"}|${e.args ?? ""}`,
        ok: null,
        exit: null,
      };
      calls.push(call);
      pending.push(call);
    } else if (e.kind === "tool_result") {
      const call = pending.shift();
      if (!call) continue;
      call.ok = e.ok;
      call.exit = e.exit ?? null;
    }
  }
  return calls;
}

// The longest run of consecutive calls that repeat the same tool with the same
// arguments. One repeat is a retry; a streak is an agent stuck in place.
function longestStreak(calls: PairedCall[], accept: (c: PairedCall) => boolean): number {
  let best = 0;
  let current = 0;
  let previousKey: string | null = null;
  for (const call of calls) {
    if (accept(call) && call.key === previousKey) {
      current = current === 0 ? 2 : current + 1;
    } else {
      current = accept(call) ? 1 : 0;
    }
    previousKey = accept(call) ? call.key : null;
    best = Math.max(best, current);
  }
  return best < 2 ? 0 : best;
}

export function summarize(
  events: TraceEvent[],
  source: TraceSource,
  paths: TracePaths,
): TraceSummary {
  const calls = pairCalls(events);
  const byClass = (cls: string) => calls.filter((c) => c.event.cls === cls).length;

  // The shape of the run in the vocabulary every harness shares, so an expectation
  // about what the agent did can be checked against all of them and not only against
  // the one that reports tool names.
  const classOrder = events
    .filter((e) => e.kind === "tool_call")
    .map((e) => e.cls ?? "other");
  const toolHistogram: Record<string, number> = {};
  for (const call of calls) {
    const name = call.event.tool ?? "unknown";
    toolHistogram[name] = (toolHistogram[name] ?? 0) + 1;
  }

  const firstWriteIndex = calls.findIndex((c) => c.event.cls === "write");
  const firstWrite = firstWriteIndex >= 0 ? calls[firstWriteIndex] : null;
  const writes = byClass("write");
  const before = firstWriteIndex >= 0 ? calls.slice(0, firstWriteIndex) : calls;

  // A call is wasted when the agent had already made exactly that call. The first
  // occurrence is work; every later one bought nothing.
  const seen = new Set<string>();
  let duplicateCalls = 0;
  for (const call of calls) {
    if (seen.has(call.key)) duplicateCalls++;
    else seen.add(call.key);
  }

  const filesWritten = new Set<string>();
  for (const call of calls) {
    if (call.event.cls !== "write") continue;
    for (const p of pathsInArgs(call.event.args)) filesWritten.add(p);
  }

  // Did anything useful happen after the last failing call, or did the run stop there?
  // A run that never failed has nothing to recover from and is not counted either way.
  const lastFailure = [...calls].reverse().find((c) => c.ok === false);
  const recoveredFromError =
    lastFailure === undefined
      ? null
      : calls.some(
          (c) =>
            c.index > lastFailure.index &&
            (c.event.cls === "write" || (c.event.cls === "shell" && c.ok !== false)),
        );

  // A build or test the model ran ITSELF, and after it had written something: a build
  // run before any code exists checks the fixture, not the agent's work.
  const verifyIndex = calls.findIndex(
    (c) => c.event.cls === "shell" && BUILD_OR_TEST_RE.test(c.event.args ?? ""),
  );
  const selfVerified = verifyIndex >= 0 && firstWriteIndex >= 0 && verifyIndex > firstWriteIndex;

  const endEvent = [...events].reverse().find((e) => e.kind === "run_end");

  const summary: TraceSummary = {
    format: "refio-trace/1",
    source,
    path: paths.path,
    // The highest turn number reached, not the number of messages that said something:
    // a model that reasons silently and then calls a tool has still taken a turn.
    turns: events.reduce((max, e) => Math.max(max, e.turn), 0),
    endReason: endReasonOf(endEvent?.text ?? null),
    toolCalls: calls.length,
    reads: byClass("read"),
    writes,
    shellRuns: byClass("shell"),
    searches: byClass("search"),
    otherCalls: byClass("other"),
    // The tool call itself was rejected or errored. A shell command that merely
    // returned non-zero is counted separately, because for some harnesses that is how
    // a grep with no match looks.
    // Counted over the results themselves, not the paired calls: a stream that lost
    // the call a result answers must still report the failure.
    toolErrors: events.filter((e) => e.kind === "tool_result" && e.ok === false).length,
    nonZeroExits: events.filter(
      (e) => e.kind === "tool_result" && e.exit != null && e.exit !== 0,
    ).length,
    duplicateCalls,
    repeatedCallStreak: longestStreak(calls, () => true),
    repeatedFailedCallStreak: longestStreak(calls, (c) => c.ok === false),
    recoveredFromError,
    readsBeforeFirstWrite: before.filter((c) => c.event.cls === "read").length,
    searchesBeforeFirstWrite: before.filter((c) => c.event.cls === "search").length,
    filesWritten: filesWritten.size,
    firstWriteAtCall: firstWrite ? firstWriteIndex + 1 : null,
    editsAfterFirstWrite: firstWrite ? writes - 1 : 0,
    timeToFirstWriteMs: firstWrite?.event.tMs ?? null,
    selfVerified,
    toolHistogram,
    classOrder,
  };
  if (paths.rawPath) summary.rawPath = paths.rawPath;
  if (paths.runJsonPath) summary.runJsonPath = paths.runJsonPath;
  return summary;
}

export function toJsonl(events: TraceEvent[]): string {
  return events.map((e) => JSON.stringify(e)).join("\n");
}
