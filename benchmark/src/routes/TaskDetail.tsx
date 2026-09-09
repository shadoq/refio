import { useMemo } from "react";
import { useParams, Link } from "react-router-dom";
import { Typography, Card, Collapse, Tag, Empty, Spin, Table, Tooltip } from "antd";
import { useTasks } from "@/data/queries";
import { useResults } from "@/data/queries";
import { useFilters, applyFilters } from "@/store/filters";
import { TaskAttemptsTable } from "@/components/tables/TaskAttemptsTable";
import { harnessDelta } from "@/lib/stats";
import { BarByCriterion } from "@/components/charts/BarByCriterion";

const { Title, Text, Paragraph } = Typography;

export default function TaskDetail() {
  const { taskId } = useParams<{ taskId: string }>();
  const filters = useFilters();
  const { data: tasksData, isLoading: tasksLoading } = useTasks();
  const { data: resultsData, isLoading: resultsLoading } = useResults();

  const task = useMemo(
    () => tasksData?.tasks.find((t) => t.id === taskId),
    [tasksData, taskId],
  );

  const allCriteria = useMemo(() => {
    if (!tasksData || !task) return [];
    return [...tasksData.coreCriteria, ...task.extraCriteria];
  }, [tasksData, task]);

  const filteredResults = useMemo(() => {
    if (!resultsData || !taskId) return [];
    const taskResults = resultsData.results.filter((r) => r.taskId === taskId);
    return applyFilters(taskResults, { ...filters, taskIds: [] }); // don't filter by taskId here
  }, [resultsData, taskId, filters]);

  const modelNames = useMemo(
    () => Object.fromEntries((resultsData?.models ?? []).map((m) => [m.id, m.name])),
    [resultsData],
  );

  const environmentNames = useMemo(
    () => Object.fromEntries((resultsData?.environments ?? []).map((e) => [e.id, e.name])),
    [resultsData],
  );

  // The comparison the reference track exists for: the same model under two harnesses.
  // It deliberately ignores the harness filter, which would otherwise hide one side.
  const deltaRows = useMemo(() => {
    if (!resultsData || !tasksData || !taskId) return [];
    const taskResults = resultsData.results.filter((r) => r.taskId === taskId);
    return harnessDelta(taskResults, tasksData, "refio");
  }, [resultsData, tasksData, taskId]);

  const harnessNames = useMemo(
    () => Object.fromEntries((resultsData?.harnesses ?? []).map((h) => [h.id, h.name])),
    [resultsData],
  );

  const otherHarnessIds = useMemo(
    () => [...new Set(deltaRows.flatMap((r) => Object.keys(r.byHarness)))].filter((h) => h !== "refio"),
    [deltaRows],
  );

  const stabilityEntries = useMemo(
    () => (resultsData?.stability ?? []).filter((s) => s.taskId === taskId),
    [resultsData, taskId],
  );

  if (tasksLoading || resultsLoading) {
    return (
      <div style={{ display: "flex", justifyContent: "center", padding: 80 }}>
        <Spin size="large" />
      </div>
    );
  }

  if (!task) {
    return (
      <div>
        <Link to="/">← Back</Link>
        <Empty description={`Task "${taskId}" not found`} style={{ marginTop: 40 }} />
      </div>
    );
  }

  const collapseItems = [
    {
      key: "prompt",
      label: "System Prompt",
      children: (
        <pre style={{ whiteSpace: "pre-wrap", fontSize: 12, padding: 12, borderRadius: 4 }}>
          {task.systemPrompt}
        </pre>
      ),
    },
  ];

  return (
    <div>
      <Link to="/">← Leaderboard</Link>

      <Title level={2} style={{ marginTop: 16 }}>
        {task.name}
      </Title>
      <Paragraph style={{ color: "#555" }}>{task.description}</Paragraph>

      <Collapse items={collapseItems} style={{ marginBottom: 24 }} />

      <Card title="Criteria" style={{ marginBottom: 24 }}>
        {allCriteria.map((c) => (
          <div key={c.id} style={{ marginBottom: 8 }}>
            <Tag color={tasksData?.coreCriteria.some((cc) => cc.id === c.id) ? "blue" : "purple"}>
              {c.name}
            </Tag>
            <Text type="secondary">{c.description}</Text>
            <Text style={{ marginLeft: 8, fontSize: 11, color: "#999" }}>
              scale: [{c.scale.values.join(", ")}]
            </Text>
          </div>
        ))}
      </Card>

      {filteredResults.length === 0 ? (
        <Empty description="No results yet for this task." />
      ) : (
        <>
          <Card title="Attempts" style={{ marginBottom: 24 }}>
            <TaskAttemptsTable
              results={filteredResults}
              allCriteria={allCriteria}
              tasksFile={tasksData!}
              modelNames={modelNames}
              environmentNames={environmentNames}
            />
          </Card>

          <Card title="Score by Criterion" style={{ marginBottom: 24 }}>
            <BarByCriterion
              results={filteredResults}
              criteria={allCriteria}
              tasksFile={tasksData!}
              modelNames={modelNames}
            />
          </Card>
        </>
      )}

      {deltaRows.length > 0 && (
        <Card title="Refio vs external agents" style={{ marginBottom: 24 }}>
          <Text type="secondary" style={{ display: "block", marginBottom: 12 }}>
            The same model on the same task, driven by Refio and by an external coding
            agent. The difference is the agent's scaffolding, not the model.
          </Text>
          <Table
            size="small"
            pagination={false}
            rowKey={(r) => `${r.modelId}::${r.environmentId}`}
            dataSource={deltaRows}
            columns={[
              {
                title: "Model",
                key: "model",
                render: (_, r) => modelNames[r.modelId] ?? r.modelId,
              },
              {
                title: "Refio",
                key: "refio",
                width: 100,
                render: (_, r) =>
                  r.baselineScore === null ? (
                    <Text type="secondary">not run</Text>
                  ) : (
                    r.baselineScore.toFixed(2)
                  ),
              },
              ...otherHarnessIds.map((harnessId) => ({
                title: harnessNames[harnessId] ?? harnessId,
                key: harnessId,
                width: 140,
                render: (_: unknown, r: (typeof deltaRows)[number]) => {
                  const score = r.byHarness[harnessId];
                  if (score === undefined) return <Text type="secondary">-</Text>;
                  const d = r.delta[harnessId];
                  return (
                    <span>
                      {score.toFixed(2)}
                      {d !== undefined && (
                        <Tag color={d >= 0 ? "green" : "red"} style={{ marginInlineStart: 8 }}>
                          {d >= 0 ? "+" : ""}
                          {d.toFixed(2)}
                        </Tag>
                      )}
                    </span>
                  );
                },
              })),
            ]}
          />
        </Card>
      )}

      {stabilityEntries.length > 0 && (
        <Card title="Stability across attempts">
          <Text type="secondary" style={{ display: "block", marginBottom: 12 }}>
            Consistency of a model's solutions across repeated attempts. Lower score
            variance and higher code similarity mean more stable output.
          </Text>
          <Table
            size="small"
            pagination={false}
            rowKey={(s) => `${s.modelId}::${s.environmentId}`}
            dataSource={stabilityEntries}
            columns={[
              {
                title: "Model",
                key: "model",
                render: (_, s) => modelNames[s.modelId] ?? s.modelId,
              },
              {
                title: "Environment",
                key: "environment",
                render: (_, s) => environmentNames[s.environmentId] ?? s.environmentId,
              },
              {
                title: "Attempts",
                key: "attempts",
                width: 90,
                render: (_, s) => s.resultIds.length,
              },
              {
                title: "Score variance",
                key: "variance",
                width: 130,
                render: (_, s) => s.deterministic.scoreVariance.toFixed(3),
              },
              {
                title: "Code similarity",
                key: "similarity",
                width: 130,
                render: (_, s) => s.deterministic.codeSimilarity.toFixed(3),
              },
              {
                title: "Judges",
                key: "judges",
                render: (_, s) =>
                  s.judges.length === 0 ? (
                    <Text type="secondary">-</Text>
                  ) : (
                    s.judges.map((j) => (
                      <Tooltip key={j.judgeId} title={j.rationale ?? ""}>
                        <Tag color="blue">
                          {j.judgeId}: {j.value}
                        </Tag>
                      </Tooltip>
                    ))
                  ),
              },
            ]}
          />
        </Card>
      )}
    </div>
  );
}
