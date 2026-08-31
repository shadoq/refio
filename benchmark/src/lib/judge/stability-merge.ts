// Pure merge helper shared by the judge runner. Kept free of schema/alias
// imports so it works both under Vite/vitest and plain tsx.

interface JudgeVerdict {
  judgeId: string;
}

interface StabilityLike<J extends JudgeVerdict> {
  judges: J[];
}

// A stability run may cover only a subset of judges (topping up a group whose
// other judge already answered). Merging keeps the verdicts the new run did not
// produce, so re-judging with one judge never erases another judge's work.
// Everything else - attempts, deterministic metrics, timestamp - comes from the
// new run, which is the fresher computation.
export function mergeStabilityJudges<J extends JudgeVerdict, E extends StabilityLike<J>>(
  existing: E | undefined,
  incoming: E,
): E {
  if (!existing) return incoming;
  const fresh = new Set(incoming.judges.map((j) => j.judgeId));
  const kept = existing.judges.filter((j) => !fresh.has(j.judgeId));
  return { ...incoming, judges: [...incoming.judges, ...kept] };
}

// A group is pending while any requested judge has not scored it. Keying only on
// the group's presence would hide a partial entry (one judge answered, the other
// failed) as done, so the missing verdict could never be topped up without
// --re-judge.
export function stabilityNeedsJudging<J extends JudgeVerdict, E extends StabilityLike<J>>(
  existing: E | undefined,
  judgeIds: string[],
): boolean {
  if (!existing) return true;
  const scored = new Set(existing.judges.map((j) => j.judgeId));
  return judgeIds.some((id) => !scored.has(id));
}
