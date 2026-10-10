import { useMemo, useState } from "react";
import { Card, Empty, Segmented, Space, Spin, Table, Tabs, Tag, Typography } from "antd";
import type { ColumnsType } from "antd/es/table";
import { useResults, useTasks } from "@/data/queries";
import { useT } from "@/i18n/LanguageProvider";
import { applyFilters, useFilters } from "@/store/filters";
import { formatCost, formatDuration } from "@/lib/format";
import {
  collectRuns,
  passMatrix,
  summarizeByModel,
  summarizeByTask,
  type MatrixRow,
  type ModelSummary,
  type PassCell,
  type RunSource,
  type TaskSummary,
} from "@/lib/overview";

const { Title, Text } = Typography;

type SourceChoice = "all" | "reviewed" | "queue";

const SOURCES: Record<SourceChoice, RunSource[]> = {
  all: ["reviewed", "queue"],
  reviewed: ["reviewed"],
  queue: ["queue"],
};

const ratio = (passed: number, attempts: number) => `${passed}/${attempts}`;

function cellColor(cell: PassCell): string {
  if (cell.passed === cell.attempts) return "green";
  if (cell.passed === 0) return "red";
  return "orange";
}

export default function Overview() {
  const t = useT();
  const filters = useFilters();
  const { data: tasksData, isLoading: tasksLoading } = useTasks();
  const { data: resultsData, isLoading: resultsLoading } = useResults();
  const [source, setSource] = useState<SourceChoice>("all");

  const runs = useMemo(
    () => (resultsData ? applyFilters(collectRuns(resultsData, SOURCES[source]), filters) : []),
    [resultsData, source, filters],
  );
  const models = useMemo(() => (resultsData ? summarizeByModel(runs, resultsData) : []), [runs, resultsData]);
  const matrix = useMemo(() => (resultsData ? passMatrix(runs, resultsData) : []), [runs, resultsData]);
  const taskRows = useMemo(() => (resultsData ? summarizeByTask(runs, resultsData) : []), [runs, resultsData]);

  if (tasksLoading || resultsLoading) {
    return (
      <div style={{ display: "flex", justifyContent: "center", padding: 80 }}>
        <Spin size="large" />
      </div>
    );
  }

  const taskName = (id: string) => tasksData?.tasks.find((t) => t.id === id)?.name ?? id;
  const taskIds = [...new Set(runs.map((r) => r.taskId))].sort();

  const modelColumns: ColumnsType<ModelSummary> = [
    { title: "#", key: "rank", width: 50, render: (_, __, i) => i + 1 },
    {
      title: t("overview.colModel"),
      dataIndex: "name",
      sorter: (a, b) => a.name.localeCompare(b.name),
      render: (name: string, row) => (
        <Space size={4}>
          {name}
          {row.sources.includes("queue") && <Tag>{t("overview.unreviewedTag")}</Tag>}
        </Space>
      ),
    },
    { title: t("overview.colReasoning"), dataIndex: "reasoning", render: (v: string | null) => v ?? "-" },
    {
      title: t("overview.colPassed"),
      key: "passed",
      sorter: (a, b) => a.passed / a.attempts - b.passed / b.attempts,
      render: (_, r) => ratio(r.passed, r.attempts),
    },
    {
      title: t("overview.colWorks"),
      key: "works",
      sorter: (a, b) => a.worksFull / a.attempts - b.worksFull / b.attempts,
      render: (_, r) => ratio(r.worksFull, r.attempts),
    },
    {
      title: t("overview.colAgentLogic"),
      key: "agentLogic",
      sorter: (a, b) => a.agentLogicFull / a.attempts - b.agentLogicFull / b.attempts,
      render: (_, r) => ratio(r.agentLogicFull, r.attempts),
    },
    {
      title: t("overview.colCost"),
      dataIndex: "costUsd",
      sorter: (a, b) => (a.costUsd ?? Infinity) - (b.costUsd ?? Infinity),
      render: (v: number | null) => formatCost(v),
    },
    {
      title: t("overview.colAvgTime"),
      dataIndex: "avgDurationMs",
      sorter: (a, b) => (a.avgDurationMs ?? Infinity) - (b.avgDurationMs ?? Infinity),
      render: (v: number | null) => formatDuration(v),
    },
  ];

  const matrixColumns: ColumnsType<MatrixRow> = [
    { title: t("overview.colModel"), dataIndex: "name", fixed: "left" },
    ...taskIds.map((taskId) => ({
      title: taskName(taskId),
      key: taskId,
      sorter: (a: MatrixRow, b: MatrixRow) => {
        const rate = (c?: PassCell) => (c ? c.passed / c.attempts : -1);
        return rate(a.cells[taskId]) - rate(b.cells[taskId]);
      },
      render: (_: unknown, row: MatrixRow) => {
        const cell = row.cells[taskId];
        return cell ? <Tag color={cellColor(cell)}>{ratio(cell.passed, cell.attempts)}</Tag> : "-";
      },
    })),
  ];

  const taskColumns: ColumnsType<TaskSummary> = [
    { title: t("overview.colTask"), dataIndex: "taskId", render: (id: string) => taskName(id) },
    {
      title: t("overview.colPassed"),
      key: "passed",
      sorter: (a, b) => a.passed / a.attempts - b.passed / b.attempts,
      render: (_, r) => `${ratio(r.passed, r.attempts)} (${Math.round((100 * r.passed) / r.attempts)}%)`,
    },
    { title: t("overview.colModels"), dataIndex: "models" },
    {
      title: t("overview.colPerfect"),
      dataIndex: "perfectModels",
      render: (names: string[]) => (names.length ? names.join(", ") : "-"),
    },
    {
      title: t("overview.colWeakest"),
      dataIndex: "weakestModels",
      render: (names: string[]) => (names.length ? names.join(", ") : "-"),
    },
    {
      title: t("overview.colCost"),
      dataIndex: "costUsd",
      sorter: (a, b) => (a.costUsd ?? 0) - (b.costUsd ?? 0),
      render: (v: number | null) => formatCost(v),
    },
    {
      title: t("overview.colAvgTime"),
      dataIndex: "avgDurationMs",
      sorter: (a, b) => (a.avgDurationMs ?? 0) - (b.avgDurationMs ?? 0),
      render: (v: number | null) => formatDuration(v),
    },
  ];

  return (
    <div className="page-stack">
      <div className="section-heading">
        <div>
          <Title level={2}>{t("overview.title")}</Title>
          <p>{t("overview.intro")}</p>
          {source !== "reviewed" && (
            <p>
              <Text type="warning">{t("overview.unreviewedWarning")}</Text>
            </p>
          )}
        </div>
      </div>

      <Card size="small" className="glass-card">
        <Space>
          <Text>{t("overview.runs")}</Text>
          <Segmented<SourceChoice>
            value={source}
            onChange={setSource}
            options={[
              { value: "all", label: t("overview.sourceAll") },
              { value: "reviewed", label: t("overview.sourceReviewed") },
              { value: "queue", label: t("overview.sourceQueue") },
            ]}
          />
        </Space>
      </Card>

      {runs.length === 0 ? (
        <Empty description={t("overview.noRuns")} />
      ) : (
        <Card className="glass-card">
          <Tabs
            items={[
              {
                key: "models",
                label: t("overview.tabByModel"),
                children: (
                  <Table
                    rowKey="modelId"
                    columns={modelColumns}
                    dataSource={models}
                    pagination={false}
                    size="small"
                    scroll={{ x: true }}
                  />
                ),
              },
              {
                key: "matrix",
                label: t("overview.tabMatrix"),
                children: (
                  <Table
                    rowKey="modelId"
                    columns={matrixColumns}
                    dataSource={matrix}
                    pagination={false}
                    size="small"
                    scroll={{ x: true }}
                  />
                ),
              },
              {
                key: "tasks",
                label: t("overview.tabByTask"),
                children: (
                  <Table
                    rowKey="taskId"
                    columns={taskColumns}
                    dataSource={taskRows}
                    pagination={false}
                    size="small"
                    scroll={{ x: true }}
                  />
                ),
              },
            ]}
          />
        </Card>
      )}
    </div>
  );
}
