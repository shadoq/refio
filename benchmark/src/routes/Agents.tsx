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
import { useT } from "@/i18n/LanguageProvider";
import type { MessageKey } from "@/i18n/messages";

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
  const t = useT();
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
      title: t("agents.colAgent"),
      key: "harness",
      render: (_, row) => (
        <Space direction="vertical" size={0}>
          <Tag color={row.harness.kind === "refio" ? "blue" : "orange"}>{row.harness.name}</Tag>
          {row.harness.conditions && (
            <Tooltip title={row.harness.conditions}>
              <Text type="secondary" style={{ fontSize: 11 }}>
                {t("agents.runConditions")}
              </Text>
            </Tooltip>
          )}
        </Space>
      ),
    },
    {
      title: t("agents.colModel"),
      key: "model",
      render: (_, row) => (
        <Text strong className="model-name">
          {row.model.name}
        </Text>
      ),
    },
    { title: t("agents.colTasks"), key: "tasks", width: 80, render: (_, row) => row.tasksEvaluated },
    { title: t("agents.colAttempts"), key: "attempts", width: 90, render: (_, row) => row.attemptCount },
    {
      title: t("agents.colAvgScore"),
      key: "avgScore",
      width: 110,
      render: (_, row) => formatScore(row.avgScore),
      sorter: (a, b) => a.avgScore - b.avgScore,
      defaultSortOrder: "descend",
    },
    {
      title: (
        <Tooltip title={t("agents.colJudgesTip")}>
          <span>{t("agents.colJudges")}</span>
        </Tooltip>
      ),
      key: "judgeScore",
      width: 110,
      render: (_, row) =>
        row.judgeAvgScore == null ? <Text type="secondary">-</Text> : formatScore(row.judgeAvgScore),
    },
    {
      title: t("agents.colAvgTurns"),
      key: "turns",
      width: 100,
      render: (_, row) =>
        fmt(tracesByRow.get(`${row.modelId}::${row.environmentId}::${row.harnessId}`)?.avgTurns ?? null),
    },
    {
      title: t("agents.colAvgTools"),
      key: "tools",
      width: 100,
      render: (_, row) =>
        fmt(
          tracesByRow.get(`${row.modelId}::${row.environmentId}::${row.harnessId}`)?.avgToolCalls ??
            null,
        ),
    },
    {
      title: t("agents.colAvgWrites"),
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
        <Tooltip title={t("agents.colSelfCheckTip")}>
          <span>{t("agents.colSelfCheck")}</span>
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
        <Tooltip title={t("agents.colWastedTip")}>
          <span>{t("agents.colWasted")}</span>
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
        <Tooltip title={t("agents.colRecoveredTip")}>
          <span>{t("agents.colRecovered")}</span>
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
        <Tooltip title={t("agents.colUnfinishedTip")}>
          <span>{t("agents.colUnfinished")}</span>
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
      title: t("agents.colAvgDuration"),
      key: "duration",
      width: 130,
      render: (_, row) => formatDuration(row.avgDurationMs ?? undefined),
    },
  ];

  const deltaColumns: ColumnsType<HarnessDeltaRow> = [
    { title: t("agents.colModel"), dataIndex: "modelId", key: "modelId" },
    { title: t("agents.colEnvironment"), dataIndex: "environmentId", key: "environmentId", width: 150 },
    {
      title: "Refio",
      key: "baseline",
      width: 110,
      render: (_, row) =>
        row.baselineScore == null ? <Text type="secondary">{t("agents.notRun")}</Text> : formatScore(row.baselineScore),
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
        <Tooltip title={t("agents.colDeltaTip")}>
          <span>{t("agents.colDelta")}</span>
        </Tooltip>
      ),
      key: "delta",
      render: (_, row) => (
        <Space wrap size={4}>
          {Object.entries(row.delta).map(([harnessId, value]) => (
            <Tag key={harnessId} color={value >= 0 ? "green" : "red"}>
              {harnessId} {value >= 0 ? "+" : ""}
              {value.toFixed(2)} {t("agents.sharedTasks", { count: row.pairedTasks[harnessId] ?? 0 })}
            </Tag>
          ))}
          {Object.entries(row.pairedTasks)
            .filter(([harnessId, count]) => count === 0 && row.delta[harnessId] === undefined)
            .map(([harnessId]) => (
              <Tag key={harnessId}>{t("agents.noSharedTask", { harness: harnessId })}</Tag>
            ))}
        </Space>
      ),
    },
  ];

  const matrixColumns: ColumnsType<TaskHarnessRow> = [
    { title: t("agents.colTask"), dataIndex: "taskName", key: "taskName" },
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
            ["agents.metricTurns", selectedA.trace.turns, selectedB.trace.turns],
            ["agents.metricToolCalls", selectedA.trace.toolCalls, selectedB.trace.toolCalls],
            ["agents.metricReads", selectedA.trace.reads, selectedB.trace.reads],
            ["agents.metricWrites", selectedA.trace.writes, selectedB.trace.writes],
            ["agents.metricShellRuns", selectedA.trace.shellRuns, selectedB.trace.shellRuns],
            [
              "agents.metricSelfCheck",
              selectedA.trace.selfVerified ? 1 : 0,
              selectedB.trace.selfVerified ? 1 : 0,
            ],
            [
              "agents.metricTimeToFirstWrite",
              Math.round((selectedA.trace.timeToFirstWriteMs ?? 0) / 1000),
              Math.round((selectedB.trace.timeToFirstWriteMs ?? 0) / 1000),
            ],
          ] as Array<[MessageKey, number, number]>
        ).map(([metric, a, b]) => ({ key: metric, metric: t(metric), a, b, diff: b - a }))
      : [];

  return (
    <div>
      <Title level={2}>{t("agents.title")}</Title>
      <Paragraph type="secondary">{t("agents.intro")}</Paragraph>

      {externalHarnesses.length === 0 ? (
        <Empty description={t("agents.emptyExternal")} />
      ) : (
        <Row gutter={[16, 16]}>
          <Col span={24}>
            <Space wrap>
              {externalHarnesses.map((harness) => (
                <Card key={harness.id} size="small" style={{ minWidth: 220 }}>
                  <Space direction="vertical" size={0}>
                    <Text strong>{harness.name}</Text>
                    {harness.version && <Text type="secondary">{t("agents.version", { version: harness.version })}</Text>}
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
            <Card title={t("agents.leaderboardTitle")} className="glass-card">
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
            <Card title={t("agents.sameModelTitle")} className="glass-card">
              {deltaRows.length === 0 ? (
                <Empty description={t("agents.sameModelEmpty")} />
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
            <Card title={t("agents.matrixTitle")} className="glass-card">
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
            <Card title={t("agents.compareRunsTitle")} className="glass-card">
              {tracedRuns.length < 2 ? (
                <Empty description={t("agents.compareRunsEmpty")} />
              ) : (
                <Space direction="vertical" style={{ width: "100%" }} size="middle">
                  <Space wrap>
                    <Select
                      showSearch
                      optionFilterProp="label"
                      placeholder={t("agents.runA")}
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
                      placeholder={t("agents.runB")}
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
                          { title: t("agents.colMetric"), dataIndex: "metric", key: "metric" },
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
