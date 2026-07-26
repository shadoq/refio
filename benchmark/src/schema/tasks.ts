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
  // When true, the task and its results are hidden from the public results view
  // and excluded from all measurements. Admin editors still see it. Absent = visible.
  hidden: z.boolean().optional(),
  createdAt: z.string().datetime(),
  updatedAt: z.string().datetime(),
});

export const TasksFileSchema = z.object({
  version: z.literal(1),
  coreCriteria: z.array(CriterionSchema).min(1),
  // Criteria scored only by strong-judge agents (global, applied to every task)
  // on top of coreCriteria and a task's extraCriteria.
  judgeCriteria: z.array(CriterionSchema).default([]),
  tasks: z.array(TaskSchema),
});

export type RatingScale = z.infer<typeof RatingScaleSchema>;
export type Criterion = z.infer<typeof CriterionSchema>;
export type Task = z.infer<typeof TaskSchema>;
export type TasksFile = z.infer<typeof TasksFileSchema>;
