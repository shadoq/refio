import { z } from "zod";

// A criterion is shared with the e2e case catalog (a case carries its own extra
// criteria), so its definition lives with the e2e toolchain and is re-exported here.
// One definition, two consumers.
export { RatingScaleSchema, CriterionSchema } from "@e2e/schema/criterion";
export type { RatingScale, Criterion } from "@e2e/schema/criterion";
import { CriterionSchema } from "@e2e/schema/criterion";

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

export type Task = z.infer<typeof TaskSchema>;
export type TasksFile = z.infer<typeof TasksFileSchema>;
