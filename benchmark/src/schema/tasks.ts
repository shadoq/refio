import { z } from "zod";

export const RatingScaleSchema = z.object({
  values: z.array(z.number()).min(2),
  labels: z.record(z.string(), z.string()).optional(),
});

export const CriterionSchema = z.object({
  id: z.string().regex(/^[a-z0-9_-]+$/),
  name: z.string(),
  description: z.string(),
  scale: RatingScaleSchema,
  weight: z.number().positive().default(1.0),
});

export const TaskSchema = z.object({
  id: z.string().regex(/^[a-z0-9_-]+$/),
  name: z.string(),
  description: z.string(),
  systemPrompt: z.string(),
  extraCriteria: z.array(CriterionSchema).default([]),
  createdAt: z.string().datetime(),
  updatedAt: z.string().datetime(),
});

export const TasksFileSchema = z.object({
  version: z.literal(1),
  coreCriteria: z.array(CriterionSchema).min(1),
  tasks: z.array(TaskSchema),
});

export type RatingScale = z.infer<typeof RatingScaleSchema>;
export type Criterion = z.infer<typeof CriterionSchema>;
export type Task = z.infer<typeof TaskSchema>;
export type TasksFile = z.infer<typeof TasksFileSchema>;
