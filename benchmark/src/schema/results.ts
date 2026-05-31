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
  notes: z.string().optional(),
  runAt: z.string().datetime(),
  createdAt: z.string().datetime(),
});

export const ResultsFileSchema = z.object({
  version: z.literal(1),
  models: z.array(ModelSchema),
  environments: z.array(EnvironmentSchema),
  results: z.array(ResultSchema),
});

export type Model = z.infer<typeof ModelSchema>;
export type Environment = z.infer<typeof EnvironmentSchema>;
export type Score = z.infer<typeof ScoreSchema>;
export type Attachment = z.infer<typeof AttachmentSchema>;
export type Result = z.infer<typeof ResultSchema>;
export type ResultsFile = z.infer<typeof ResultsFileSchema>;
