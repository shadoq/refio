import { useEffect, useMemo } from "react";
import { Card, Col, Empty, Row, Select, Space, Spin, Table, Tag, Typography } from "antd";
import type { ColumnsType } from "antd/es/table";
import { useSearchParams } from "react-router-dom";
import { useResults, useTasks } from "@/data/queries";
import { useFilters } from "@/store/filters";
import { COMPARE_SELECT_PARAM, useCompareSelection } from "@/store/compareSelection";
import { StabilityRadar } from "@/components/charts/StabilityRadar";
import { filterStabilityEntries, modelStability, type ModelStability } from "@/lib/stabilityView";

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

interface DimensionRow {
  key: string;
  label: string;
  scores: Record<string, number | null>;
}

const pct = (v: number | null | undefined) => (v == null ? "-" : `${(v * 100).toFixed(1)}%`);

export default function Stability() {
  const [searchParams, setSearchParams] = useSearchParams();
  const filters = useFilters();
  const compare = useCompareSelection();
  const { data: tasksData, isLoading: tasksLoading } = useTasks();
  const { data: resultsData, isLoading: resultsLoading } = useResults();

  // Shares the model selection (and its URL param) with the Compare page.
  useEffect(() => {
    const param = searchParams.get(COMPARE_SELECT_PARAM);
    if (param) compare.setModels(param.split(",").filter(Boolean));
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (compare.modelIds.length > 0) next.set(COMPARE_SELECT_PARAM, compare.modelIds.join(","));
        else next.delete(COMPARE_SELECT_PARAM);
        return next;
      },
      { replace: true },
    );
  }, [compare.modelIds, setSearchParams]);

  const entries = useMemo(() => {
    if (!resultsData || !tasksData) return [];
    const hidden = new Set(tasksData.tasks.filter((t) => t.hidden === true).map((t) => t.id));
    return filterStabilityEntries(resultsData.stability, filters, hidden);
  }, [resultsData, tasksData, filters]);

  const harnessLabel = useMemo(() => {
    const byId = new Map((resultsData?.harnesses ?? []).map((h) => [h.id, h.name]));
    return filters.harnessIds.map((id) => byId.get(id) ?? id).join(", ") || "all";
  }, [resultsData, filters.harnessIds]);

  const modelNames = useMemo(
    () => Object.fromEntries((resultsData?.models ?? []).map((m) => [m.id, m.name])),
    [resultsData],
  );

  const taskNames = useMemo(
    () => Object.fromEntries((tasksData?.tasks ?? []).map((t) => [t.id, t.name])),
    [tasksData],
  );

  // Every model with at least one stability group under the current filters, best first.
  const ranking = useMemo(() => {
    const ids = [...new Set(entries.map((e) => e.modelId))];
    return ids
      .map((id) => modelStability(entries, id))
      .filter((m): m is ModelStability => m != null)
      .sort((a, b) => b.overall - a.overall);
  }, [entries]);

  const byModel = useMemo(() => new Map(ranking.map((m) => [m.modelId, m])), [ranking]);

  const modelOptions = useMemo(
    () => ranking.map((m) => ({ label: modelNames[m.modelId] ?? m.modelId, value: m.modelId })),
    [ranking, modelNames],
  );

  const judgeIds = useMemo(
    () => [...new Set(entries.flatMap((e) => e.judges.map((j) => j.judgeId)))].sort(),
    [entries],
  );

  const taskIds = useMemo(
    () =>
      (tasksData?.tasks ?? [])
        .map((t) => t.id)
        .filter((id) => entries.some((e) => e.taskId === id)),
    [tasksData, entries],
  );

  const selected = compare.modelIds;

  const dimensionRows: DimensionRow[] = useMemo(() => {
    const row = (key: string, label: string, pick: (m: ModelStability) => number | null) => ({
      key,
      label,
      scores: Object.fromEntries(selected.map((id) => {
        const m = byModel.get(id);
        return [id, m ? pick(m) : null];
      })),
    });
    return [
      row("overall", "Overall stability", (m) => m.overall),
      row("consistency", "Score consistency", (m) => m.consistency),
      row("similarity", "Code similarity", (m) => m.similarity),
      ...judgeIds.map((j) => row(`judge:${j}`, `Judge: ${j}`, (m) => m.byJudge[j] ?? null)),
    ];
  }, [selected, byModel, judgeIds]);

  const taskRows: DimensionRow[] = useMemo(
    () =>
      taskIds.map((taskId) => ({
        key: taskId,
        label: taskNames[taskId] ?? taskId,
        scores: Object.fromEntries(
          selected.map((id) => [id, byModel.get(id)?.byTask[taskId] ?? null]),
        ),
      })),
    [taskIds, taskNames, selected, byModel],
  );

  // Radar axes skip the "overall" row - it is the blend of the other axes.
  const dimensionRadar = useMemo(
    () =>
      dimensionRows
        .filter((r) => r.key !== "overall")
        .map((r) => ({ axis: r.label, ...zeroMissing(r.scores) })),
    [dimensionRows],
  );

  const taskRadar = useMemo(
    () => taskRows.map((r) => ({ axis: r.label, ...zeroMissing(r.scores) })),
    [taskRows],
  );

  const compareColumns: ColumnsType<DimensionRow> = [
    { title: "", dataIndex: "label", key: "label", width: 200 },
    ...selected.map((modelId, idx) => ({
      title: <Tag color={COLORS[idx % COLORS.length]}>{modelNames[modelId] ?? modelId}</Tag>,
      key: modelId,
      width: 120,
      render: (_: unknown, row: DimensionRow) => {
        const score = row.scores[modelId];
        if (score == null) return "-";
        const maxVal = Math.max(...selected.map((mid) => row.scores[mid] ?? -1));
        const isWinner = score === maxVal && score > 0;
        return (
          <span
            style={{
              color: isWinner ? "var(--accent)" : undefined,
              fontWeight: isWinner || row.key === "overall" ? 700 : 400,
            }}
          >
            {pct(score)}
          </span>
        );
      },
    })),
  ];

  const rankingColumns: ColumnsType<ModelStability> = [
    { title: "#", key: "rank", width: 50, render: (_, __, i) => i + 1 },
    { title: "Model", key: "model", render: (_, m) => modelNames[m.modelId] ?? m.modelId },
    {
      title: "Overall",
      key: "overall",
      width: 110,
      sorter: (a, b) => a.overall - b.overall,
      render: (_, m) => <Text strong>{pct(m.overall)}</Text>,
    },
    {
      title: "Score consistency",
      key: "consistency",
      width: 150,
      sorter: (a, b) => a.consistency - b.consistency,
      render: (_, m) => pct(m.consistency),
    },
    {
      title: "Code similarity",
      key: "similarity",
      width: 140,
      sorter: (a, b) => a.similarity - b.similarity,
      render: (_, m) => pct(m.similarity),
    },
    ...judgeIds.map((j) => ({
      title: `Judge: ${j}`,
      key: `judge:${j}`,
      width: 140,
      sorter: (a: ModelStability, b: ModelStability) => (a.byJudge[j] ?? -1) - (b.byJudge[j] ?? -1),
      render: (_: unknown, m: ModelStability) => pct(m.byJudge[j]),
    })),
    { title: "Groups", key: "groups", width: 80, render: (_, m) => m.groups },
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
      <Title level={2}>Stability</Title>
      <Text type="secondary" style={{ display: "block", marginBottom: 16 }}>
        Harness: {harnessLabel}. How consistent a model is across repeated attempts at the same
        task. Overall stability is the equal-weight mean of score consistency (1 - 2 x mean score
        deviation between attempts), code similarity between the artifacts and the median judge
        verdict (0 divergent, 0.5 same approach with variable quality, 1 stable).
      </Text>

      <Card style={{ marginBottom: 24 }}>
        <Space direction="vertical" style={{ width: "100%" }}>
          <Text>Select up to {MAX_MODELS} models to compare:</Text>
          <Select
            mode="multiple"
            style={{ width: "100%" }}
            options={modelOptions}
            value={selected}
            onChange={(ids: string[]) => compare.setModels(ids.slice(0, MAX_MODELS))}
            placeholder="Select models..."
            showSearch
            optionFilterProp="label"
          />
        </Space>
      </Card>

      {selected.length === 0 ? (
        <Empty
          description="Select at least one model above, or pick one from the ranking below."
          style={{ marginBottom: 24 }}
        />
      ) : (
        <>
          <Row gutter={[24, 24]} style={{ marginBottom: 24 }}>
            <Col xs={24} xl={12}>
              <Card title="Radar: Stability by Task" style={{ height: "100%" }}>
                <StabilityRadar data={taskRadar} selectedModelIds={selected} modelNames={modelNames} />
              </Card>
            </Col>
            <Col xs={24} xl={12}>
              <Card title="Radar: Stability Dimensions" style={{ height: "100%" }}>
                <StabilityRadar
                  data={dimensionRadar}
                  selectedModelIds={selected}
                  modelNames={modelNames}
                />
              </Card>
            </Col>
          </Row>

          <Card title="Stability by Dimension" style={{ marginBottom: 24 }}>
            <Table<DimensionRow>
              columns={compareColumns}
              dataSource={dimensionRows}
              rowKey="key"
              pagination={false}
              size="small"
            />
          </Card>

          <Card title="Per-task Stability" style={{ marginBottom: 24 }}>
            <Table<DimensionRow>
              columns={compareColumns}
              dataSource={taskRows}
              rowKey="key"
              pagination={false}
              size="small"
            />
          </Card>
        </>
      )}

      <Card
        title="Stability Ranking"
        extra={<Text type="secondary">click a row to add or remove it from the comparison</Text>}
      >
        <Table<ModelStability>
          columns={rankingColumns}
          dataSource={ranking}
          rowKey="modelId"
          pagination={false}
          size="small"
          scroll={{ x: true }}
          onRow={(m) => ({
            onClick: () => compare.toggleModel(m.modelId),
            style: {
              cursor: "pointer",
              background: selected.includes(m.modelId) ? "rgba(73, 199, 255, 0.08)" : undefined,
            },
          })}
        />
      </Card>
    </div>
  );
}

// Recharts needs a number on every axis; a model with no group for a task sits at 0.
function zeroMissing(scores: Record<string, number | null>): Record<string, number> {
  return Object.fromEntries(Object.entries(scores).map(([k, v]) => [k, v ?? 0]));
}
