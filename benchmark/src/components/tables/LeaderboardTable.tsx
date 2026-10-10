import { useMemo, useState } from "react";
import { Segmented, Table, Tag, Typography } from "antd";
import type { ColumnsType } from "antd/es/table";
import { useNavigate } from "react-router-dom";
import {
  compareLeaderboardRows,
  leaderboard,
  leaderboardRowKey,
  localRowsOnly,
  withRefioMode,
  type LeaderboardRow,
} from "@/lib/stats";
import { applyFilters, useFilters } from "@/store/filters";
import { useRefioMode } from "@/store/refioMode";
import { COMPARE_SELECT_PARAM } from "@/store/compareSelection";
import { useTasks } from "@/data/queries";
import { useResults } from "@/data/queries";
import { useT, type Translate } from "@/i18n/LanguageProvider";
import {
  formatDuration,
  formatCost,
  formatScore,
  formatTokensPerSecond,
  scoreColor,
  formatModelSpec,
} from "@/lib/format";

const { Text } = Typography;

// The table opens on the three quality scores; the rest sits behind a view switch so
// the first look is not a wall of numbers.
type ColumnView = "scores" | "reliability" | "speedCost" | "all";

const VIEW_COLUMNS: Record<Exclude<ColumnView, "all">, string[]> = {
  scores: ["refioScore", "avgScore", "judgeScore"],
  reliability: ["refioScore", "passRate", "firstShot", "reliability", "stability"],
  speedCost: ["refioScore", "localViability", "duration", "estimatedLlm", "tokenSpeed", "avgCost"],
};

// Identity columns are shown in every view.
const ALWAYS_SHOWN = new Set(["rank", "model", "env", "harness"]);

const VIEW_STORAGE_KEY = "benchmark-leaderboard-view";

function initialView(): ColumnView {
  try {
    const saved = localStorage.getItem(VIEW_STORAGE_KEY);
    if (saved === "reliability" || saved === "speedCost" || saved === "all") return saved;
  } catch {
    // Storage blocked: fall back to the default view.
  }
  return "scores";
}

export function LeaderboardTable() {
  const navigate = useNavigate();
  const filters = useFilters();
  const t = useT();
  const { data: tasksData, isLoading: tasksLoading } = useTasks();
  const { data: resultsData, isLoading: resultsLoading } = useResults();

  const refioMode = useRefioMode((s) => s.mode);
  const rows = useMemo(() => {
    if (!tasksData || !resultsData) return [];
    const filtered = applyFilters(resultsData.results, { ...filters, localOnly: false });
    const rows = localRowsOnly(leaderboard(filtered, resultsData, tasksData), filters.localOnly);
    return withRefioMode(rows, refioMode);
  }, [tasksData, resultsData, filters, refioMode]);

  const [view, setView] = useState<ColumnView>(initialView);
  const changeView = (next: ColumnView) => {
    setView(next);
    try {
      localStorage.setItem(VIEW_STORAGE_KEY, next);
    } catch {
      // Storage blocked: the choice holds for this visit.
    }
  };

  const showHarness = new Set(rows.map((r) => r.harnessId)).size > 1;

  const allColumns: ColumnsType<LeaderboardRow> = [
    {
      title: t("leaderboard.colRank"),
      key: "rank",
      width: 72,
      render: (_: unknown, _row: LeaderboardRow, index: number) => (
        <span className="leaderboard-rank">{index + 1}</span>
      ),
    },
    {
      title: t("leaderboard.colModel"),
      key: "model",
      render: (_: unknown, row: LeaderboardRow) => {
        const spec = formatModelSpec(row.model);
        return (
          <>
            <Text strong className="model-name">
              {row.model.name}
            </Text>
            {spec && (
              <Text type="secondary" style={{ display: "block", fontSize: 11 }}>
                {spec}
              </Text>
            )}
          </>
        );
      },
      sorter: (a: LeaderboardRow, b: LeaderboardRow) =>
        a.model.name.localeCompare(b.model.name),
    },
    {
      title: t("leaderboard.colEnvironment"),
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
            title: t("leaderboard.colHarness"),
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
      title: t("leaderboard.colTasks"),
      dataIndex: "tasksEvaluated",
      key: "tasks",
      width: 78,
      sorter: (a: LeaderboardRow, b: LeaderboardRow) =>
        a.tasksEvaluated - b.tasksEvaluated,
    },
    {
      title: t("leaderboard.colAttempts"),
      dataIndex: "attemptCount",
      key: "attempts",
      width: 96,
    },
    {
      title: t("leaderboard.colRefio"),
      key: "refioScore",
      width: 130,
      defaultSortOrder: "descend",
      sorter: (a: LeaderboardRow, b: LeaderboardRow) =>
        (a.refioScore ?? -1) - (b.refioScore ?? -1),
      render: (_: unknown, row: LeaderboardRow) => {
        const value = row.refioScore;
        return value == null ? (
          <Text type="secondary">-</Text>
        ) : (
          <Text strong style={{ color: scoreColor(value) }}>
            {formatScore(value)}
          </Text>
        );
      },
    },
    {
      title: t("leaderboard.colAvgScore"),
      key: "avgScore",
      width: 120,
      sorter: (a: LeaderboardRow, b: LeaderboardRow) => compareLeaderboardRows(b, a),
      render: (_: unknown, row: LeaderboardRow) => (
        <Text strong className="score-pill" style={{ color: scoreColor(row.avgScore) }}>
          {formatScore(row.avgScore)}
        </Text>
      ),
    },
    {
      title: t("leaderboard.colJudge"),
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
      title: t("leaderboard.colPassRate"),
      key: "passRate",
      width: 110,
      sorter: (a: LeaderboardRow, b: LeaderboardRow) => a.passRate - b.passRate,
      render: (_: unknown, row: LeaderboardRow) => formatScore(row.passRate),
    },
    {
      title: t("leaderboard.colFirstShot"),
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
              {row.firstShotSuccess ? t("leaderboard.firstShotOk") : t("leaderboard.firstShotFix")}
            </Tag>
          )}
        </span>
      ),
    },
    {
      title: t("leaderboard.colReliability"),
      key: "reliability",
      width: 122,
      sorter: (a: LeaderboardRow, b: LeaderboardRow) =>
        (a.reliabilityScore ?? -1) - (b.reliabilityScore ?? -1),
      render: (_: unknown, row: LeaderboardRow) =>
        formatNullableScore(row.reliabilityScore),
    },
    {
      title: t("leaderboard.colStability"),
      key: "stability",
      width: 130,
      sorter: (a: LeaderboardRow, b: LeaderboardRow) =>
        (a.stabilityScore ?? -1) - (b.stabilityScore ?? -1),
      render: (_: unknown, row: LeaderboardRow) => formatNullableScore(row.stabilityScore),
    },
    {
      title: t("leaderboard.colLocalViability"),
      key: "localViability",
      width: 140,
      sorter: (a: LeaderboardRow, b: LeaderboardRow) =>
        (a.localViabilityScore ?? -1) - (b.localViabilityScore ?? -1),
      render: (_: unknown, row: LeaderboardRow) =>
        row.environment.type === "local"
          ? formatNullableScore(row.localViabilityScore)
          : t("leaderboard.cloudBaseline"),
    },
    {
      title: t("leaderboard.colDuration"),
      key: "duration",
      width: 130,
      sorter: (a: LeaderboardRow, b: LeaderboardRow) =>
        (a.avgDurationMs ?? Infinity) - (b.avgDurationMs ?? Infinity),
      render: (_: unknown, row: LeaderboardRow) => formatDuration(row.avgDurationMs),
    },
    {
      title: t("leaderboard.colLlmEst"),
      key: "estimatedLlm",
      width: 118,
      sorter: (a: LeaderboardRow, b: LeaderboardRow) =>
        (a.avgEstimatedLlmMs ?? Infinity) - (b.avgEstimatedLlmMs ?? Infinity),
      render: (_: unknown, row: LeaderboardRow) =>
        formatDuration(row.avgEstimatedLlmMs),
    },
    {
      title: t("leaderboard.colTokenSpeed"),
      key: "tokenSpeed",
      width: 132,
      render: (_: unknown, row: LeaderboardRow) => renderTokenSpeed(row, t),
    },
    {
      title: t("leaderboard.colCost"),
      key: "avgCost",
      width: 120,
      sorter: (a: LeaderboardRow, b: LeaderboardRow) =>
        (a.avgCostUsd ?? Infinity) - (b.avgCostUsd ?? Infinity),
      render: (_: unknown, row: LeaderboardRow) => formatCost(row.avgCostUsd),
    },
  ];

  const columns = allColumns.filter((column) => {
    const key = String(column.key);
    return view === "all" || ALWAYS_SHOWN.has(key) || VIEW_COLUMNS[view].includes(key);
  });

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
      <div className="leaderboard-view-bar">
        <Text type="secondary">{t("leaderboard.viewLabel")}</Text>
        <Segmented
          className="leaderboard-view-switch"
          value={view}
          onChange={(next) => changeView(next as ColumnView)}
          options={[
            { label: t("leaderboard.viewScores"), value: "scores" },
            { label: t("leaderboard.viewReliability"), value: "reliability" },
            { label: t("leaderboard.viewSpeedCost"), value: "speedCost" },
            { label: t("leaderboard.viewAll"), value: "all" },
          ]}
        />
      </div>
      <Table<LeaderboardRow>
        columns={columns}
        dataSource={rows}
        rowKey={rowKey}
        loading={tasksLoading || resultsLoading}
        pagination={false}
        size="middle"
        scroll={{ x: "max-content" }}
        onRow={(row, index) => ({
          onClick: () =>
            navigate(`/compare?${COMPARE_SELECT_PARAM}=${encodeURIComponent(row.modelId)}`),
          className: index === 0 ? "leaderboard-row-top" : "",
          style: { cursor: "pointer" },
        })}
      />
    </div>
  );
}

function rowKey(row: LeaderboardRow): string {
  return leaderboardRowKey(row);
}

function formatNullableScore(score: number | null): string {
  if (score == null) return "-";
  return formatScore(score);
}

function renderTokenSpeed(row: LeaderboardRow, t: Translate) {
  if (row.avgPrefillTokensPerSecond == null && row.avgDecodeTokensPerSecond == null) {
    return "-";
  }

  return (
    <span style={{ whiteSpace: "nowrap" }}>
      {t("leaderboard.tokenIn")} {formatTokensPerSecond(row.avgPrefillTokensPerSecond)}
      <br />
      {t("leaderboard.tokenOut")} {formatTokensPerSecond(row.avgDecodeTokensPerSecond)}
    </span>
  );
}
