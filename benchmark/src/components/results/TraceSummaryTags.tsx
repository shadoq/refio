import { Space, Tag, Tooltip } from "antd";
import type { TraceSummary } from "@/schema/results";

// How a run ended, in a word and a colour. A run killed by its cap reads the same as
// one that failed at once unless this is on the row.
const END_REASON_COLOR: Record<TraceSummary["endReason"], string> = {
  completed: "green",
  failed: "red",
  incomplete: "orange",
  cancelled: "default",
  limit: "volcano",
  unknown: "default",
};

// The one-line shape of a run: how many turns it took, how many tools it called, how
// that split between reading, writing and running commands, how much of that work it
// had already done before, whether it survived its own failures and whether it checked
// its own output.
export function TraceSummaryTags({ trace }: { trace: TraceSummary }) {
  const wastedRate = trace.toolCalls > 0 ? trace.duplicateCalls / trace.toolCalls : 0;
  return (
    <Space wrap size={4}>
      <Tag color={END_REASON_COLOR[trace.endReason]}>{trace.endReason}</Tag>
      <Tag>{trace.turns} turns</Tag>
      <Tag>{trace.toolCalls} tools</Tag>
      <Tooltip title="reads / writes / shell commands">
        <Tag color="blue">
          {trace.reads}R / {trace.writes}W / {trace.shellRuns}sh
        </Tag>
      </Tooltip>
      <Tooltip title="the model itself ran a build or a test">
        <Tag color={trace.selfVerified ? "green" : "default"}>
          self-check {trace.selfVerified ? "yes" : "no"}
        </Tag>
      </Tooltip>
      {trace.firstWriteAtCall !== null && <Tag>first write @{trace.firstWriteAtCall}</Tag>}
      {trace.duplicateCalls > 0 && (
        <Tooltip title="calls that repeated one the agent had already made">
          <Tag color={wastedRate > 0.3 ? "red" : "orange"}>
            {trace.duplicateCalls} repeated ({Math.round(wastedRate * 100)}%)
          </Tag>
        </Tooltip>
      )}
      {trace.repeatedFailedCallStreak > 0 && (
        <Tooltip title="longest run of identical calls that kept failing">
          <Tag color="red">stuck x{trace.repeatedFailedCallStreak}</Tag>
        </Tooltip>
      )}
      {trace.toolErrors > 0 && (
        <Tooltip title="the tool call itself was rejected or errored">
          <Tag color="red">{trace.toolErrors} tool errors</Tag>
        </Tooltip>
      )}
      {trace.nonZeroExits > 0 && (
        <Tooltip title="shell commands that returned non-zero, which is not the same as a failed call">
          <Tag>{trace.nonZeroExits} non-zero exits</Tag>
        </Tooltip>
      )}
      {trace.recoveredFromError !== null && (
        <Tooltip title="did the agent do anything useful after its last failing call">
          <Tag color={trace.recoveredFromError ? "green" : "red"}>
            {trace.recoveredFromError ? "recovered" : "gave up after failure"}
          </Tag>
        </Tooltip>
      )}
      {trace.loop?.contextOverflow && (
        <Tooltip title="the prompt did not fit the model's window">
          <Tag color="volcano">context overflow</Tag>
        </Tooltip>
      )}
      {trace.loop?.failureMarker && (
        <Tooltip title="the loop's own name for how this run went wrong">
          <Tag color="volcano">{trace.loop.failureMarker}</Tag>
        </Tooltip>
      )}
    </Space>
  );
}
