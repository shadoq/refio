import { useMemo } from "react";
import { Table, Tag, Typography } from "antd";
import type { ColumnsType } from "antd/es/table";
import { useNavigate } from "react-router-dom";
import { compareLeaderboardRows, leaderboard, type LeaderboardRow } from "@/lib/stats";
import { applyFilters, useFilters } from "@/store/filters";
import { COMPARE_SELECT_PARAM } from "@/store/compareSelection";
import { filterStabilityEntries, modelStability } from "@/lib/stabilityView";
import { useTasks } from "@/data/queries";
import { useResults } from "@/data/queries";
import {
  formatDuration,
  formatCost,
  formatScore,
  formatTokensPerSecond,
} from "@/lib/format";

const { Text } = Typography;

export function LeaderboardTable() {
  const navigate = useNavigate();
  const filters = useFilters();
  const { data: tasksData, isLoading: tasksLoading } = useTasks();
  const { data: resultsData, isLoading: resultsLoading } = useResults();

  const rows = useMemo(() => {
    if (!tasksData || !resultsData) return [];
    const filtered = applyFilters(resultsData.results, filters);
    return leaderboard(filtered, resultsData, tasksData);
  }, [tasksData, resultsData, filters]);

  // Same overall stability as the Stability page, per leaderboard row (model, env, harness).
  const stabilityByRow = useMemo(() => {
    const out = new Map<string, number>();
    if (!tasksData || !resultsData) return out;
    const hidden = new Set(tasksData.tasks.filter((t) => t.hidden === true).map((t) => t.id));
    const entries = filterStabilityEntries(resultsData.stability, filters, hidden);
    for (const row of rows) {
      const own = entries.filter(
        (e) => e.environmentId === row.environmentId && e.harnessId === row.harnessId,
      );
      const s = modelStability(own, row.modelId);
      if (s) out.set(rowKey(row), s.overall);
    }
    return out;
  }, [tasksData, resultsData, filters, rows]);

  const showHarness = new Set(rows.map((r) => r.harnessId)).size > 1;

  const columns: ColumnsType<LeaderboardRow> = [
    {
      title: "Rank",
      key: "rank",
      width: 72,
      render: (_: unknown, _row: LeaderboardRow, index: number) => (
        <span className="leaderboard-rank">{index + 1}</span>
      ),
    },
    {
      title: "Model",
      key: "model",
      render: (_: unknown, row: LeaderboardRow) => (
        <Text strong className="model-name">
          {row.model.name}
        </Text>
      ),
      sorter: (a: LeaderboardRow, b: LeaderboardRow) =>
        a.model.name.localeCompare(b.model.name),
    },
    {
      title: "Environment",
      key: "env",
      render: (_: unknown, row: LeaderboardRow) => (
        <Tag color={row.environment.type === "cloud" ? "blue" : "green"}>
          {row.environment.name}
        </Tag>
      ),
    },
    // Only worth a column once the data actually holds more than one track; with the
    // Refio track alone it would be a column of identical tags.
    ...(showHarness
      ? [
          {
            title: "Harness",
            key: "harness",
            render: (_: unknown, row: LeaderboardRow) => (
              <Tag color={row.harness.kind === "refio" ? "geekblue" : "orange"}>
                {row.harness.name}
              </Tag>
            ),
          } as ColumnsType<LeaderboardRow>[number],
        ]
      : []),
    {
      title: "Tasks",
      dataIndex: "tasksEvaluated",
      key: "tasks",
      width: 78,
      sorter: (a: LeaderboardRow, b: LeaderboardRow) =>
        a.tasksEvaluated - b.tasksEvaluated,
    },
    {
      title: "Attempts",
      dataIndex: "attemptCount",
      key: "attempts",
      width: 96,
    },
    {
      title: "Avg Score",
      key: "avgScore",
      width: 120,
      defaultSortOrder: "descend",
      sorter: (a: LeaderboardRow, b: LeaderboardRow) => compareLeaderboardRows(b, a),
      render: (_: unknown, row: LeaderboardRow) => (
        <Text strong className="score-pill" style={{ color: scoreColor(row.avgScore) }}>
          {formatScore(row.avgScore)}
        </Text>
      ),
    },
    {
      title: "Judge Score",
      key: "judgeScore",
      width: 128,
      sorter: (a: LeaderboardRow, b: LeaderboardRow) =>
        (a.judgeAvgScore ?? -1) - (b.judgeAvgScore ?? -1),
      render: (_: unknown, row: LeaderboardRow) =>
        row.judgeAvgScore == null ? (
          <Text type="secondary">-</Text>
        ) : (
          <span>
            <Text strong style={{ color: scoreColor(row.judgeAvgScore) }}>
              {formatScore(row.judgeAvgScore)}
            </Text>
            <Text type="secondary" style={{ fontSize: 11, marginLeft: 6 }}>
              {row.judgedAttempts}/{row.attemptCount}
            </Text>
          </span>
        ),
    },
    {
      title: "Pass Rate",
      key: "passRate",
      width: 110,
      sorter: (a: LeaderboardRow, b: LeaderboardRow) => a.passRate - b.passRate,
      render: (_: unknown, row: LeaderboardRow) => formatScore(row.passRate),
    },
    {
      title: "First-shot",
      key: "firstShot",
      width: 122,
      sorter: (a: LeaderboardRow, b: LeaderboardRow) =>
        (a.firstShotScore ?? -1) - (b.firstShotScore ?? -1),
      render: (_: unknown, row: LeaderboardRow) => (
        <span>
          {formatNullableScore(row.firstShotScore)}
          {row.firstShotSuccess != null && (
            <Tag
              color={row.firstShotSuccess ? "green" : "red"}
              style={{ marginLeft: 6 }}
            >
              {row.firstShotSuccess ? "OK" : "Fix"}
            </Tag>
          )}
        </span>
      ),
    },
    {
      title: "Reliability",
      key: "reliability",
      width: 122,
      sorter: (a: LeaderboardRow, b: LeaderboardRow) =>
        (a.reliabilityScore ?? -1) - (b.reliabilityScore ?? -1),
      render: (_: unknown, row: LeaderboardRow) =>
        formatNullableScore(row.reliabilityScore),
    },
    {
      title: "Avg Stability",
      key: "stability",
      width: 130,
      sorter: (a: LeaderboardRow, b: LeaderboardRow) =>
        (stabilityByRow.get(rowKey(a)) ?? -1) - (stabilityByRow.get(rowKey(b)) ?? -1),
      render: (_: unknown, row: LeaderboardRow) =>
        formatNullableScore(stabilityByRow.get(rowKey(row)) ?? null),
    },
    {
      title: "Local Viability",
      key: "localViability",
      width: 140,
      sorter: (a: LeaderboardRow, b: LeaderboardRow) =>
        (a.localViabilityScore ?? -1) - (b.localViabilityScore ?? -1),
      render: (_: unknown, row: LeaderboardRow) =>
        row.environment.type === "local"
          ? formatNullableScore(row.localViabilityScore)
          : "cloud baseline",
    },
    {
      title: "Avg Duration",
      key: "duration",
      width: 130,
      sorter: (a: LeaderboardRow, b: LeaderboardRow) =>
        (a.avgDurationMs ?? Infinity) - (b.avgDurationMs ?? Infinity),
      render: (_: unknown, row: LeaderboardRow) => formatDuration(row.avgDurationMs),
    },
    {
      title: "LLM Est.",
      key: "estimatedLlm",
      width: 118,
      sorter: (a: LeaderboardRow, b: LeaderboardRow) =>
        (a.avgEstimatedLlmMs ?? Infinity) - (b.avgEstimatedLlmMs ?? Infinity),
      render: (_: unknown, row: LeaderboardRow) =>
        formatDuration(row.avgEstimatedLlmMs),
    },
    {
      title: "Token Speed",
      key: "tokenSpeed",
      width: 132,
      render: (_: unknown, row: LeaderboardRow) => renderTokenSpeed(row),
    },
    {
      title: "Avg Cost",
      key: "avgCost",
      width: 120,
      sorter: (a: LeaderboardRow, b: LeaderboardRow) =>
        (a.avgCostUsd ?? Infinity) - (b.avgCostUsd ?? Infinity),
      render: (_: unknown, row: LeaderboardRow) => formatCost(row.avgCostUsd),
    },
  ];

  return (
    <Table<LeaderboardRow>
      columns={columns}
      dataSource={rows}
      rowKey={rowKey}
      loading={tasksLoading || resultsLoading}
      pagination={false}
      size="middle"
      scroll={{ x: 1518 }}
      onRow={(row, index) => ({
        onClick: () =>
          navigate(`/compare?${COMPARE_SELECT_PARAM}=${encodeURIComponent(row.modelId)}`),
        className: index === 0 ? "leaderboard-row-top" : "",
        style: { cursor: "pointer" },
      })}
    />
  );
}

function rowKey(row: LeaderboardRow): string {
  return `${row.modelId}::${row.environmentId}::${row.harnessId}`;
}

function scoreColor(score: number): string {
  if (score >= 0.8) return "var(--accent)";
  if (score >= 0.6) return "#ffd166";
  return "#ff4d4f";
}

function formatNullableScore(score: number | null): string {
  if (score == null) return "-";
  return formatScore(score);
}

function renderTokenSpeed(row: LeaderboardRow) {
  if (row.avgPrefillTokensPerSecond == null && row.avgDecodeTokensPerSecond == null) {
    return "-";
  }

  return (
    <span style={{ whiteSpace: "nowrap" }}>
      in {formatTokensPerSecond(row.avgPrefillTokensPerSecond)}
      <br />
      out {formatTokensPerSecond(row.avgDecodeTokensPerSecond)}
    </span>
  );
}
