import { Space, Tag, Tooltip } from "antd";
import { useT } from "@/i18n/LanguageProvider";
import type { MessageKey } from "@/i18n/messages";
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

const END_REASON_LABEL: Record<TraceSummary["endReason"], MessageKey> = {
  completed: "resultView.endCompleted",
  failed: "resultView.endFailed",
  incomplete: "resultView.endIncomplete",
  cancelled: "resultView.endCancelled",
  limit: "resultView.endLimit",
  unknown: "resultView.endUnknown",
};

// The one-line shape of a run: how many turns it took, how many tools it called, how
// that split between reading, writing and running commands, how much of that work it
// had already done before, whether it survived its own failures and whether it checked
// its own output.
export function TraceSummaryTags({ trace }: { trace: TraceSummary }) {
  const t = useT();
  const wastedRate = trace.toolCalls > 0 ? trace.duplicateCalls / trace.toolCalls : 0;
  const endLabel = END_REASON_LABEL[trace.endReason];
  return (
    <Space wrap size={4}>
      <Tag color={END_REASON_COLOR[trace.endReason]}>
        {endLabel ? t(endLabel) : trace.endReason}
      </Tag>
      <Tag>{t("resultView.turns", { count: trace.turns })}</Tag>
      <Tag>{t("resultView.tools", { count: trace.toolCalls })}</Tag>
      <Tooltip title={t("resultView.callSplitTooltip")}>
        <Tag color="blue">
          {t("resultView.callSplit", {
            reads: trace.reads,
            writes: trace.writes,
            shell: trace.shellRuns,
          })}
        </Tag>
      </Tooltip>
      <Tooltip title={t("resultView.selfCheckTooltip")}>
        <Tag color={trace.selfVerified ? "green" : "default"}>
          {t(trace.selfVerified ? "resultView.selfCheckYes" : "resultView.selfCheckNo")}
        </Tag>
      </Tooltip>
      {trace.firstWriteAtCall !== null && (
        <Tag>{t("resultView.firstWrite", { n: trace.firstWriteAtCall })}</Tag>
      )}
      {trace.duplicateCalls > 0 && (
        <Tooltip title={t("resultView.repeatedTooltip")}>
          <Tag color={wastedRate > 0.3 ? "red" : "orange"}>
            {t("resultView.repeated", {
              count: trace.duplicateCalls,
              percent: Math.round(wastedRate * 100),
            })}
          </Tag>
        </Tooltip>
      )}
      {trace.repeatedFailedCallStreak > 0 && (
        <Tooltip title={t("resultView.stuckTooltip")}>
          <Tag color="red">{t("resultView.stuck", { count: trace.repeatedFailedCallStreak })}</Tag>
        </Tooltip>
      )}
      {trace.toolErrors > 0 && (
        <Tooltip title={t("resultView.toolErrorsTooltip")}>
          <Tag color="red">{t("resultView.toolErrors", { count: trace.toolErrors })}</Tag>
        </Tooltip>
      )}
      {trace.nonZeroExits > 0 && (
        <Tooltip title={t("resultView.nonZeroTooltip")}>
          <Tag>{t("resultView.nonZeroExits", { count: trace.nonZeroExits })}</Tag>
        </Tooltip>
      )}
      {trace.recoveredFromError !== null && (
        <Tooltip title={t("resultView.recoveredTooltip")}>
          <Tag color={trace.recoveredFromError ? "green" : "red"}>
            {t(trace.recoveredFromError ? "resultView.recovered" : "resultView.gaveUp")}
          </Tag>
        </Tooltip>
      )}
      {trace.loop?.contextOverflow && (
        <Tooltip title={t("resultView.contextOverflowTooltip")}>
          <Tag color="volcano">{t("resultView.contextOverflow")}</Tag>
        </Tooltip>
      )}
      {trace.loop?.failureMarker && (
        <Tooltip title={t("resultView.failureMarkerTooltip")}>
          <Tag color="volcano">{trace.loop.failureMarker}</Tag>
        </Tooltip>
      )}
    </Space>
  );
}
