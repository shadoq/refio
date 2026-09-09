// Pure translation of an external coding agent's CLI output into the run.json shape
// the import path already understands (see parseRunJson in ./inbox.ts). No IO and no
// node imports, so vitest and the tsx importer can both use it.
//
// The two agents report very differently: Claude Code prints one JSON envelope with
// usage and billed cost, Codex prints a JSONL event stream and bills through a
// subscription. Whatever an agent does not report stays undefined - a zero would read
// as a measured value and quietly poison the cost and throughput comparisons.

export interface AgentRun {
  status: "SUCCESS" | "FAILED";
  finalOutput: string;
  durationMs?: number;
  tokensIn?: number;
  tokensOut?: number;
  costUsd?: number;
}

function num(v: unknown): number | undefined {
  return typeof v === "number" && Number.isFinite(v) ? v : undefined;
}

function sum(...values: Array<number | undefined>): number | undefined {
  const present = values.filter((v): v is number => v !== undefined);
  return present.length > 0 ? present.reduce((a, b) => a + b, 0) : undefined;
}

// `claude -p --output-format json` emits either the result event alone or an array of
// events ending with it. Anything else (a crash, a truncated pipe) leaves the metrics
// unset and the run marked failed.
export function parseClaudeCodeRun(stdout: string, exitCode: number): AgentRun {
  let events: Array<Record<string, unknown>>;
  try {
    const parsed: unknown = JSON.parse(stdout);
    events = (Array.isArray(parsed) ? parsed : [parsed]) as Array<Record<string, unknown>>;
  } catch {
    return { status: "FAILED", finalOutput: stdout.trim() };
  }

  const result = [...events].reverse().find((e) => typeof e?.result === "string");
  if (!result) return { status: "FAILED", finalOutput: stdout.trim() };

  const usage = (result.usage ?? {}) as Record<string, unknown>;
  const failed = exitCode !== 0 || result.is_error === true || result.subtype === "error";

  const run: AgentRun = {
    status: failed ? "FAILED" : "SUCCESS",
    finalOutput: String(result.result ?? ""),
  };
  // Cached and cache-creation tokens are still prompt tokens the model was given;
  // counting only input_tokens would under-report the real prompt several times over.
  const tokensIn = sum(
    num(usage.input_tokens),
    num(usage.cache_creation_input_tokens),
    num(usage.cache_read_input_tokens),
  );
  if (tokensIn !== undefined) run.tokensIn = tokensIn;
  const tokensOut = num(usage.output_tokens);
  if (tokensOut !== undefined) run.tokensOut = tokensOut;
  const durationMs = num(result.duration_ms);
  if (durationMs !== undefined) run.durationMs = durationMs;
  const costUsd = num(result.total_cost_usd);
  if (costUsd !== undefined) run.costUsd = costUsd;
  return run;
}

// Recursively look for a usage object anywhere in an event. Codex's event names are
// not a stable contract, so binding to one envelope would break on the next release;
// the numbers themselves are named consistently.
function findUsage(node: unknown): { input?: number; output?: number } | null {
  if (node === null || typeof node !== "object") return null;
  const obj = node as Record<string, unknown>;
  const input = num(obj.input_tokens);
  const output = num(obj.output_tokens);
  if (input !== undefined || output !== undefined) return { input, output };
  for (const value of Object.values(obj)) {
    const found = findUsage(value);
    if (found) return found;
  }
  return null;
}

// `codex exec --json` prints one event per line; the final agent message is read from
// the --output-last-message file, and the wall clock is measured by the caller because
// Codex does not report it. There is no per-run cost: Codex bills by subscription.
export function parseCodexRun(
  stdoutJsonl: string,
  lastMessage: string,
  exitCode: number,
  durationMs: number,
): AgentRun {
  let usage: { input?: number; output?: number } | null = null;
  for (const line of stdoutJsonl.split("\n")) {
    const trimmed = line.trim();
    if (trimmed === "") continue;
    let event: unknown;
    try {
      event = JSON.parse(trimmed);
    } catch {
      continue; // a progress line that is not an event must not fail the whole run
    }
    const found = findUsage(event);
    if (found) usage = found; // the last report is the cumulative one
  }

  const run: AgentRun = {
    status: exitCode === 0 ? "SUCCESS" : "FAILED",
    finalOutput: lastMessage.trim(),
    durationMs,
  };
  if (usage?.input !== undefined) run.tokensIn = usage.input;
  if (usage?.output !== undefined) run.tokensOut = usage.output;
  return run;
}

// The import path reads every run through parseRunJson, so an external agent's run has
// to arrive in that document shape. `conversation` stays empty on purpose: an external
// agent runs its own tool loop and reports no Refio tool names, and inventing them
// would feed the deterministic judge a fiction.
export function toRunJson(run: AgentRun): unknown {
  const metrics: Record<string, number> = {};
  if (run.durationMs !== undefined) metrics.durationMs = run.durationMs;
  if (run.tokensIn !== undefined) metrics.tokensIn = run.tokensIn;
  if (run.tokensOut !== undefined) metrics.tokensOut = run.tokensOut;
  if (run.costUsd !== undefined) metrics.costUsd = run.costUsd;
  return {
    schemaVersion: 1,
    session: { status: run.status },
    metrics,
    finalOutput: run.finalOutput,
    conversation: [],
  };
}
