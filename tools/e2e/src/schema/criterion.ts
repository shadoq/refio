import { z } from "zod";

// A scored review criterion. Lives here rather than next to the benchmark task
// schema because a catalog case carries its own extra criteria, and the e2e
// toolchain must stay usable without the benchmark app. The benchmark viewer
// re-exports these so there is exactly one definition in the repo.

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

export type RatingScale = z.infer<typeof RatingScaleSchema>;
export type Criterion = z.infer<typeof CriterionSchema>;
