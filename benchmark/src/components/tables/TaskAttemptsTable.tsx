import { useMemo, useState } from "react";
import { Table, Tag, Modal, Typography, Space, Button } from "antd";
import type { ColumnsType } from "antd/es/table";
import type { Result } from "@/schema/results";
import type { TasksFile, Criterion } from "@/schema/tasks";
import { normalizeScore, normalizeResult } from "@/lib/stats";
import { formatDuration, formatCost, formatTokens, formatScore } from "@/lib/format";
import { AttachmentViewer } from "@/components/attachments/AttachmentViewer";

const { Text } = Typography;

interface RowData {
  key: string;
  modelId: string;
  modelName: string;
  environmentName: string;
  attemptNumber: number | "Avg";
  scores: Record<string, number | null>; // criterionId → normalized score (0..1) or null
  avgScore: number | null;
  durationMs: number | null | undefined;
  tokensIn: number | null | undefined;
  tokensOut: number | null | undefined;
  costUsd: number | null | undefined;
  attachmentCount: number;
  result: Result | null; // null for summary rows
}

interface TaskAttemptsTableProps {
  results: Result[];
  allCriteria: Criterion[]; // core + extra for this task
  tasksFile: TasksFile;
  modelNames: Record<string, string>;
  environmentNames: Record<string, string>;
}

function avg(vals: (number | undefined | null)[]): number | null {
  const nums = vals.filter((v): v is number => v != null);
  if (nums.length === 0) return null;
  return nums.reduce((a, b) => a + b, 0) / nums.length;
}

export function TaskAttemptsTable({
  results,
  allCriteria,
  tasksFile,
  modelNames,
  environmentNames,
}: TaskAttemptsTableProps) {
  const [detailResult, setDetailResult] = useState<Result | null>(null);

  const criteriaById = useMemo(
    () => new Map(tasksFile.coreCriteria.map((c) => [c.id, c])),
    [tasksFile],
  );

  // Build all rows
  const rows = useMemo(() => {
    const modelGroups = new Map<string, Result[]>();
    for (const r of results) {
      const key = `${r.modelId}::${r.environmentId}`;
      const g = modelGroups.get(key) ?? [];
      g.push(r);
      modelGroups.set(key, g);
    }

    const allRows: RowData[] = [];

    for (const [, group] of modelGroups) {
      const sorted = [...group].sort((a, b) => a.attemptNumber - b.attemptNumber);
      const { modelId, environmentId } = sorted[0];

      // Attempt rows
      const attemptRows: RowData[] = sorted.map((r) => {
        const scores: Record<string, number | null> = {};
        for (const c of allCriteria) {
          const s = r.scores.find((sc) => sc.criterionId === c.id);
          if (s) {
            const crit = criteriaById.get(c.id) ?? allCriteria.find((ac) => ac.id === c.id);
            scores[c.id] = crit ? normalizeScore(s.value, crit.scale.values) : null;
          } else {
            scores[c.id] = null;
          }
        }
        return {
          key: r.id,
          modelId,
          modelName: modelNames[modelId] ?? modelId,
          environmentName: environmentNames[environmentId] ?? environmentId,
          attemptNumber: r.attemptNumber,
          scores,
          avgScore: normalizeResult(r, tasksFile),
          durationMs: r.durationMs,
          tokensIn: r.tokensIn,
          tokensOut: r.tokensOut,
          costUsd: r.costUsd,
          attachmentCount: r.attachments.length,
          result: r,
        };
      });

      // Summary (avg) row
      const avgScores: Record<string, number | null> = {};
      for (const c of allCriteria) {
        const vals = attemptRows.map((r) => r.scores[c.id]);
        avgScores[c.id] = avg(vals);
      }

      const summaryRow: RowData = {
        key: `${modelId}::${environmentId}::avg`,
        modelId,
        modelName: modelNames[modelId] ?? modelId,
        environmentName: environmentNames[environmentId] ?? environmentId,
        attemptNumber: "Avg",
        scores: avgScores,
        avgScore: avg(attemptRows.map((r) => r.avgScore)),
        durationMs: avg(attemptRows.map((r) => r.durationMs)),
        tokensIn: avg(attemptRows.map((r) => r.tokensIn)),
        tokensOut: avg(attemptRows.map((r) => r.tokensOut)),
        costUsd: avg(attemptRows.map((r) => r.costUsd)),
        attachmentCount: 0,
        result: null,
      };

      allRows.push(...attemptRows, summaryRow);
    }

    return allRows;
  }, [results, allCriteria, modelNames, environmentNames, criteriaById, tasksFile]);

  // Fixed columns
  const fixedColumns: ColumnsType<RowData> = [
    {
      title: "Model",
      key: "model",
      width: 160,
      render: (_: unknown, row: RowData) => (
        <div>
          <Text strong>{row.modelName}</Text>
          <br />
          <Tag color={row.environmentName.toLowerCase().includes("cloud") ? "blue" : "green"} style={{ fontSize: 11 }}>
            {row.environmentName}
          </Tag>
        </div>
      ),
    },
    {
      title: "#",
      dataIndex: "attemptNumber",
      key: "attempt",
      width: 50,
      render: (v: number | "Avg") =>
        v === "Avg" ? <Text strong>Avg</Text> : v,
    },
  ];

  // Dynamic criterion columns
  const criterionColumns: ColumnsType<RowData> = allCriteria.map((c) => ({
    title: c.name,
    key: c.id,
    width: 90,
    render: (_: unknown, row: RowData) => {
      const score = row.scores[c.id];
      if (score == null) return "—";
      const pct = score * 100;
      const color = pct >= 80 ? "#52c41a" : pct >= 50 ? "#faad14" : "#ff4d4f";
      return <span style={{ color }}>{pct.toFixed(0)}%</span>;
    },
  }));

  // Metric + action columns
  const metricColumns: ColumnsType<RowData> = [
    {
      title: "Avg Score",
      key: "avgScore",
      width: 90,
      render: (_: unknown, row: RowData) =>
        row.avgScore != null ? (
          <Text strong>{formatScore(row.avgScore)}</Text>
        ) : "—",
    },
    {
      title: "Duration",
      key: "duration",
      width: 90,
      render: (_: unknown, row: RowData) => formatDuration(row.durationMs),
    },
    {
      title: "Tokens",
      key: "tokens",
      width: 90,
      render: (_: unknown, row: RowData) =>
        row.tokensOut != null ? formatTokens(row.tokensOut) : "—",
    },
    {
      title: "Cost",
      key: "cost",
      width: 80,
      render: (_: unknown, row: RowData) => formatCost(row.costUsd),
    },
    {
      title: "Files",
      key: "att",
      width: 60,
      render: (_: unknown, row: RowData) =>
        row.attachmentCount > 0 ? (
          <Button
            size="small"
            type="link"
            onClick={(e) => {
              e.stopPropagation();
              if (row.result) setDetailResult(row.result);
            }}
          >
            {row.attachmentCount} 📎
          </Button>
        ) : "—",
    },
  ];

  return (
    <>
      <Table<RowData>
        className="task-attempts-table"
        columns={[...fixedColumns, ...criterionColumns, ...metricColumns]}
        dataSource={rows}
        rowKey="key"
        pagination={false}
        size="small"
        scroll={{ x: "max-content" }}
        rowClassName={(row) =>
          row.attemptNumber === "Avg" ? "benchmark-summary-row" : ""
        }
        onRow={(row) => ({
          onClick: () => row.result && setDetailResult(row.result),
          style: { cursor: row.result ? "pointer" : "default" },
        })}
      />

      <Modal
        title={`Result detail — ${modelNames[detailResult?.modelId ?? ""] ?? ""} attempt #${detailResult?.attemptNumber}`}
        open={!!detailResult}
        onCancel={() => setDetailResult(null)}
        footer={null}
        width={700}
      >
        {detailResult && (
          <div>
            {detailResult.notes && (
              <p style={{ marginBottom: 16, color: "#555" }}>{detailResult.notes}</p>
            )}
            {detailResult.attachments.length === 0 && (
              <p style={{ color: "#999" }}>No attachments.</p>
            )}
            <Space direction="vertical" style={{ width: "100%" }}>
              {detailResult.attachments.map((att, idx) => (
                <div key={idx}>
                  {att.caption && (
                    <div style={{ marginBottom: 4, fontWeight: 500 }}>{att.caption}</div>
                  )}
                  <AttachmentViewer attachment={att} />
                </div>
              ))}
            </Space>
          </div>
        )}
      </Modal>
    </>
  );
}
