import { useMemo } from "react";
import { useParams, Link } from "react-router-dom";
import { Typography, Card, Collapse, Tag, Empty, Spin } from "antd";
import { useTasks } from "@/data/queries";
import { useResults } from "@/data/queries";
import { useFilters, applyFilters } from "@/store/filters";
import { TaskAttemptsTable } from "@/components/tables/TaskAttemptsTable";
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

          <Card title="Score by Criterion">
            <BarByCriterion
              results={filteredResults}
              criteria={allCriteria}
              tasksFile={tasksData!}
              modelNames={modelNames}
            />
          </Card>
        </>
      )}
    </div>
  );
}
