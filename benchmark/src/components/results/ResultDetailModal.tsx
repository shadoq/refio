import { useMemo, useState } from "react";
import { Button, Collapse, Empty, Modal, Space, Table, Tabs, Tag, Typography } from "antd";
import { EyeOutlined } from "@ant-design/icons";
import { AttachmentViewer } from "@/components/attachments/AttachmentViewer";
import { HtmlSandbox } from "@/components/attachments/HtmlSandbox";
import { JudgeBreakdown } from "@/components/results/JudgeBreakdown";
import { TraceSummaryTags } from "@/components/results/TraceSummaryTags";
import { TraceTimeline } from "@/components/results/TraceTimeline";
import { formatCost, formatDuration, formatScore, formatTokensPerSecond } from "@/lib/format";
import { getResultCriterionScore } from "@/lib/stats";
import { estimateResultTokenProcessing } from "@/lib/tokenSpeed";
import type { Result } from "@/schema/results";
import type { Criterion, Task, TasksFile } from "@/schema/tasks";

const { Text, Paragraph } = Typography;

// Full criteria set a judge scores: human core + task extra + judge-only.
function judgeCriteriaFor(tasks: TasksFile, task: Task | undefined): Criterion[] {
  return [...tasks.coreCriteria, ...(task?.extraCriteria ?? []), ...tasks.judgeCriteria];
}

interface ResultDetailModalProps {
  detailResult: Result | null;
  tasksFile: TasksFile;
  task: Task | undefined;
  modelName: string | undefined;
  environmentName: string | undefined;
  onClose: () => void;
}

// Parents key this modal by result id, so its local state (the open preview) resets per result.
export function ResultDetailModal({
  detailResult,
  tasksFile,
  task,
  modelName,
  environmentName,
  onClose,
}: ResultDetailModalProps) {
  const [previewIdx, setPreviewIdx] = useState<number | null>(null);

  const scoreDetails = useMemo(() => {
    if (!detailResult) return [];
    const criteria = [...tasksFile.coreCriteria, ...(task?.extraCriteria ?? [])];
    return criteria.map((criterion) => ({
      id: criterion.id,
      name: criterion.name,
      raw: detailResult.scores.find((score) => score.criterionId === criterion.id)?.value,
      normalized: getResultCriterionScore(detailResult, tasksFile, criterion.id),
    }));
  }, [detailResult, task, tasksFile]);
  const criteria = judgeCriteriaFor(tasksFile, task);

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
  const previewActive =
    previewIdx !== null && previewIdx >= 0 && previewIdx < htmlAttachments.length;
  const safeHtmlIdx = previewActive ? (previewIdx as number) : 0;
  const activeHtml = previewActive ? htmlAttachments[safeHtmlIdx].att : null;

  const title = modelName
    ? `${modelName} - ${task?.name ?? detailResult?.taskId}`
    : "Result detail";

  const meta = detailResult && (
    <Space direction="vertical" size="large" style={{ width: "100%" }}>
      <Space wrap>
        <Tag>attempt #{detailResult.attemptNumber}</Tag>
        {detailResult.harnessId !== "refio" && <Tag color="orange">{detailResult.harnessId}</Tag>}
        <Tag>{environmentName ?? detailResult.environmentId}</Tag>
        <Tag>{formatDuration(detailResult.durationMs)}</Tag>
        <Tag>LLM est. {formatDuration(estimateResultTokenProcessing(detailResult).totalMs)}</Tag>
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
          {formatTokensPerSecond(estimateResultTokenProcessing(detailResult).decodeTokensPerSecond)}
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

      {detailResult.trace && (
        <Space direction="vertical" style={{ width: "100%" }} size="small">
          <Text strong>Run trace</Text>
          <TraceSummaryTags trace={detailResult.trace} />
          <Collapse
            ghost
            size="small"
            items={[
              {
                key: "trace",
                label: "Show steps",
                children: <TraceTimeline trace={detailResult.trace} />,
              },
            ]}
          />
        </Space>
      )}

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
      <Modal title={title} open={!!detailResult} onCancel={onClose} footer={null} width={780}>
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
