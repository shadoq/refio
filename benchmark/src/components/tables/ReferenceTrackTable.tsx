import { useMemo } from "react";
import { Table, Tag, Typography, Empty, Space, Tooltip } from "antd";
import type { ColumnsType } from "antd/es/table";
import { compareLeaderboardRows, leaderboard, type LeaderboardRow } from "@/lib/stats";
import { applyFilters, useFilters } from "@/store/filters";
import { useTasks, useResults } from "@/data/queries";
import { formatDuration, formatScore } from "@/lib/format";

const { Text } = Typography;

// The reference track: the same tasks run by an external coding agent (Claude Code,
// Codex) on its own model, with its own planning, tools and self-checking. It answers
// a different question from the main table - "how far is a local model from what people
// already have on their desk" - so it is shown apart and never ranked against it.
//
// It ignores the global harness filter on purpose: this section IS the external track,
// and the other facets (model, task, environment, date) still narrow it.
export function ReferenceTrackTable() {
  const filters = useFilters();
  const { data: tasksData } = useTasks();
  const { data: resultsData } = useResults();

  const externalHarnesses = useMemo(
    () => (resultsData?.harnesses ?? []).filter((h) => h.kind === "external"),
    [resultsData],
  );

  const rows = useMemo(() => {
    if (!tasksData || !resultsData || externalHarnesses.length === 0) return [];
    const externalIds = externalHarnesses.map((h) => h.id);
    const filtered = applyFilters(resultsData.results, { ...filters, harnessIds: externalIds });
    return leaderboard(filtered, resultsData, tasksData).sort(compareLeaderboardRows);
  }, [tasksData, resultsData, filters, externalHarnesses]);

  if (externalHarnesses.length === 0) {
    return (
      <Empty description="No external agent runs yet. Import one with run-task --harness claude-code." />
    );
  }

  const columns: ColumnsType<LeaderboardRow> = [
    {
      title: "Agent",
      key: "harness",
      render: (_: unknown, row: LeaderboardRow) => (
        <Space direction="vertical" size={0}>
          <Tag color="orange">{row.harness.name}</Tag>
          {row.harness.conditions && (
            <Tooltip title={row.harness.conditions}>
              <Text type="secondary" style={{ fontSize: 11 }}>
                run conditions
              </Text>
            </Tooltip>
          )}
        </Space>
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
    },
    {
      title: "Tasks",
      key: "tasks",
      width: 90,
      render: (_: unknown, row: LeaderboardRow) => row.tasksEvaluated,
    },
    {
      title: "Attempts",
      key: "attempts",
      width: 100,
      render: (_: unknown, row: LeaderboardRow) => row.attemptCount,
    },
    {
      title: "Avg score",
      key: "avgScore",
      width: 110,
      render: (_: unknown, row: LeaderboardRow) => formatScore(row.avgScore),
      sorter: (a: LeaderboardRow, b: LeaderboardRow) => a.avgScore - b.avgScore,
      defaultSortOrder: "descend",
    },
    {
      title: "Judges",
      key: "judgeScore",
      width: 110,
      render: (_: unknown, row: LeaderboardRow) =>
        row.judgeAvgScore == null ? (
          <Text type="secondary">-</Text>
        ) : (
          formatScore(row.judgeAvgScore)
        ),
    },
    {
      title: "First shot",
      key: "firstShot",
      width: 110,
      render: (_: unknown, row: LeaderboardRow) =>
        row.firstShotScore == null ? (
          <Text type="secondary">-</Text>
        ) : (
          formatScore(row.firstShotScore)
        ),
    },
    {
      title: "Avg duration",
      key: "duration",
      width: 130,
      render: (_: unknown, row: LeaderboardRow) => formatDuration(row.avgDurationMs ?? undefined),
    },
  ];

  return (
    <Table
      dataSource={rows}
      columns={columns}
      rowKey={(row) => `${row.modelId}::${row.environmentId}::${row.harnessId}`}
      size="small"
      pagination={false}
    />
  );
}
