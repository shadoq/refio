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
  resultIds: z.array(z.string()).min(2),
  deterministic: z.object({
    scoreVariance: z.number().nonnegative(),
    codeSimilarity: z.number().min(0).max(1),
  }),
  judges: z.array(StabilityJudgeSchema).default([]),
  computedAt: z.string().datetime(),
});

export const ResultSchema = z.object({
  id: z.string(),
  taskId: z.string(),
  modelId: z.string(),
  environmentId: z.string(),
  attemptNumber: z.number().int().positive(),
  scores: z.array(ScoreSchema).min(1),
  durationMs: z.number().int().nonnegative().optional(),
  tokensIn: z.number().int().nonnegative().optional(),
  tokensOut: z.number().int().nonnegative().optional(),
  costUsd: z.number().nonnegative().optional(),
  attachments: z.array(AttachmentSchema).default([]),
  judgeScores: z.array(JudgeScoreSetSchema).default([]),
  notes: z.string().optional(),
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
    attemptNumber: z.number().int().positive(),
    durationMs: z.number().int().nonnegative().optional(),
    tokensIn: z.number().int().nonnegative().optional(),
    tokensOut: z.number().int().nonnegative().optional(),
    costUsd: z.number().nonnegative().optional(),
    attachments: z.array(AttachmentSchema).default([]),
    judgeScores: z.array(JudgeScoreSetSchema).default([]),
    autoVerdict: AutoVerdictSchema.optional(),
    notes: z.string().optional(),
    runAt: z.string().datetime(),
    createdAt: z.string().datetime(),
  })
  .strict();

export const ResultsFileSchema = z.object({
  version: z.literal(1),
  models: z.array(ModelSchema),
  environments: z.array(EnvironmentSchema),
  results: z.array(ResultSchema),
  stability: z.array(StabilityEntrySchema).default([]),
  inbox: z.array(InboxEntrySchema).default([]),
});

export type Model = z.infer<typeof ModelSchema>;
export type Environment = z.infer<typeof EnvironmentSchema>;
export type Score = z.infer<typeof ScoreSchema>;
export type Attachment = z.infer<typeof AttachmentSchema>;
export type JudgeScore = z.infer<typeof JudgeScoreSchema>;
export type JudgeScoreSet = z.infer<typeof JudgeScoreSetSchema>;
export type StabilityJudge = z.infer<typeof StabilityJudgeSchema>;
export type StabilityEntry = z.infer<typeof StabilityEntrySchema>;
export type Result = z.infer<typeof ResultSchema>;
export type AutoVerdict = z.infer<typeof AutoVerdictSchema>;
export type InboxEntry = z.infer<typeof InboxEntrySchema>;
export type ResultsFile = z.infer<typeof ResultsFileSchema>;
