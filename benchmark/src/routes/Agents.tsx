import { useMemo, useState } from "react";
import { Typography, Card, Row, Col, Table, Tag, Space, Empty, Spin, Select, Tooltip } from "antd";
import type { ColumnsType } from "antd/es/table";
import { useResults, useTasks } from "@/data/queries";
import { useFilters, applyFilters } from "@/store/filters";
import {
  compareLeaderboardRows,
  leaderboard,
  harnessDelta,
  taskHarnessMatrix,
  type LeaderboardRow,
  type HarnessDeltaRow,
  type TaskHarnessRow,
} from "@/lib/stats";
import { aggregateTraces } from "@/lib/trace/aggregate";
import { formatDuration, formatScore } from "@/lib/format";
import { TraceSummaryTags } from "@/components/results/TraceSummaryTags";
import { TraceTimeline } from "@/components/results/TraceTimeline";
import type { Result } from "@/schema/results";

const { Title, Text, Paragraph } = Typography;

const BASELINE_HARNESS_ID = "refio";

function fmt(value: number | null, digits = 1): string {
  return value == null ? "-" : value.toFixed(digits);
}

// Everything about running the same tasks through an external coding agent: how the
// agents score against Refio, how the same model does under two of them, which tasks
// separate them, and what each run actually did step by step.
//
// It ignores the global harness filter on purpose - this page IS the cross-harness
// view - while model, task, environment and date still narrow it.
export default function Agents() {
  const filters = useFilters();
  const { data: tasksData } = useTasks();
  const { data: resultsData } = useResults();
  const [runA, setRunA] = useState<string | null>(null);
  const [runB, setRunB] = useState<string | null>(null);

  const externalHarnesses = useMemo(
    () => (resultsData?.harnesses ?? []).filter((h) => h.kind === "external"),
    [resultsData],
  );

  // Every harness, filtered by the other facets only.
  const visible = useMemo(() => {
    if (!resultsData) return [];
    return applyFilters(resultsData.results, { ...filters, harnessIds: [] });
  }, [resultsData, filters]);

  const resultsById = useMemo(
    () => new Map(visible.map((r) => [r.id, r])),
    [visible],
  );

  const rows = useMemo(() => {
    if (!tasksData || !resultsData) return [];
    return leaderboard(visible, resultsData, tasksData, { excludeSelfJudge: true }).sort(
      compareLeaderboardRows,
    );
  }, [visible, resultsData, tasksData]);

  // The trace metrics belong to the same grouping the leaderboard uses.
  const tracesByRow = useMemo(() => {
    const map = new Map<string, ReturnType<typeof aggregateTraces>>();
    for (const r of visible) {
      const key = `${r.modelId}::${r.environmentId}::${r.harnessId}`;
      map.set(key, map.get(key) ?? aggregateTraces([]));
    }
    for (const key of map.keys()) {
      const group = visible.filter(
        (r) => `${r.modelId}::${r.environmentId}::${r.harnessId}` === key,
      );
      map.set(key, aggregateTraces(group));
    }
    return map;
  }, [visible]);

  const deltaRows = useMemo(() => {
    if (!tasksData) return [];
    return harnessDelta(visible, tasksData, BASELINE_HARNESS_ID);
  }, [visible, tasksData]);

  const matrixRows = useMemo(() => {
    if (!tasksData) return [];
    return taskHarnessMatrix(visible, tasksData);
  }, [visible, tasksData]);

  const tracedRuns = useMemo(() => visible.filter((r) => r.trace), [visible]);

  if (!tasksData || !resultsData) {
    return (
      <div style={{ display: "flex", justifyContent: "center", padding: 40 }}>
        <Spin />
      </div>
    );
  }

  const harnessIdsInData = [...new Set(visible.map((r) => r.harnessId))];

  const agentColumns: ColumnsType<LeaderboardRow> = [
    {
      title: "Agent",
      key: "harness",
      render: (_, row) => (
        <Space direction="vertical" size={0}>
          <Tag color={row.harness.kind === "refio" ? "blue" : "orange"}>{row.harness.name}</Tag>
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
      render: (_, row) => (
        <Text strong className="model-name">
          {row.model.name}
        </Text>
      ),
    },
    { title: "Tasks", key: "tasks", width: 80, render: (_, row) => row.tasksEvaluated },
    { title: "Attempts", key: "attempts", width: 90, render: (_, row) => row.attemptCount },
    {
      title: "Avg score",
      key: "avgScore",
      width: 110,
      render: (_, row) => formatScore(row.avgScore),
      sorter: (a, b) => a.avgScore - b.avgScore,
      defaultSortOrder: "descend",
    },
    {
      title: (
        <Tooltip title="a judge's verdict on a run its own agent produced is excluded here">
          <span>Judges</span>
        </Tooltip>
      ),
      key: "judgeScore",
      width: 110,
      render: (_, row) =>
        row.judgeAvgScore == null ? <Text type="secondary">-</Text> : formatScore(row.judgeAvgScore),
    },
    {
      title: "Avg turns",
      key: "turns",
      width: 100,
      render: (_, row) =>
        fmt(tracesByRow.get(`${row.modelId}::${row.environmentId}::${row.harnessId}`)?.avgTurns ?? null),
    },
    {
      title: "Avg tools",
      key: "tools",
      width: 100,
      render: (_, row) =>
        fmt(
          tracesByRow.get(`${row.modelId}::${row.environmentId}::${row.harnessId}`)?.avgToolCalls ??
            null,
        ),
    },
    {
      title: "Avg writes",
      key: "writes",
      width: 100,
      render: (_, row) =>
        fmt(
          tracesByRow.get(`${row.modelId}::${row.environmentId}::${row.harnessId}`)?.avgWrites ??
            null,
        ),
    },
    {
      title: (
        <Tooltip title="share of runs where the model itself ran a build or a test">
          <span>Self-check</span>
        </Tooltip>
      ),
      key: "selfCheck",
      width: 110,
      render: (_, row) => {
        const rate = tracesByRow.get(
          `${row.modelId}::${row.environmentId}::${row.harnessId}`,
        )?.selfVerifiedRate;
        return rate == null ? <Text type="secondary">-</Text> : `${Math.round(rate * 100)}%`;
      },
    },
    {
      title: (
        <Tooltip title="share of tool calls that repeated one the agent had already made">
          <span>Wasted</span>
        </Tooltip>
      ),
      key: "wasted",
      width: 100,
      render: (_, row) => {
        const rate = tracesByRow.get(
          `${row.modelId}::${row.environmentId}::${row.harnessId}`,
        )?.wastedCallRate;
        return rate == null ? <Text type="secondary">-</Text> : `${Math.round(rate * 100)}%`;
      },
    },
    {
      title: (
        <Tooltip title="of the runs that hit a failing tool call, how many carried on afterwards">
          <span>Recovered</span>
        </Tooltip>
      ),
      key: "recovered",
      width: 110,
      render: (_, row) => {
        const rate = tracesByRow.get(
          `${row.modelId}::${row.environmentId}::${row.harnessId}`,
        )?.recoveryRate;
        return rate == null ? <Text type="secondary">-</Text> : `${Math.round(rate * 100)}%`;
      },
    },
    {
      title: (
        <Tooltip title="runs that ended other than by finishing: a cap, a timeout, a crash">
          <span>Unfinished</span>
        </Tooltip>
      ),
      key: "unfinished",
      width: 110,
      render: (_, row) => {
        const rate = tracesByRow.get(
          `${row.modelId}::${row.environmentId}::${row.harnessId}`,
        )?.unfinishedRate;
        return rate == null ? <Text type="secondary">-</Text> : `${Math.round(rate * 100)}%`;
      },
    },
    {
      title: "Avg duration",
      key: "duration",
      width: 130,
      render: (_, row) => formatDuration(row.avgDurationMs ?? undefined),
    },
  ];

  const deltaColumns: ColumnsType<HarnessDeltaRow> = [
    { title: "Model", dataIndex: "modelId", key: "modelId" },
    { title: "Environment", dataIndex: "environmentId", key: "environmentId", width: 150 },
    {
      title: "Refio",
      key: "baseline",
      width: 110,
      render: (_, row) =>
        row.baselineScore == null ? <Text type="secondary">not run</Text> : formatScore(row.baselineScore),
    },
    ...externalHarnesses.map((harness) => ({
      title: harness.name,
      key: harness.id,
      width: 120,
      render: (_: unknown, row: HarnessDeltaRow) =>
        row.byHarness[harness.id] === undefined ? (
          <Text type="secondary">-</Text>
        ) : (
          formatScore(row.byHarness[harness.id])
        ),
    })),
    {
      title: (
        <Tooltip title="averaged task by task over the tasks BOTH agents ran; the count is how many that was">
          <span>Delta</span>
        </Tooltip>
      ),
      key: "delta",
      render: (_, row) => (
        <Space wrap size={4}>
          {Object.entries(row.delta).map(([harnessId, value]) => (
            <Tag key={harnessId} color={value >= 0 ? "green" : "red"}>
              {harnessId} {value >= 0 ? "+" : ""}
              {value.toFixed(2)} ({row.pairedTasks[harnessId] ?? 0} shared)
            </Tag>
          ))}
          {Object.entries(row.pairedTasks)
            .filter(([harnessId, count]) => count === 0 && row.delta[harnessId] === undefined)
            .map(([harnessId]) => (
              <Tag key={harnessId}>{harnessId}: no shared task</Tag>
            ))}
        </Space>
      ),
    },
  ];

  const matrixColumns: ColumnsType<TaskHarnessRow> = [
    { title: "Task", dataIndex: "taskName", key: "taskName" },
    ...harnessIdsInData.map((harnessId) => ({
      title: harnessId,
      key: harnessId,
      width: 140,
      render: (_: unknown, row: TaskHarnessRow) => {
        const cell = row.byHarness[harnessId];
        return cell === undefined ? (
          <Text type="secondary">-</Text>
        ) : (
          <span>
            {formatScore(cell.avgScore)} <Text type="secondary">({cell.attempts})</Text>
          </span>
        );
      },
    })),
  ];

  const runLabel = (r: Result): string =>
    `${r.taskId} / ${r.modelId} / ${r.harnessId} / #${r.attemptNumber}`;
  const selectedA = runA ? resultsById.get(runA) ?? null : null;
  const selectedB = runB ? resultsById.get(runB) ?? null : null;
  // Two runs of different tasks cannot be compared step by step: the work differs.
  const optionsB = selectedA
    ? tracedRuns.filter((r) => r.taskId === selectedA.taskId && r.id !== selectedA.id)
    : [];

  const metricRows =
    selectedA?.trace && selectedB?.trace
      ? (
          [
            ["turns", selectedA.trace.turns, selectedB.trace.turns],
            ["tool calls", selectedA.trace.toolCalls, selectedB.trace.toolCalls],
            ["reads", selectedA.trace.reads, selectedB.trace.reads],
            ["writes", selectedA.trace.writes, selectedB.trace.writes],
            ["shell runs", selectedA.trace.shellRuns, selectedB.trace.shellRuns],
            [
              "self-check",
              selectedA.trace.selfVerified ? 1 : 0,
              selectedB.trace.selfVerified ? 1 : 0,
            ],
            [
              "time to first write (s)",
              Math.round((selectedA.trace.timeToFirstWriteMs ?? 0) / 1000),
              Math.round((selectedB.trace.timeToFirstWriteMs ?? 0) / 1000),
            ],
          ] as Array<[string, number, number]>
        ).map(([metric, a, b]) => ({ key: metric, metric, a, b, diff: b - a }))
      : [];

  return (
    <div>
      <Title level={2}>Agents</Title>
      <Paragraph type="secondary">
        The same tasks run by external coding agents - Claude Code, Codex, Gemini CLI - on
        their own planning, tools and self-checking, next to Refio. Measured on the same
        criteria and kept off the leaderboard: this page answers how far Refio's agent loop
        is from what is already on people's desks, and whether a strong model behaves
        differently when a different agent drives it. A model id starting with ollama/ was
        run locally under both, which is the pairing the delta table below is for.
      </Paragraph>

      {externalHarnesses.length === 0 ? (
        <Empty description="No external agent runs yet. Import one with import-runs --harness claude-code." />
      ) : (
        <Row gutter={[16, 16]}>
          <Col span={24}>
            <Space wrap>
              {externalHarnesses.map((harness) => (
                <Card key={harness.id} size="small" style={{ minWidth: 220 }}>
                  <Space direction="vertical" size={0}>
                    <Text strong>{harness.name}</Text>
                    {harness.version && <Text type="secondary">version {harness.version}</Text>}
                    {harness.conditions && (
                      <Text type="secondary" style={{ fontSize: 12 }}>
                        {harness.conditions}
                      </Text>
                    )}
                  </Space>
                </Card>
              ))}
            </Space>
          </Col>

          <Col span={24}>
            <Card title="Agents leaderboard" className="glass-card">
              <Table
                dataSource={rows}
                columns={agentColumns}
                rowKey={(row) => `${row.modelId}::${row.environmentId}::${row.harnessId}`}
                size="small"
                pagination={false}
                scroll={{ x: true }}
              />
            </Card>
          </Col>

          <Col span={24}>
            <Card title="Same model, two harnesses" className="glass-card">
              {deltaRows.length === 0 ? (
                <Empty description="Run the same ollama/... model under Refio and under an external agent to see this table" />
              ) : (
                <Table
                  dataSource={deltaRows}
                  columns={deltaColumns}
                  rowKey={(row) => `${row.modelId}::${row.environmentId}`}
                  size="small"
                  pagination={false}
                  scroll={{ x: true }}
                />
              )}
            </Card>
          </Col>

          <Col span={24}>
            <Card title="Task x harness" className="glass-card">
              <Table
                dataSource={matrixRows}
                columns={matrixColumns}
                rowKey={(row) => row.taskId}
                size="small"
                pagination={false}
                scroll={{ x: true }}
              />
            </Card>
          </Col>

          <Col span={24}>
            <Card title="Compare two runs" className="glass-card">
              {tracedRuns.length < 2 ? (
                <Empty description="At least two runs with a recorded trace are needed" />
              ) : (
                <Space direction="vertical" style={{ width: "100%" }} size="middle">
                  <Space wrap>
                    <Select
                      showSearch
                      optionFilterProp="label"
                      placeholder="Run A"
                      style={{ minWidth: 360 }}
                      value={runA}
                      onChange={(value) => {
                        setRunA(value);
                        setRunB(null);
                      }}
                      options={tracedRuns.map((r) => ({ value: r.id, label: runLabel(r) }))}
                    />
                    <Select
                      showSearch
                      optionFilterProp="label"
                      placeholder="Run B (same task)"
                      style={{ minWidth: 360 }}
                      value={runB}
                      onChange={setRunB}
                      disabled={!selectedA}
                      options={optionsB.map((r) => ({ value: r.id, label: runLabel(r) }))}
                    />
                  </Space>

                  {selectedA?.trace && selectedB?.trace && (
                    <>
                      <Table
                        dataSource={metricRows}
                        columns={[
                          { title: "Metric", dataIndex: "metric", key: "metric" },
                          { title: "A", dataIndex: "a", key: "a", width: 90 },
                          { title: "B", dataIndex: "b", key: "b", width: 90 },
                          {
                            title: "B - A",
                            dataIndex: "diff",
                            key: "diff",
                            width: 100,
                            render: (value: number) => (
                              <Tag color={value === 0 ? "default" : value > 0 ? "blue" : "orange"}>
                                {value > 0 ? "+" : ""}
                                {value}
                              </Tag>
                            ),
                          },
                        ]}
                        size="small"
                        pagination={false}
                      />
                      <Row gutter={16}>
                        <Col span={12}>
                          <Text strong>A: {runLabel(selectedA)}</Text>
                          <TraceSummaryTags trace={selectedA.trace} />
                          <TraceTimeline trace={selectedA.trace} />
                        </Col>
                        <Col span={12}>
                          <Text strong>B: {runLabel(selectedB)}</Text>
                          <TraceSummaryTags trace={selectedB.trace} />
                          <TraceTimeline trace={selectedB.trace} />
                        </Col>
                      </Row>
                    </>
                  )}
                </Space>
              )}
            </Card>
          </Col>
        </Row>
      )}
    </div>
  );
}
