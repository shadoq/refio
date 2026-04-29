import { useMemo, useEffect } from "react";
import {
  Typography,
  Card,
  Select,
  Tag,
  Table,
  Space,
  Empty,
  Spin,
} from "antd";
import type { ColumnsType } from "antd/es/table";
import { useSearchParams } from "react-router-dom";
import { useTasks } from "@/data/queries";
import { useResults } from "@/data/queries";
import { useFilters, applyFilters } from "@/store/filters";
import { useCompareSelection } from "@/store/compareSelection";
import { RadarCompare } from "@/components/charts/RadarCompare";
import { MetricRadarCompare } from "@/components/charts/MetricRadarCompare";
import { TaskRadarCompare } from "@/components/charts/TaskRadarCompare";
import { normalizeScore } from "@/lib/stats";

const { Title, Text } = Typography;

const MAX_MODELS = 6;

const COLORS = [
  "var(--accent-2)",
  "var(--accent)",
  "var(--accent-3)",
  "#9b8cff",
  "#ffd166",
  "#4dd4ac",
];

interface CompareRow {
  criterionId: string;
  criterionName: string;
  scores: Record<string, number | null>; // modelId → avg normalized score
}

export default function Compare() {
  const [searchParams, setSearchParams] = useSearchParams();
  const filters = useFilters();
  const compare = useCompareSelection();
  const { data: tasksData, isLoading: tasksLoading } = useTasks();
  const { data: resultsData, isLoading: resultsLoading } = useResults();

  // Sync URL ?models= → Zustand on mount
  useEffect(() => {
    const param = searchParams.get("models");
    if (param) {
      const ids = param.split(",").filter(Boolean);
      compare.setModels(ids);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Sync Zustand → URL whenever selection changes
  useEffect(() => {
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (compare.modelIds.length > 0) {
          next.set("models", compare.modelIds.join(","));
        } else {
          next.delete("models");
        }
        return next;
      },
      { replace: true },
    );
  }, [compare.modelIds, setSearchParams]);

  const filteredResults = useMemo(() => {
    if (!resultsData) return [];
    return applyFilters(resultsData.results, filters);
  }, [resultsData, filters]);

  const modelOptions = useMemo(
    () =>
      (resultsData?.models ?? []).map((m) => ({
        label: m.name,
        value: m.id,
      })),
    [resultsData],
  );

  const modelNames = useMemo(
    () => Object.fromEntries((resultsData?.models ?? []).map((m) => [m.id, m.name])),
    [resultsData],
  );

  // Build comparison table: rows = criteria, cols = selected models
  const comparisonRows = useMemo(() => {
    if (!tasksData || compare.modelIds.length === 0) return [];
    const criteria = tasksData.coreCriteria;

    return criteria.map((criterion): CompareRow => {
      const scores: Record<string, number | null> = {};
      for (const modelId of compare.modelIds) {
        const modelResults = filteredResults.filter((r) => r.modelId === modelId);
        const vals = modelResults
          .map((r) => {
            const s = r.scores.find((sc) => sc.criterionId === criterion.id);
            return s ? normalizeScore(s.value, criterion.scale.values) : null;
          })
          .filter((v): v is number => v !== null);
        scores[modelId] = vals.length > 0 ? vals.reduce((a, b) => a + b, 0) / vals.length : null;
      }
      return { criterionId: criterion.id, criterionName: criterion.name, scores };
    });
  }, [tasksData, filteredResults, compare.modelIds]);

  // Per-task breakdown: for each task, show scores per model
  const taskBreakdown = useMemo(() => {
    if (!tasksData || compare.modelIds.length === 0) return [];
    return tasksData.tasks.map((task) => {
      const taskResults = filteredResults.filter((r) => r.taskId === task.id);
      const modelScores: Record<string, number | null> = {};
      for (const modelId of compare.modelIds) {
        const mr = taskResults.filter((r) => r.modelId === modelId);
        if (mr.length === 0) {
          modelScores[modelId] = null;
        } else {
          const scores = mr.map((r) => {
            const normalized = r.scores
              .map((s) => {
                const c = tasksData.coreCriteria.find((cc) => cc.id === s.criterionId)
                  ?? task.extraCriteria.find((ec) => ec.id === s.criterionId);
                return c ? normalizeScore(s.value, c.scale.values) : null;
              })
              .filter((v): v is number => v !== null);
            return normalized.length > 0 ? normalized.reduce((a, b) => a + b, 0) / normalized.length : 0;
          });
          modelScores[modelId] = scores.reduce((a, b) => a + b, 0) / scores.length;
        }
      }
      return { taskId: task.id, taskName: task.name, modelScores };
    }).filter((row) => Object.values(row.modelScores).some((v) => v !== null));
  }, [tasksData, filteredResults, compare.modelIds]);

  // Comparison table columns
  const comparisonColumns: ColumnsType<CompareRow> = [
    { title: "Criterion", dataIndex: "criterionName", key: "crit", width: 180 },
    ...compare.modelIds.map((modelId, idx) => ({
      title: <Tag color={COLORS[idx % COLORS.length]}>{modelNames[modelId] ?? modelId}</Tag>,
      key: modelId,
      width: 120,
      render: (_: unknown, row: CompareRow) => {
        const score = row.scores[modelId];
        if (score == null) return "—";
        // Find the winner for this row
        const values = compare.modelIds.map((mid) => row.scores[mid] ?? -1);
        const maxVal = Math.max(...values);
        const isWinner = score === maxVal && score > 0;
        return (
          <span style={{ color: isWinner ? "var(--accent)" : undefined, fontWeight: isWinner ? 700 : 400 }}>
            {(score * 100).toFixed(1)}%
          </span>
        );
      },
    })),
  ];

  if (tasksLoading || resultsLoading) {
    return (
      <div style={{ display: "flex", justifyContent: "center", padding: 80 }}>
        <Spin size="large" />
      </div>
    );
  }

  return (
    <div>
      <Title level={2}>Compare Models</Title>

      <Card style={{ marginBottom: 24 }}>
        <Space direction="vertical" style={{ width: "100%" }}>
          <Text>Select up to {MAX_MODELS} models to compare:</Text>
          <Select
            mode="multiple"
            style={{ width: "100%" }}
            options={modelOptions}
            value={compare.modelIds}
            onChange={(ids: string[]) => compare.setModels(ids.slice(0, MAX_MODELS))}
            placeholder="Select models..."
            showSearch
          />
        </Space>
      </Card>

      {compare.modelIds.length === 0 ? (
        <Empty description="Select at least one model above to start comparing." />
      ) : (
        <>
          <Card title="Radar: Average Score per Criterion" style={{ marginBottom: 24 }}>
            <RadarCompare
              results={filteredResults}
              selectedModelIds={compare.modelIds}
              tasksFile={tasksData!}
              modelNames={modelNames}
            />
          </Card>

          <Card title="Radar: Derived Benchmark Metrics" style={{ marginBottom: 24 }}>
            <MetricRadarCompare
              results={filteredResults}
              selectedModelIds={compare.modelIds}
              tasksFile={tasksData!}
              resultsFile={resultsData!}
              modelNames={modelNames}
            />
          </Card>

          <Card title="Radar: Model Behavior by Task" style={{ marginBottom: 24 }}>
            <TaskRadarCompare
              results={filteredResults}
              selectedModelIds={compare.modelIds}
              tasksFile={tasksData!}
              modelNames={modelNames}
            />
          </Card>

          <Card title="Score by Criterion" style={{ marginBottom: 24 }}>
            <Table<CompareRow>
              columns={comparisonColumns}
              dataSource={comparisonRows}
              rowKey="criterionId"
              pagination={false}
              size="small"
            />
          </Card>

          {taskBreakdown.length > 0 && (
            <Card title="Per-task Breakdown">
              <Table
                columns={[
                  { title: "Task", dataIndex: "taskName", key: "task", width: 200 },
                  ...compare.modelIds.map((modelId, idx) => ({
                    title: <Tag color={COLORS[idx % COLORS.length]}>{modelNames[modelId] ?? modelId}</Tag>,
                    key: modelId,
                    render: (_: unknown, row: { modelScores: Record<string, number | null> }) => {
                      const score = row.modelScores[modelId];
                      if (score == null) return "—";
                      const values = compare.modelIds.map((mid) => row.modelScores[mid] ?? -1);
                      const maxVal = Math.max(...values);
                      const isWinner = score === maxVal && score > 0;
                      return (
                        <span style={{ color: isWinner ? "var(--accent)" : undefined, fontWeight: isWinner ? 700 : 400 }}>
                          {(score * 100).toFixed(1)}%
                        </span>
                      );
                    },
                  })),
                ]}
                dataSource={taskBreakdown}
                rowKey="taskId"
                pagination={false}
                size="small"
              />
            </Card>
          )}
        </>
      )}
    </div>
  );
}
