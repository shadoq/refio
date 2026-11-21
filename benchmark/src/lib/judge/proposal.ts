// Pre-fills the review queue with a score per criterion, so the reviewer accepts
// or corrects a proposal instead of scoring every run from scratch.
//
// Strong judges rate systematically differently from the reviewer (on "look" they
// sit almost a point higher), and a pre-filled value anchors the reviewer's choice.
// Proposing the raw judge mean would therefore drift the leaderboard upwards
// unnoticed. Instead the judge mean is translated into the score the reviewer has
// historically given for that judge mean, learned from promoted results that carry
// both a human score and judge scores.
import { snapToScale } from "./scoring";

// Not a strong judge: a plain measurement written by import-runs. Used as a
// fallback for criteria the strong judges do not score (e.g. agent_logic).
const DETERMINISTIC_JUDGE = "e2e-deterministic";

// Fewer past reviews than this for a given judge mean is too thin to trust; the
// proposal then falls back to the snapped judge mean.
export const MIN_CALIBRATION_SAMPLES = 10;

// Judges this many points apart on one criterion are flagged as disputed.
const DISPUTE_SPREAD = 2;

interface JudgeSetLike {
  judgeId: string;
  scores: ReadonlyArray<{ criterionId: string; value: number }>;
  error?: string | null;
}

interface ReviewedLike {
  scores: ReadonlyArray<{ criterionId: string; value: number }>;
  judgeScores?: ReadonlyArray<JudgeSetLike>;
}

interface CriterionLike {
  id: string;
  scale: { values: number[] };
}

// criterionId -> (judge mean bucket -> reviewer's median score for that bucket)
export type Calibration = Map<string, Map<number, number>>;

export interface Proposal {
  criterionId: string;
  value?: number;
  source?: "judges" | "deterministic";
  // Mean of the strong judges' scores, before calibration.
  judgeMean?: number;
  calibrated?: boolean;
  disputed?: boolean;
}

// Two judges can average to a half point; keep that resolution in the bucket key.
function bucket(mean: number): number {
  return Math.round(mean * 2) / 2;
}

// Lower middle on an even count, so a split never rounds the proposal upwards.
function lowerMedian(values: number[]): number {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.floor((sorted.length - 1) / 2)];
}

function strongJudgeValues(judgeSets: ReadonlyArray<JudgeSetLike>, criterionId: string): number[] {
  const out: number[] = [];
  for (const set of judgeSets) {
    if (set.error != null || set.judgeId === DETERMINISTIC_JUDGE) continue;
    const s = set.scores.find((x) => x.criterionId === criterionId);
    if (s) out.push(s.value);
  }
  return out;
}

function mean(values: number[]): number {
  return values.reduce((a, b) => a + b, 0) / values.length;
}

export function buildCalibration(results: ReadonlyArray<ReviewedLike>): Calibration {
  const samples = new Map<string, Map<number, number[]>>();
  for (const r of results) {
    for (const human of r.scores) {
      const values = strongJudgeValues(r.judgeScores ?? [], human.criterionId);
      if (values.length === 0) continue;
      const byBucket = samples.get(human.criterionId) ?? new Map<number, number[]>();
      const key = bucket(mean(values));
      byBucket.set(key, [...(byBucket.get(key) ?? []), human.value]);
      samples.set(human.criterionId, byBucket);
    }
  }

  const calibration: Calibration = new Map();
  for (const [criterionId, byBucket] of samples) {
    const table = new Map<number, number>();
    for (const [key, humans] of byBucket) {
      if (humans.length >= MIN_CALIBRATION_SAMPLES) table.set(key, lowerMedian(humans));
    }
    if (table.size > 0) calibration.set(criterionId, table);
  }
  return calibration;
}

export function proposeScores(
  entry: { judgeScores: ReadonlyArray<JudgeSetLike> },
  criteria: ReadonlyArray<CriterionLike>,
  calibration: Calibration,
): Proposal[] {
  return criteria.map((c) => {
    const values = strongJudgeValues(entry.judgeScores, c.id);
    if (values.length > 0) {
      const judgeMean = mean(values);
      const learned = calibration.get(c.id)?.get(bucket(judgeMean));
      return {
        criterionId: c.id,
        value: learned ?? snapToScale(judgeMean, c.scale.values),
        source: "judges",
        judgeMean,
        calibrated: learned !== undefined,
        disputed: Math.max(...values) - Math.min(...values) >= DISPUTE_SPREAD,
      };
    }

    const deterministic = entry.judgeScores
      .find((s) => s.judgeId === DETERMINISTIC_JUDGE && s.error == null)
      ?.scores.find((s) => s.criterionId === c.id);
    if (deterministic) {
      return { criterionId: c.id, value: deterministic.value, source: "deterministic" };
    }
    return { criterionId: c.id };
  });
}
