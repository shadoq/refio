import { z } from "zod";

export const ModelSchema = z.object({
  id: z.string().regex(/^[a-z0-9_./:-]+$/),
  name: z.string(),
  provider: z.string(),
  parameterCount: z.string().optional(),
  notes: z.string().optional(),
});

export const EnvironmentSchema = z.object({
  id: z.string().regex(/^[a-z0-9_-]+$/),
  name: z.string(),
  type: z.enum(["local", "cloud"]),
  hardware: z.string().optional(),
  notes: z.string().optional(),
});

// What drove the agent that produced a result: Refio itself, or an external coding
// agent (Claude Code, Codex) running on its own model with its own scaffolding.
// `conditions` records what that harness was allowed to do (network, permissions,
// turn limit) so a cross-harness comparison can be read honestly.
export const HarnessSchema = z.object({
  id: z.string().regex(/^[a-z0-9_-]+$/),
  name: z.string(),
  kind: z.enum(["refio", "external"]),
  version: z.string().optional(),
  conditions: z.string().optional(),
  notes: z.string().optional(),
});

// Every row recorded before this dimension existed came from Refio, so the default
// keeps the whole historical data set loadable without rewriting a single row.
const harnessId = z.string().default("refio");

export const ScoreSchema = z.object({
  criterionId: z.string(),
  value: z.number(),
});

export const AttachmentSchema = z.object({
  type: z.enum(["image", "html", "video", "video-embed", "archive", "file"]),
  src: z.string(),
  caption: z.string().optional(),
});

// A single criterion score produced by a strong-judge agent, with an optional
// short rationale (the judge prompt requires one for 0 and 0.5 values).
export const JudgeScoreSchema = z.object({
  criterionId: z.string(),
  value: z.number(),
  rationale: z.string().optional(),
});

// One judge's full verdict for a single result. Either `scores` carries a valid
// verdict, or `error` describes why the judge failed - the two are mutually
// exclusive. Screenshots are relative paths into the result's `_judge/` folder.
export const JudgeScoreSetSchema = z.object({
  judgeId: z.string(),
  judgeModel: z.string(),
  judgedAt: z.string().datetime(),
  scores: z.array(JudgeScoreSchema).default([]),
  screenshots: z.array(z.string()).default([]),
  consoleErrors: z.array(z.string()).default([]),
  error: z.string().nullable().optional(),
});

// A judge's stability verdict over all attempts of one (task, model, env) group.
export const StabilityJudgeSchema = z.object({
  judgeId: z.string(),
  judgeModel: z.string(),
  value: z.number(),
  rationale: z.string().optional(),
  judgedAt: z.string().datetime(),
});

// Cross-attempt stability for one (task, model, environment) group. Keyed
// logically by that triple; requires at least two attempts with an HTML artifact.
export const StabilityEntrySchema = z.object({
  taskId: z.string(),
  modelId: z.string(),
  environmentId: z.string(),
  harnessId,
  resultIds: z.array(z.string()).min(2),
  deterministic: z.object({
    scoreVariance: z.number().nonnegative(),
    codeSimilarity: z.number().min(0).max(1),
  }),
  judges: z.array(StabilityJudgeSchema).default([]),
  computedAt: z.string().datetime(),
});

// A deterministic summary of what the agent actually DID during one run: how many
// turns it took, which classes of tool it called, how soon it wrote the deliverable
// and whether it ran a build or test itself. Computed from the agent's own event
// stream, never from a model, so two harnesses can be compared on behaviour and not
// only on the final score. The event-by-event log lives next to the artifact and is
// referenced by `path`.
export const TraceSummarySchema = z.object({
  format: z.literal("refio-trace/1"),
  source: z.enum([
    "refio-run-json",
    "claude-stream-json",
    "codex-jsonl",
    "gemini-stream-json",
    "hermes-transcript",
  ]),
  path: z.string(),
  rawPath: z.string().optional(),
  runJsonPath: z.string().optional(),
  turns: z.number().int().nonnegative(),
  // How the run ended, read from the harness's own end event. A run killed by its
  // timeout and one that failed in ten seconds are otherwise the same row.
  endReason: z.enum(["completed", "failed", "incomplete", "cancelled", "limit", "unknown"]),
  toolCalls: z.number().int().nonnegative(),
  reads: z.number().int().nonnegative(),
  writes: z.number().int().nonnegative(),
  shellRuns: z.number().int().nonnegative(),
  searches: z.number().int().nonnegative(),
  otherCalls: z.number().int().nonnegative(),
  // The tool call itself was rejected or errored.
  toolErrors: z.number().int().nonnegative(),
  // A shell command that returned non-zero. Kept apart from toolErrors because for
  // some harnesses a grep with no match looks exactly like a failed tool call.
  nonZeroExits: z.number().int().nonnegative(),
  // Calls that repeated one the agent had already made: the first is work, the rest
  // bought nothing.
  duplicateCalls: z.number().int().nonnegative(),
  // Longest run of consecutive identical calls; 0 when nothing repeated back to back.
  repeatedCallStreak: z.number().int().nonnegative(),
  // The same, counting only calls that failed: an agent stuck retrying what cannot work.
  repeatedFailedCallStreak: z.number().int().nonnegative(),
  // Did anything useful happen after the last failing call. Null when nothing failed,
  // because a run with nothing to recover from is not evidence either way.
  recoveredFromError: z.boolean().nullable(),
  readsBeforeFirstWrite: z.number().int().nonnegative(),
  searchesBeforeFirstWrite: z.number().int().nonnegative(),
  // Distinct files the agent wrote to, as opposed to the number of writing calls.
  filesWritten: z.number().int().nonnegative(),
  // 1-based index of the first writing call among all tool calls; null when the run
  // never wrote anything.
  firstWriteAtCall: z.number().int().positive().nullable(),
  editsAfterFirstWrite: z.number().int().nonnegative(),
  timeToFirstWriteMs: z.number().int().nonnegative().nullable(),
  // The model itself ran a build or a test AFTER it had written something, as opposed
  // to a loop doing it for the model or a build that only checked the fixture.
  selfVerified: z.boolean(),
  toolHistogram: z.record(z.string(), z.number().int().nonnegative()),
  // Every call's class, in order. Optional so runs recorded before it existed stay
  // valid; absent means unmeasured, never "the agent did nothing".
  classOrder: z.array(z.enum(["read", "write", "shell", "search", "other"])).optional(),
  // What the run's own loop reported about itself. Only a harness that exposes these
  // fills them in; an absent field is unmeasured, never a zero.
  loop: z
    .object({
      contextOverflow: z.boolean().optional(),
      // The loop's own name for why it stopped, when it has one.
      failureMarker: z.string().optional(),
      // A build or test the HARNESS ran for the model, which is a different thing from
      // selfVerified above.
      verification: z
        .object({
          ran: z.boolean(),
          attempts: z.number().int().nonnegative().optional(),
          result: z.string().optional(),
        })
        .optional(),
      // What the loop decided to put in the prompt and what it had to leave out. A loop
      // that rebuilds its prompt every iteration and trims by position quietly loses the
      // agent's own earlier findings, and nothing else in this record would show it.
      context: z
        .object({
          budgetTokens: z.number().int().nonnegative().optional(),
          usedTokens: z.number().int().nonnegative().optional(),
          // Conversation messages dropped to fit the budget, across the whole run.
          droppedMessages: z.number().int().nonnegative().optional(),
          // Earlier tool steps dropped the same way.
          droppedSteps: z.number().int().nonnegative().optional(),
          // Per section, how often it was left out and why.
          drops: z.record(z.string(), z.number().int().nonnegative()).optional(),
        })
        .optional(),
    })
    .optional(),
});

// Everything needed to run this attempt again and get a comparable one. Two runs that
// differ in context window, permission mode or prompt text are not the same
// measurement, and until this is recorded the data cannot say which of those it was.
export const RunContextSchema = z.object({
  // Version string the agent's own CLI reports.
  harnessVersion: z.string().optional(),
  // Digest of the resolved prompt, so an edited prompt stops looking like the same task.
  promptSha256: z.string().optional(),
  // Tokens the model was given to work in. The single biggest confound when the same
  // local model is measured under two harnesses.
  contextWindow: z.number().int().positive().optional(),
  maxOutputTokens: z.number().int().positive().optional(),
  // Where the model actually ran, resolved rather than assumed.
  modelServer: z.string().optional(),
  // What the agent was allowed to do without asking.
  permissionMode: z.string().optional(),
  timeoutMs: z.number().int().positive().optional(),
  maxTurns: z.number().int().positive().optional(),
  // The command line as it was issued, minus the environment, which carries tokens.
  commandLine: z.string().optional(),
  // Which tool channel the Refio harness was told to use. Left to Refio, a model name
  // its registry does not know falls back to the JSON-envelope-in-prose path, which is
  // a different mechanism from the one every other harness runs on - so the sweep's
  // choice belongs in the data rather than in somebody's memory.
  nativeTools: z.enum(["auto", "always", "never"]).optional(),
  // Host extensions switched off for this run, so a later reader knows the agent was
  // measured as it ships rather than as this machine is configured.
  pluginsDisabled: z.array(z.string()).optional(),
});

// Whether the model was allowed to reason before answering, and whether it actually
// did. A thinking model with reasoning switched off behaves like a different model -
// different speed, different tool discipline, different output - so a run that does
// not record this cannot be compared with one that used the other setting.
// `requested` is what the harness asked for, `observed` is what the run's own event
// stream shows, and the two can differ (a model that ignores the setting, or a
// harness that cannot express it).
export const ThinkingSchema = z.object({
  requested: z.enum(["on", "off", "unknown"]),
  // Provider-specific effort or budget label when the harness sets one.
  level: z.string().optional(),
  observed: z.boolean(),
  // Reasoning tokens the run reported, when it reports them at all.
  tokens: z.number().int().nonnegative().optional(),
});

export const ResultSchema = z.object({
  id: z.string(),
  taskId: z.string(),
  modelId: z.string(),
  environmentId: z.string(),
  harnessId,
  attemptNumber: z.number().int().positive(),
  scores: z.array(ScoreSchema).min(1),
  durationMs: z.number().int().nonnegative().optional(),
  tokensIn: z.number().int().nonnegative().optional(),
  tokensOut: z.number().int().nonnegative().optional(),
  costUsd: z.number().nonnegative().optional(),
  attachments: z.array(AttachmentSchema).default([]),
  judgeScores: z.array(JudgeScoreSetSchema).default([]),
  notes: z.string().optional(),
  trace: TraceSummarySchema.optional(),
  thinking: ThinkingSchema.optional(),
  runContext: RunContextSchema.optional(),
  runAt: z.string().datetime(),
  createdAt: z.string().datetime(),
});

// The advisory PASS/FAIL verdict of the e2e soft judge, carried on an inbox
// entry until a human promotes it. Never affects statistics on its own.
export const AutoVerdictSchema = z.object({
  verdict: z.enum(["PASS", "FAIL"]),
  confidence: z.number().optional(),
  reasons: z.array(z.string()).default([]),
});

// A completed automated run awaiting human scoring. It is a Result without the
// manual `scores` (a human adds those on promotion, in results[]), plus the
// artifact, metrics, deterministic judgeScores and the advisory autoVerdict.
// Strict so a stray `scores` key cannot leak manual scoring into the queue.
export const InboxEntrySchema = z
  .object({
    id: z.string(),
    taskId: z.string(),
    modelId: z.string(),
    environmentId: z.string(),
    harnessId,
    attemptNumber: z.number().int().positive(),
    durationMs: z.number().int().nonnegative().optional(),
    tokensIn: z.number().int().nonnegative().optional(),
    tokensOut: z.number().int().nonnegative().optional(),
    costUsd: z.number().nonnegative().optional(),
    attachments: z.array(AttachmentSchema).default([]),
    judgeScores: z.array(JudgeScoreSetSchema).default([]),
    autoVerdict: AutoVerdictSchema.optional(),
    notes: z.string().optional(),
    trace: TraceSummarySchema.optional(),
    thinking: ThinkingSchema.optional(),
    runContext: RunContextSchema.optional(),
    runAt: z.string().datetime(),
    createdAt: z.string().datetime(),
  })
  .strict();

export const ResultsFileSchema = z.object({
  version: z.literal(1),
  models: z.array(ModelSchema),
  environments: z.array(EnvironmentSchema),
  harnesses: z.array(HarnessSchema).default([]),
  results: z.array(ResultSchema),
  stability: z.array(StabilityEntrySchema).default([]),
  inbox: z.array(InboxEntrySchema).default([]),
});

export type Model = z.infer<typeof ModelSchema>;
export type Environment = z.infer<typeof EnvironmentSchema>;
export type Harness = z.infer<typeof HarnessSchema>;
export type Score = z.infer<typeof ScoreSchema>;
export type Attachment = z.infer<typeof AttachmentSchema>;
export type JudgeScore = z.infer<typeof JudgeScoreSchema>;
export type JudgeScoreSet = z.infer<typeof JudgeScoreSetSchema>;
export type StabilityJudge = z.infer<typeof StabilityJudgeSchema>;
export type StabilityEntry = z.infer<typeof StabilityEntrySchema>;
export type TraceSummary = z.infer<typeof TraceSummarySchema>;
export type Thinking = z.infer<typeof ThinkingSchema>;
export type RunContext = z.infer<typeof RunContextSchema>;
export type Result = z.infer<typeof ResultSchema>;
export type AutoVerdict = z.infer<typeof AutoVerdictSchema>;
export type InboxEntry = z.infer<typeof InboxEntrySchema>;
export type ResultsFile = z.infer<typeof ResultsFileSchema>;
