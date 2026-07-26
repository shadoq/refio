// Pure helpers that turn one headless run into an inbox entry (a run awaiting
// human scoring). No IO, so both vitest (@ alias) and the tsx importer (relative)
// can use it. Type-only schema imports are erased at runtime.
import type {
  InboxEntry,
  JudgeScoreSet,
  Attachment,
  AutoVerdict,
  ResultsFile,
  Result,
  Score,
} from "../../schema/results";

export interface ParsedRun {
  status: string;
  toolCalls: string[];
  finalOutput: string;
  contextOverflow: boolean;
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

  return {
    status: typeof session.status === "string" ? session.status : "UNKNOWN",
    toolCalls,
    finalOutput: typeof doc.finalOutput === "string" ? doc.finalOutput : "",
    contextOverflow: metrics.contextOverflow === true,
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

export function makeInboxId(caseId: string, modelId: string, attempt: number): string {
  return `${caseId}__${sanitizeModelId(modelId)}__${attempt}`;
}

// A deterministic PASS/FAIL summary from the deterministic scores: the run passed
// the hard checks when compliance is full and any measured criterion is non-zero.
export function deterministicVerdict(
  scores: Array<{ criterionId: string; value: number }>,
): AutoVerdict {
  const by = new Map(scores.map((s) => [s.criterionId, s.value]));
  const compliance = by.get("compliance");
  const works = by.get("works_out_of_box");
  const agentLogic = by.get("agent_logic");

  const pass =
    compliance === 1 &&
    (works === undefined || works > 0) &&
    (agentLogic === undefined || agentLogic > 0);

  const reasons = scores.map((s) => `${s.criterionId}=${s.value}`);
  return { verdict: pass ? "PASS" : "FAIL", reasons };
}

export interface InboxEntryInput {
  caseId: string;
  mode: string;
  modelId: string;
  environmentId: string;
  attemptNumber: number;
  run: ParsedRun;
  judge: JudgeScoreSet;
  attachments: Attachment[];
  autoVerdict: AutoVerdict;
  now: string;
}

// Assemble the inbox entry. Optional metric fields are only set when the run
// actually reported them, keeping the persisted JSON clean.
export function buildInboxEntry(input: InboxEntryInput): InboxEntry {
  const entry: InboxEntry = {
    id: makeInboxId(input.caseId, input.modelId, input.attemptNumber),
    taskId: input.caseId,
    modelId: input.modelId,
    environmentId: input.environmentId,
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
