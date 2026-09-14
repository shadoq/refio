import { z } from "zod";
import { CriterionSchema } from "./criterion";

// A single catalog case is the one source of truth for a prompt. Cases live in
// test_data/e2e_catalog/<category>/<name>/. The e2e generator emits a headless
// scenario (test_data/e2e/<id>.json) from it; the benchmark toolchain emits an
// admin review task from the same case and scores runs against the same
// `assert` block. Long prompt text lives next to it in <id>.prompt.md, not here.

export const CaseCategory = z.enum([
  "games",
  "demoscene",
  "website",
  "neural-net",
  "single-file-app",
  "voxel",
  "analysis",
  "animation",
  "simulation",
  // A task whose deliverable is a set of modules rather than one page: scored by
  // running the fixture's own tests, not by rendering.
  "multi-file",
]);

export const CaseTier = z.enum(["easy", "medium", "hard", "stress"]);
export const CaseMode = z.enum(["AGENT", "PLAN", "CHAT"]);

// One needle matched against the produced deliverable. Exactly one of regex/text
// carries the pattern; regex uses POSIX ERE to match the e2e harness.
export const CaseNeedleSchema = z
  .object({
    regex: z.string().optional(),
    text: z.string().optional(),
  })
  .refine((n) => !!n.regex !== !!n.text, {
    message: "needle needs exactly one of regex or text",
  });

export const CaseSmokeSchema = z.object({
  entry: z.string(),
  domPresent: z.array(z.string()).default([]),
});

export const CaseAssertSchema = z.object({
  // Expected tool-call order as a subsequence of the run (agent_logic signal).
  toolOrder: z.array(z.string()).default([]),
  // AGENT: patterns the deliverable file must contain (compliance signal).
  needles: z.array(CaseNeedleSchema).default([]),
  // PLAN/CHAT: pattern the run's finalOutput must contain (compliance signal).
  needleInOutput: z.object({ regex: z.string() }).nullable().default(null),
  // Files that must stay byte-identical (PLAN read-only guard).
  fileUnchanged: z.array(z.string()).default([]),
  // Headless-browser smoke: entry file + DOM selectors expected present.
  smoke: CaseSmokeSchema.nullable().default(null),
  // Build command for multi-file cases (exit 0). Null for single-file.
  buildCmd: z.string().nullable().default(null),
  noContextOverflow: z.boolean().default(true),
  // Loop-quality gates. They are about HOW the run went, not what it left behind,
  // which is the one thing every other assertion here is blind to: a run that took
  // forty wasted turns, never checked its own work, or kept retrying a call that
  // could not work produced exactly the same files as one that did the job well.
  //
  // Cap on the agent's iterations. Enforced only when enforceMaxIterations is on, so
  // an existing case keeps its declared budget as documentation until someone decides
  // it is a real limit for that case.
  enforceMaxIterations: z.boolean().default(false),
  // The agent itself had to run the build or the tests. The sharpest single difference
  // between a loop that verifies and one that hands back untested output.
  selfVerified: z.boolean().default(false),
  // Loop markers that must not appear even on a run that delivered. Without this a
  // guardrail can fire, the run can recover, and the incident vanishes behind a PASS.
  forbiddenMarkers: z.array(z.string()).default([]),
  // Per-tool ceilings, e.g. {"read_file": 8}. The tool-order check is a subsequence
  // test and therefore blind to an agent calling the same tool twelve times.
  toolBudget: z.record(z.string(), z.number().int().positive()).default({}),
  // The same tool with the same arguments twice in a row is an agent stuck in place.
  noImmediateRepeat: z.boolean().default(false),
});

export const CaseReviewSchema = z.object({
  description: z.string(),
  extraCriteria: z.array(CriterionSchema).default([]),
});

export const CatalogCaseSchema = z
  .object({
    id: z.string().regex(/^[a-z0-9_-]+$/),
    title: z.string(),
    category: CaseCategory,
    tier: CaseTier,
    mode: CaseMode,
    maxIterations: z.number().int().positive().default(40),
    // The normalized deliverable filename used in the fixture and needles.
    // Required for AGENT (the run must produce a file); absent for PLAN/CHAT.
    deliverable: z.string().optional(),
    // The original {{MODEL_ID}} filename pattern from the corpus, kept for
    // documentation only (the sandbox uses the normalized `deliverable`).
    originalFilePattern: z.string().optional(),
    fixture: z.string(),
    assert: CaseAssertSchema,
    judge: z.object({ criteria: z.array(z.string()).min(1) }),
    review: CaseReviewSchema,
  })
  .refine((c) => c.mode !== "AGENT" || !!c.deliverable, {
    message: "AGENT cases must declare a deliverable file",
    path: ["deliverable"],
  });

export type CaseNeedle = z.infer<typeof CaseNeedleSchema>;
export type CaseAssert = z.infer<typeof CaseAssertSchema>;
export type CaseReview = z.infer<typeof CaseReviewSchema>;
export type CatalogCase = z.infer<typeof CatalogCaseSchema>;
