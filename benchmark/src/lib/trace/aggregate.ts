// Average trace metrics over a set of results: how many turns and tool calls an agent
// typically needs, and how often the model checked its own work. Only results that
// actually carry a trace are counted - a run recorded before traces existed is
// unmeasured, not a zero.
import type { Result } from "../../schema/results";

export interface TraceAggregate {
  withTrace: number;
  avgTurns: number | null;
  avgToolCalls: number | null;
  avgReads: number | null;
  avgWrites: number | null;
  avgShellRuns: number | null;
  selfVerifiedRate: number | null;
  // Share of calls that repeated one the agent had already made: the cheapest reading
  // of how much of a loop's work bought nothing.
  wastedCallRate: number | null;
  // Of the runs that hit a failing tool call, how many carried on afterwards. Null when
  // no run in the set ever failed, which is not the same as none recovering.
  recoveryRate: number | null;
  // Runs that ended other than by finishing.
  unfinishedRate: number | null;
}

export function aggregateTraces(results: Array<Pick<Result, "trace">>): TraceAggregate {
  const traces = results.map((r) => r.trace).filter((t): t is NonNullable<typeof t> => t != null);
  if (traces.length === 0) {
    return {
      withTrace: 0,
      avgTurns: null,
      avgToolCalls: null,
      avgReads: null,
      avgWrites: null,
      avgShellRuns: null,
      selfVerifiedRate: null,
      wastedCallRate: null,
      recoveryRate: null,
      unfinishedRate: null,
    };
  }
  const mean = (pick: (t: NonNullable<Result["trace"]>) => number): number =>
    traces.reduce((sum, t) => sum + pick(t), 0) / traces.length;

  const totalCalls = traces.reduce((sum, t) => sum + t.toolCalls, 0);
  const withFailure = traces.filter((t) => t.recoveredFromError !== null);

  return {
    withTrace: traces.length,
    avgTurns: mean((t) => t.turns),
    avgToolCalls: mean((t) => t.toolCalls),
    avgReads: mean((t) => t.reads),
    avgWrites: mean((t) => t.writes),
    avgShellRuns: mean((t) => t.shellRuns),
    selfVerifiedRate: mean((t) => (t.selfVerified ? 1 : 0)),
    wastedCallRate:
      totalCalls === 0 ? null : traces.reduce((sum, t) => sum + t.duplicateCalls, 0) / totalCalls,
    recoveryRate:
      withFailure.length === 0
        ? null
        : withFailure.filter((t) => t.recoveredFromError === true).length / withFailure.length,
    unfinishedRate: mean((t) => (t.endReason === "completed" ? 0 : 1)),
  };
}
