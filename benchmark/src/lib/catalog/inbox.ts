// Pure helpers that turn one headless run into an inbox entry (a run awaiting
// human scoring). No IO, so both vitest (@ alias) and the tsx importer (relative)
// can use it. Type-only schema imports are erased at runtime.
import type {
  InboxEntry,
  RunContext,
  JudgeScoreSet,
  Attachment,
  AutoVerdict,
  ResultsFile,
  Result,
  Score,
  Thinking,
  TraceSummary,
} from "../../schema/results";

// What the loop said about itself, as opposed to what the model did. Only a harness
// that exposes these fills them in.
export interface LoopSignals {
  contextOverflow?: boolean;
  failureMarker?: string;
  verification?: { ran: boolean; attempts?: number; result?: string };
  context?: {
    budgetTokens?: number;
    usedTokens?: number;
    droppedMessages?: number;
    droppedSteps?: number;
    drops?: Record<string, number>;
  };
}

export interface ParsedRun {
  status: string;
  toolCalls: string[];
  finalOutput: string;
  contextOverflow: boolean;
  // Read from the run document and carried onto the entry. Previously parsed and then
  // dropped, which meant a run that silently overflowed its context looked like any
  // other run.
  loop: LoopSignals;
  metrics: {
    durationMs?: number;
    tokensIn?: number;
    tokensOut?: number;
    costUsd?: number;
  };
}

function num(v: unknown): number | undefined {
  return typeof v === "number" ? v : undefined;
}

// Read the headless run.json shape defensively: a partial or malformed document
// must degrade to UNKNOWN/empty rather than throw.
export function parseRunJson(raw: unknown): ParsedRun {
  const doc = (raw ?? {}) as Record<string, unknown>;
  const session = (doc.session ?? {}) as Record<string, unknown>;
  const metrics = (doc.metrics ?? {}) as Record<string, unknown>;
  const conversation = Array.isArray(doc.conversation) ? doc.conversation : [];

  const toolCalls: string[] = [];
  for (const turn of conversation) {
    const calls = (turn as Record<string, unknown>)?.toolCalls;
    if (Array.isArray(calls)) toolCalls.push(...calls.filter((c): c is string => typeof c === "string"));
  }

  const loop: LoopSignals = {};
  if (typeof metrics.contextOverflow === "boolean") loop.contextOverflow = metrics.contextOverflow;
  if (typeof metrics.failureMarker === "string" && metrics.failureMarker !== "") {
    loop.failureMarker = metrics.failureMarker;
  }
  const verification = (metrics.verification ?? null) as Record<string, unknown> | null;
  if (verification && typeof verification.ran === "boolean") {
    loop.verification = {
      ran: verification.ran,
      ...(num(verification.attempts) !== undefined ? { attempts: num(verification.attempts) } : {}),
      ...(typeof verification.result === "string" ? { result: verification.result } : {}),
    };
  }

  // The loop's own account of what it could not fit in the prompt. Absent from today's
  // run documents; read here so it lands the moment the loop starts reporting it.
  const contextTrace = (metrics.context ?? null) as Record<string, unknown> | null;
  if (contextTrace) {
    const drops = (contextTrace.drops ?? null) as Record<string, unknown> | null;
    loop.context = {
      ...(num(contextTrace.budgetTokens) !== undefined ? { budgetTokens: num(contextTrace.budgetTokens) } : {}),
      ...(num(contextTrace.usedTokens) !== undefined ? { usedTokens: num(contextTrace.usedTokens) } : {}),
      ...(num(contextTrace.droppedMessages) !== undefined ? { droppedMessages: num(contextTrace.droppedMessages) } : {}),
      ...(num(contextTrace.droppedSteps) !== undefined ? { droppedSteps: num(contextTrace.droppedSteps) } : {}),
      ...(drops
        ? {
            drops: Object.fromEntries(
              Object.entries(drops).filter((e): e is [string, number] => typeof e[1] === "number"),
            ),
          }
        : {}),
    };
  }

  return {
    status: typeof session.status === "string" ? session.status : "UNKNOWN",
    toolCalls,
    finalOutput: typeof doc.finalOutput === "string" ? doc.finalOutput : "",
    contextOverflow: metrics.contextOverflow === true,
    loop,
    metrics: {
      durationMs: num(metrics.durationMs),
      tokensIn: num(metrics.tokensIn),
      tokensOut: num(metrics.tokensOut),
      costUsd: num(metrics.costUsd),
    },
  };
}

// Filesystem/id-safe model id (the provider slash and tag colon become dashes).
// Reused for inbox ids and to resolve {{MODEL_ID}} into a deliverable filename.
export function sanitizeModelId(modelId: string): string {
  return modelId.replace(/[^a-zA-Z0-9_.-]+/g, "-");
}

// The id also names the attachment folder on disk. Refio runs keep the historical
// two-part form so existing queue entries and their artifacts stay valid; an external
// harness adds its own segment, without which the same model imported under Refio and
// under Claude Code would overwrite one run with the other.
export function makeInboxId(
  caseId: string,
  modelId: string,
  attempt: number,
  harnessId: string = "refio",
): string {
  const model = sanitizeModelId(modelId);
  if (harnessId === "refio") return `${caseId}__${model}__${attempt}`;
  return `${caseId}__${sanitizeModelId(harnessId)}__${model}__${attempt}`;
}

// A deterministic PASS/FAIL summary from the deterministic scores: every criterion
// that WAS measured has to hold - compliance in full, the rest above zero - and at
// least one has to have been measured at all.
//
// A criterion that could not be measured is neither a pass nor a failure, and it is
// named in the reasons so the queue shows what the verdict is actually based on. A run
// with nothing measured cannot pass: there is no evidence to pass on.
export function deterministicVerdict(
  scores: Array<{ criterionId: string; value: number }>,
): AutoVerdict {
  const by = new Map(scores.map((s) => [s.criterionId, s.value]));
  const compliance = by.get("compliance");
  const works = by.get("works_out_of_box");
  const agentLogic = by.get("agent_logic");

  const pass =
    scores.length > 0 &&
    (compliance === undefined || compliance === 1) &&
    (works === undefined || works > 0) &&
    (agentLogic === undefined || agentLogic > 0);

  const reasons = scores.map((s) => `${s.criterionId}=${s.value}`);
  for (const id of ["compliance", "works_out_of_box", "agent_logic"]) {
    if (!by.has(id)) reasons.push(`${id}=not measured`);
  }
  return { verdict: pass ? "PASS" : "FAIL", reasons };
}

export interface InboxEntryInput {
  caseId: string;
  mode: string;
  modelId: string;
  environmentId: string;
  harnessId: string;
  attemptNumber: number;
  run: ParsedRun;
  judge: JudgeScoreSet;
  attachments: Attachment[];
  autoVerdict: AutoVerdict;
  now: string;
  // Absent when the run left no readable event stream (an agent that crashed before
  // its first event, or an import from an old run.json).
  trace?: TraceSummary;
  // What the model was allowed to do before answering, and what it actually did.
  thinking?: Thinking;
  // Everything needed to run this attempt again and get a comparable one.
  runContext?: RunContext;
}

// Assemble the inbox entry. Optional metric fields are only set when the run
// actually reported them, keeping the persisted JSON clean.
export function buildInboxEntry(input: InboxEntryInput): InboxEntry {
  const entry: InboxEntry = {
    id: makeInboxId(input.caseId, input.modelId, input.attemptNumber, input.harnessId),
    taskId: input.caseId,
    modelId: input.modelId,
    environmentId: input.environmentId,
    harnessId: input.harnessId,
    attemptNumber: input.attemptNumber,
    attachments: input.attachments,
    judgeScores: [input.judge],
    autoVerdict: input.autoVerdict,
    notes: "auto-import; confirm look and code",
    runAt: input.now,
    createdAt: input.now,
  };
  if (input.run.metrics.durationMs !== undefined) entry.durationMs = input.run.metrics.durationMs;
  if (input.run.metrics.tokensIn !== undefined) entry.tokensIn = input.run.metrics.tokensIn;
  if (input.run.metrics.tokensOut !== undefined) entry.tokensOut = input.run.metrics.tokensOut;
  if (input.run.metrics.costUsd !== undefined) entry.costUsd = input.run.metrics.costUsd;
  if (input.trace !== undefined) entry.trace = input.trace;
  if (input.thinking !== undefined) entry.thinking = input.thinking;
  if (input.runContext !== undefined) entry.runContext = input.runContext;
  return entry;
}

// Promote an inbox entry into results[] with the human's manual scores: it keeps
// the artifact and metrics, but starts with no judgeScores - the strong judges
// (claude-code, codex) score the result later in a dedicated judging pass, so the
// deterministic auto-judge from the queue is intentionally not carried over. It
// also drops the advisory autoVerdict (results have no such field) and removes the
// entry from inbox[].
export function promoteInboxEntry(
  file: ResultsFile,
  entryId: string,
  scores: Score[],
  now: string,
): ResultsFile {
  const entry = file.inbox.find((e) => e.id === entryId);
  if (!entry) throw new Error(`inbox entry not found: ${entryId}`);

  const result: Result = {
    id: entry.id,
    taskId: entry.taskId,
    modelId: entry.modelId,
    environmentId: entry.environmentId,
    harnessId: entry.harnessId,
    attemptNumber: entry.attemptNumber,
    scores,
    attachments: entry.attachments,
    judgeScores: [],
    runAt: entry.runAt,
    createdAt: now,
  };
  if (entry.durationMs !== undefined) result.durationMs = entry.durationMs;
  if (entry.tokensIn !== undefined) result.tokensIn = entry.tokensIn;
  if (entry.tokensOut !== undefined) result.tokensOut = entry.tokensOut;
  if (entry.costUsd !== undefined) result.costUsd = entry.costUsd;
  if (entry.notes !== undefined) result.notes = entry.notes;
  if (entry.trace !== undefined) result.trace = entry.trace;
  if (entry.thinking !== undefined) result.thinking = entry.thinking;
  if (entry.runContext !== undefined) result.runContext = entry.runContext;

  return {
    ...file,
    results: [...file.results, result],
    inbox: file.inbox.filter((e) => e.id !== entryId),
  };
}

// Drop an inbox entry without promoting it (a bad run that should not be scored).
export function discardInboxEntry(file: ResultsFile, entryId: string): ResultsFile {
  return { ...file, inbox: file.inbox.filter((e) => e.id !== entryId) };
}
