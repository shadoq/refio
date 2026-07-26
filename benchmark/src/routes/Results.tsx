import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import {
  Button,
  Card,
  Empty,
  Input,
  Modal,
  Select,
  Space,
  Spin,
  Table,
  Tabs,
  Tag,
  Tooltip,
  Typography,
} from "antd";
import { ClearOutlined, EyeOutlined } from "@ant-design/icons";
import type { ColumnsType } from "antd/es/table";
import { AttachmentViewer } from "@/components/attachments/AttachmentViewer";
import { HtmlSandbox } from "@/components/attachments/HtmlSandbox";
import { JudgeBreakdown } from "@/components/results/JudgeBreakdown";
import { useResults, useTasks } from "@/data/queries";
import {
  formatCost,
  formatDuration,
  formatScore,
  formatTokens,
  formatTokensPerSecond,
} from "@/lib/format";
import { getResultCriterionScore, normalizeResult, visibleTasks } from "@/lib/stats";
import {
  aggregateJudgeScores,
  maxSharedDivergence,
  weightedNormalized,
} from "@/lib/judge/scoring";
import { estimateResultTokenProcessing } from "@/lib/tokenSpeed";
import type { Environment, Result } from "@/schema/results";
import type { Criterion, Task, TasksFile } from "@/schema/tasks";

const DIVERGENCE_THRESHOLD = 0.5;

// Full criteria set a judge scores: human core + task extra + judge-only.
function judgeCriteriaFor(tasks: TasksFile, task: Task | undefined): Criterion[] {
  return [...tasks.coreCriteria, ...(task?.extraCriteria ?? []), ...tasks.judgeCriteria];
}

const { Title, Text, Paragraph } = Typography;

interface ResultRow {
  key: string;
  result: Result;
  task: Task | undefined;
  modelName: string;
  environment: Environment | undefined;
  score: number;
  judgeScore: number | null;
  judgeCount: number;
  judgeDivergence: number;
}

export default function Results() {
  const { data: tasksData, isLoading: tasksLoading } = useTasks();
  const { data: resultsData, isLoading: resultsLoading } = useResults();
  const [modelFilter, setModelFilter] = useState<string[]>([]);
  const [taskFilter, setTaskFilter] = useState<string[]>([]);
  const [environmentFilter, setEnvironmentFilter] = useState<string[]>([]);
  const [environmentTypeFilter, setEnvironmentTypeFilter] = useState<Array<"local" | "cloud">>([]);
  const [searchText, setSearchText] = useState("");
  const [detailResult, setDetailResult] = useState<Result | null>(null);

  const taskById = useMemo(
    () => new Map((tasksData?.tasks ?? []).map((task) => [task.id, task])),
    [tasksData],
  );

  const modelById = useMemo(
    () => new Map((resultsData?.models ?? []).map((model) => [model.id, model])),
    [resultsData],
  );

  const environmentById = useMemo(
    () => new Map((resultsData?.environments ?? []).map((env) => [env.id, env])),
    [resultsData],
  );

  const rows = useMemo<ResultRow[]>(() => {
    if (!tasksData || !resultsData) return [];
    const query = searchText.trim().toLowerCase();

    return resultsData.results
      .filter((result) => {
        const model = modelById.get(result.modelId);
        const task = taskById.get(result.taskId);
        const environment = environmentById.get(result.environmentId);

        // Hidden tasks are excluded from the public results view entirely.
        if (task?.hidden) return false;
        if (modelFilter.length > 0 && !modelFilter.includes(result.modelId)) return false;
        if (taskFilter.length > 0 && !taskFilter.includes(result.taskId)) return false;
        if (environmentFilter.length > 0 && !environmentFilter.includes(result.environmentId)) return false;
        if (
          environmentTypeFilter.length > 0 &&
          (!environment || !environmentTypeFilter.includes(environment.type))
        ) {
          return false;
        }
        if (!query) return true;

        return [
          result.id,
          result.modelId,
          model?.name,
          result.taskId,
          task?.name,
          result.environmentId,
          environment?.name,
          result.notes,
        ]
          .filter(Boolean)
          .some((value) => String(value).toLowerCase().includes(query));
      })
      .map((result) => {
        const task = taskById.get(result.taskId);
        const aggregate = aggregateJudgeScores(result.judgeScores ?? []);
        const judgeCount = (result.judgeScores ?? []).filter((j) => j.error == null).length;
        return {
          key: result.id,
          result,
          task,
          modelName: modelById.get(result.modelId)?.name ?? result.modelId,
          environment: environmentById.get(result.environmentId),
          score: normalizeResult(result, tasksData),
          judgeScore:
            judgeCount > 0
              ? weightedNormalized(aggregate, judgeCriteriaFor(tasksData, task))
              : null,
          judgeCount,
          judgeDivergence: maxSharedDivergence(result.scores, aggregate),
        };
      })
      .sort((a, b) => b.result.runAt.localeCompare(a.result.runAt));
  }, [
    environmentById,
    environmentFilter,
    environmentTypeFilter,
    modelById,
    modelFilter,
    resultsData,
    searchText,
    taskById,
    taskFilter,
    tasksData,
  ]);

  const selectedRow = detailResult
    ? rows.find((row) => row.result.id === detailResult.id)
    : undefined;

  const scoreDetails = useMemo(() => {
    if (!detailResult || !tasksData) return [];
    const task = taskById.get(detailResult.taskId);
    const criteria = [...tasksData.coreCriteria, ...(task?.extraCriteria ?? [])];
    return criteria.map((criterion) => {
      const raw = detailResult.scores.find((score) => score.criterionId === criterion.id)?.value;
      const normalized = getResultCriterionScore(detailResult, tasksData, criterion.id);
      return {
        id: criterion.id,
        name: criterion.name,
        raw,
        normalized,
      };
    });
  }, [detailResult, taskById, tasksData]);

  const hasFilters =
    modelFilter.length > 0 ||
    taskFilter.length > 0 ||
    environmentFilter.length > 0 ||
    environmentTypeFilter.length > 0 ||
    searchText.trim().length > 0;

  const clearFilters = () => {
    setModelFilter([]);
    setTaskFilter([]);
    setEnvironmentFilter([]);
    setEnvironmentTypeFilter([]);
    setSearchText("");
  };

  const columns: ColumnsType<ResultRow> = [
    {
      title: "Task",
      key: "task",
      width: 220,
      render: (_, row) =>
        row.task ? (
          <Link to={`/tasks/${row.task.id}`}>{row.task.name}</Link>
        ) : (
          row.result.taskId
        ),
    },
    {
      title: "Model",
      key: "model",
      width: 220,
      render: (_, row) => (
        <div>
          <Text strong>{row.modelName}</Text>
          <br />
          <Text type="secondary" style={{ fontSize: 12 }}>
            {row.result.modelId}
          </Text>
        </div>
      ),
    },
    {
      title: "Environment",
      key: "environment",
      width: 160,
      render: (_, row) => (
        <Space direction="vertical" size={2}>
          <Text>{row.environment?.name ?? row.result.environmentId}</Text>
          <Tag color={row.environment?.type === "cloud" ? "blue" : "green"}>
            {row.environment?.type ?? "unknown"}
          </Tag>
        </Space>
      ),
    },
    {
      title: "Attempt",
      dataIndex: ["result", "attemptNumber"],
      key: "attempt",
      width: 90,
      sorter: (a, b) => a.result.attemptNumber - b.result.attemptNumber,
    },
    {
      title: "Score",
      key: "score",
      width: 110,
      sorter: (a, b) => a.score - b.score,
      render: (_, row) =>
        row.result.excludeFromStats ? (
          <Tooltip title="Excluded from all stats: no result could be established for this run">
            <Tag color="orange">not counted</Tag>
          </Tooltip>
        ) : (
          <Text strong>{formatScore(row.score)}</Text>
        ),
    },
    {
      title: "Auto (judges)",
      key: "judgeScore",
      width: 150,
      sorter: (a: ResultRow, b: ResultRow) => (a.judgeScore ?? -1) - (b.judgeScore ?? -1),
      render: (_: unknown, row: ResultRow) =>
        row.judgeScore == null ? (
          <Text type="secondary">-</Text>
        ) : (
          <Space size={4}>
            <Text strong>{formatScore(row.judgeScore)}</Text>
            <Text type="secondary" style={{ fontSize: 11 }}>
              ×{row.judgeCount}
            </Text>
            {row.judgeDivergence >= DIVERGENCE_THRESHOLD && (
              <Tooltip
                title={`Human and judges differ by ${row.judgeDivergence} on a shared criterion`}
              >
                <Tag color="orange" style={{ marginInlineEnd: 0 }}>
                  Δ
                </Tag>
              </Tooltip>
            )}
          </Space>
        ),
    },
    {
      title: "Duration",
      key: "duration",
      width: 110,
      render: (_, row) => formatDuration(row.result.durationMs),
      sorter: (a, b) => (a.result.durationMs ?? 0) - (b.result.durationMs ?? 0),
    },
    {
      title: "Tokens",
      key: "tokens",
      width: 120,
      render: (_, row) => {
        if (row.result.tokensIn == null && row.result.tokensOut == null) return "-";
        return `${formatTokens(row.result.tokensIn)} / ${formatTokens(row.result.tokensOut)}`;
      },
    },
    {
      title: "LLM Est.",
      key: "estimatedLlm",
      width: 110,
      render: (_, row) =>
        formatDuration(estimateResultTokenProcessing(row.result).totalMs),
      sorter: (a, b) =>
        (estimateResultTokenProcessing(a.result).totalMs ?? 0) -
        (estimateResultTokenProcessing(b.result).totalMs ?? 0),
    },
    {
      title: "Token Speed",
      key: "tokenSpeed",
      width: 150,
      render: (_, row) => {
        const estimate = estimateResultTokenProcessing(row.result);
        return (
          <span>
            {formatTokensPerSecond(estimate.prefillTokensPerSecond)} in
            <br />
            {formatTokensPerSecond(estimate.decodeTokensPerSecond)} out
          </span>
        );
      },
    },
    {
      title: "Cost",
      key: "cost",
      width: 100,
      render: (_, row) => formatCost(row.result.costUsd),
      sorter: (a, b) => (a.result.costUsd ?? 0) - (b.result.costUsd ?? 0),
    },
    {
      title: "Run",
      key: "runAt",
      width: 140,
      render: (_, row) => new Date(row.result.runAt).toLocaleDateString(),
      sorter: (a, b) => a.result.runAt.localeCompare(b.result.runAt),
    },
    {
      title: "",
      key: "action",
      width: 72,
      render: (_, row) => (
        <Button
          aria-label="View result"
          icon={<EyeOutlined />}
          onClick={(event) => {
            event.stopPropagation();
            setDetailResult(row.result);
          }}
        />
      ),
    },
  ];

  if (tasksLoading || resultsLoading) {
    return (
      <div style={{ display: "flex", justifyContent: "center", padding: 80 }}>
        <Spin size="large" />
      </div>
    );
  }

  if (!tasksData || !resultsData || resultsData.results.length === 0) {
    return <Empty description="No benchmark results yet." />;
  }

  return (
    <div>
      <Title level={2}>Results</Title>
      <Paragraph type="secondary">
        Browse individual benchmark runs without opening the admin editor.
      </Paragraph>

      <Card style={{ marginBottom: 16 }}>
        <Space wrap>
          <Select
            mode="multiple"
            allowClear
            showSearch
            optionFilterProp="label"
            placeholder="Models"
            value={modelFilter}
            onChange={setModelFilter}
            options={resultsData.models.map((model) => ({
              value: model.id,
              label: model.name,
            }))}
            style={{ minWidth: 220 }}
          />
          <Select
            mode="multiple"
            allowClear
            showSearch
            optionFilterProp="label"
            placeholder="Tasks"
            value={taskFilter}
            onChange={setTaskFilter}
            options={visibleTasks(tasksData.tasks).map((task) => ({
              value: task.id,
              label: task.name,
            }))}
            style={{ minWidth: 200 }}
          />
          <Select
            mode="multiple"
            allowClear
            showSearch
            optionFilterProp="label"
            placeholder="Environments"
            value={environmentFilter}
            onChange={setEnvironmentFilter}
            options={resultsData.environments.map((env) => ({
              value: env.id,
              label: env.name,
            }))}
            style={{ minWidth: 190 }}
          />
          <Select
            mode="multiple"
            allowClear
            placeholder="Env type"
            value={environmentTypeFilter}
            onChange={setEnvironmentTypeFilter}
            options={[
              { value: "local", label: "Local" },
              { value: "cloud", label: "Cloud" },
            ]}
            style={{ minWidth: 140 }}
          />
          <Input.Search
            allowClear
            placeholder="Search ID, model, notes"
            value={searchText}
            onChange={(event) => setSearchText(event.target.value)}
            style={{ width: 240 }}
          />
          <Text type="secondary">
            {rows.length} / {resultsData.results.length}
          </Text>
          {hasFilters && (
            <Button icon={<ClearOutlined />} onClick={clearFilters}>
              Clear
            </Button>
          )}
        </Space>
      </Card>

      <Card>
        <Table<ResultRow>
          columns={columns}
          dataSource={rows}
          rowKey="key"
          size="middle"
          pagination={{ pageSize: 20, showSizeChanger: true }}
          scroll={{ x: "max-content" }}
          onRow={(row) => ({
            onClick: () => setDetailResult(row.result),
            style: { cursor: "pointer" },
          })}
        />
      </Card>

      <ResultDetailModal
        key={detailResult?.id ?? "none"}
        detailResult={detailResult}
        selectedRow={selectedRow}
        scoreDetails={scoreDetails}
        criteria={
          detailResult ? judgeCriteriaFor(tasksData, taskById.get(detailResult.taskId)) : []
        }
        onClose={() => setDetailResult(null)}
      />
    </div>
  );
}

interface ResultDetailModalProps {
  detailResult: Result | null;
  selectedRow: ResultRow | undefined;
  scoreDetails: Array<{ id: string; name: string; raw: number | undefined; normalized: number | null }>;
  criteria: Criterion[];
  onClose: () => void;
}

function ResultDetailModal({
  detailResult,
  selectedRow,
  scoreDetails,
  criteria,
  onClose,
}: ResultDetailModalProps) {
  // previewIdx resets automatically: the parent keys this modal by result id.
  const [previewIdx, setPreviewIdx] = useState<number | null>(null);

  const htmlAttachments = useMemo(
    () =>
      (detailResult?.attachments ?? [])
        .map((att, index) => ({ att, index }))
        .filter((entry) => entry.att.type === "html"),
    [detailResult],
  );
  const otherAttachments = useMemo(
    () =>
      (detailResult?.attachments ?? [])
        .map((att, index) => ({ att, index }))
        .filter((entry) => entry.att.type !== "html"),
    [detailResult],
  );
  const hasHtml = htmlAttachments.length > 0;
  const previewActive = previewIdx !== null && previewIdx >= 0 && previewIdx < htmlAttachments.length;
  const safeHtmlIdx = previewActive ? (previewIdx as number) : 0;
  const activeHtml = previewActive ? htmlAttachments[safeHtmlIdx].att : null;

  const title = selectedRow
    ? `${selectedRow.modelName} - ${selectedRow.task?.name ?? detailResult?.taskId}`
    : "Result detail";

  const meta = detailResult && (
    <Space direction="vertical" size="large" style={{ width: "100%" }}>
      <Space wrap>
        <Tag>attempt #{detailResult.attemptNumber}</Tag>
        {detailResult.excludeFromStats && <Tag color="orange">not counted</Tag>}
        <Tag>{selectedRow?.environment?.name ?? detailResult.environmentId}</Tag>
        <Tag>{formatDuration(detailResult.durationMs)}</Tag>
        <Tag>
          LLM est. {formatDuration(estimateResultTokenProcessing(detailResult).totalMs)}
        </Tag>
        <Tag>{formatCost(detailResult.costUsd)}</Tag>
      </Space>

      <Space wrap>
        <Tag>
          Prefill {formatDuration(estimateResultTokenProcessing(detailResult).prefillMs)}
          {" / "}
          {formatTokensPerSecond(
            estimateResultTokenProcessing(detailResult).prefillTokensPerSecond,
          )}
        </Tag>
        <Tag>
          Decode {formatDuration(estimateResultTokenProcessing(detailResult).decodeMs)}
          {" / "}
          {formatTokensPerSecond(
            estimateResultTokenProcessing(detailResult).decodeTokensPerSecond,
          )}
        </Tag>
      </Space>

      {detailResult.notes && <Paragraph>{detailResult.notes}</Paragraph>}

      <Table
        size="small"
        pagination={false}
        rowKey="id"
        dataSource={scoreDetails}
        columns={[
          { title: "Criterion", dataIndex: "name", key: "name" },
          {
            title: "Raw",
            dataIndex: "raw",
            key: "raw",
            width: 90,
            render: (value: number | undefined) => value ?? "-",
          },
          {
            title: "Normalized",
            dataIndex: "normalized",
            key: "normalized",
            width: 130,
            render: (value: number | null) => (value == null ? "-" : formatScore(value)),
          },
        ]}
      />

      <JudgeBreakdown detailResult={detailResult} criteria={criteria} />

      {!hasHtml && otherAttachments.length === 0 && (
        <Empty description="No attachments for this result." />
      )}

      {hasHtml && (
        <Space direction="vertical" style={{ width: "100%" }} size="small">
          <Text strong>HTML previews</Text>
          {htmlAttachments.map((entry, i) => (
            <div
              key={`${entry.att.src}-${entry.index}`}
              style={{ display: "flex", alignItems: "center", gap: 12 }}
            >
              <Button size="small" icon={<EyeOutlined />} onClick={() => setPreviewIdx(i)}>
                Show preview
              </Button>
              <Text type="secondary">{entry.att.caption ?? entry.att.src}</Text>
            </div>
          ))}
        </Space>
      )}

      {otherAttachments.length > 0 && (
        <Space direction="vertical" style={{ width: "100%" }}>
          {otherAttachments.map(({ att, index }) => (
            <div key={`${att.src}-${index}`}>
              {att.caption && (
                <Text strong style={{ display: "block", marginBottom: 8 }}>
                  {att.caption}
                </Text>
              )}
              <AttachmentViewer attachment={att} />
            </div>
          ))}
        </Space>
      )}
    </Space>
  );

  if (!previewActive) {
    return (
      <Modal
        title={title}
        open={!!detailResult}
        onCancel={onClose}
        footer={null}
        width={780}
      >
        {meta}
      </Modal>
    );
  }

  return (
    <Modal
      title={title}
      open={!!detailResult}
      onCancel={onClose}
      footer={null}
      width="90vw"
      style={{ top: 24 }}
      styles={{ body: { padding: 0, height: "85vh" } }}
    >
      <div style={{ display: "flex", height: "100%", minHeight: 0 }}>
        <div
          style={{
            flex: "0 0 40%",
            maxWidth: 520,
            minWidth: 320,
            overflow: "auto",
            padding: 24,
            borderRight: "1px solid rgba(255,255,255,0.08)",
          }}
        >
          {meta}
        </div>
        <div style={{ flex: 1, minWidth: 0, display: "flex", flexDirection: "column" }}>
          <div
            style={{
              display: "flex",
              alignItems: "center",
              justifyContent: "space-between",
              padding: "8px 16px 0",
              gap: 12,
            }}
          >
            {htmlAttachments.length > 1 ? (
              <Tabs
                activeKey={String(safeHtmlIdx)}
                onChange={(key) => setPreviewIdx(Number(key))}
                items={htmlAttachments.map((entry, i) => ({
                  key: String(i),
                  label: entry.att.caption ?? `HTML ${i + 1}`,
                }))}
                style={{ flex: 1, minWidth: 0 }}
              />
            ) : (
              <Text type="secondary">{activeHtml?.caption ?? activeHtml?.src}</Text>
            )}
            <Button size="small" onClick={() => setPreviewIdx(null)}>
              Hide preview
            </Button>
          </div>
          <div style={{ flex: 1, minHeight: 0, background: "#fff" }}>
            {activeHtml && (
              <HtmlSandbox
                key={`${activeHtml.src}-${safeHtmlIdx}`}
                src={activeHtml.src}
                caption={activeHtml.caption}
                fill
              />
            )}
          </div>
        </div>
      </div>
    </Modal>
  );
}

