import { useMemo } from "react";
import { Collapse, Descriptions, Divider, Empty, Modal, Space, Table, Tag, Typography } from "antd";
import { AttachmentViewer } from "@/components/attachments/AttachmentViewer";
import { HtmlSandbox } from "@/components/attachments/HtmlSandbox";
import { JudgeBreakdown } from "@/components/results/JudgeBreakdown";
import { resultAdminFacts } from "@/lib/adminFacts";
import { formatScore } from "@/lib/format";
import { getResultCriterionScore } from "@/lib/stats";
import type { Result, ResultsFile } from "@/schema/results";
import type { TasksFile } from "@/schema/tasks";

const { Text } = Typography;

interface ResultPreviewModalProps {
  result: Result | null;
  tasksData: TasksFile | undefined;
  resultsData: ResultsFile | undefined;
  onClose: () => void;
}

// Read-only admin preview of a single result. It surfaces the same detail the
// public result view shows (scores, strong-judge verdicts, HTML preview) plus
// the administrative facts the public view hides: raw ids, timestamps, exact
// metrics, attachment paths and the full JSON record.
export function ResultPreviewModal({
  result,
  tasksData,
  resultsData,
  onClose,
}: ResultPreviewModalProps) {
  const task = useMemo(
    () => (result && tasksData ? tasksData.tasks.find((t) => t.id === result.taskId) : undefined),
    [result, tasksData],
  );
  const model = useMemo(
    () => (result && resultsData ? resultsData.models.find((m) => m.id === result.modelId) : undefined),
    [result, resultsData],
  );
  const environment = useMemo(
    () =>
      result && resultsData
        ? resultsData.environments.find((e) => e.id === result.environmentId)
        : undefined,
    [result, resultsData],
  );

  const facts = useMemo(
    () =>
      result
        ? resultAdminFacts(result, {
            taskName: task?.name,
            modelName: model?.name,
            environmentName: environment?.name,
            environmentType: environment?.type,
          })
        : [],
    [result, task, model, environment],
  );

  // Human-scored criteria (core + task extra) with raw and normalized values.
  const scoreDetails = useMemo(() => {
    if (!result || !tasksData) return [];
    const criteria = [...tasksData.coreCriteria, ...(task?.extraCriteria ?? [])];
    return criteria.map((criterion) => ({
      id: criterion.id,
      name: criterion.name,
      raw: result.scores.find((score) => score.criterionId === criterion.id)?.value,
      normalized: getResultCriterionScore(result, tasksData, criterion.id),
    }));
  }, [result, tasksData, task]);

  // Every criterion a judge might score, used purely as a name lookup for the
  // judge breakdown (unfiltered, so agent_logic still resolves a display name).
  const judgeCriteria = useMemo(() => {
    if (!tasksData) return [];
    return [...tasksData.coreCriteria, ...(task?.extraCriteria ?? []), ...tasksData.judgeCriteria];
  }, [tasksData, task]);

  const json = useMemo(() => (result ? JSON.stringify(result, null, 2) : ""), [result]);

  const htmlAttachments = (result?.attachments ?? []).filter((att) => att.type === "html");
  const otherAttachments = (result?.attachments ?? []).filter((att) => att.type !== "html");

  const title = result
    ? `${model?.name ?? result.modelId} - ${task?.name ?? result.taskId}`
    : "Result preview";

  return (
    <Modal
      title={title}
      open={!!result}
      onCancel={onClose}
      footer={null}
      width={860}
      style={{ top: 24 }}
      styles={{ body: { maxHeight: "80vh", overflow: "auto" } }}
    >
      {result && (
        <Space direction="vertical" size="middle" style={{ width: "100%" }}>
          <Divider titlePlacement="start" style={{ marginTop: 0 }}>
            Administrative
          </Divider>
          <Descriptions bordered size="small" column={1}>
            {facts.map((fact) => (
              <Descriptions.Item key={fact.label} label={fact.label}>
                {fact.copyable ? (
                  <Text copyable code>
                    {fact.value}
                  </Text>
                ) : (
                  fact.value
                )}
              </Descriptions.Item>
            ))}
          </Descriptions>

          {result.attachments.length > 0 && (
            <div>
              <Text strong>Attachment paths</Text>
              <div style={{ marginTop: 6 }}>
                {result.attachments.map((att, index) => (
                  <div
                    key={`${att.src}-${index}`}
                    style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 4 }}
                  >
                    <Tag>{att.type}</Tag>
                    <Text code copyable style={{ fontSize: 12 }}>
                      {att.src}
                    </Text>
                  </div>
                ))}
              </div>
            </div>
          )}

          <Divider titlePlacement="start">Scores</Divider>
          <Table
            size="small"
            pagination={false}
            rowKey="id"
            dataSource={scoreDetails}
            locale={{ emptyText: "No scores" }}
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

          <JudgeBreakdown detailResult={result} criteria={judgeCriteria} />

          {htmlAttachments.length > 0 && (
            <>
              <Divider titlePlacement="start">HTML preview</Divider>
              {htmlAttachments.map((att, index) => (
                <HtmlSandbox
                  key={`${att.src}-${index}`}
                  src={att.src}
                  caption={att.caption}
                  height={420}
                />
              ))}
            </>
          )}

          {otherAttachments.length > 0 && (
            <>
              <Divider titlePlacement="start">Attachments</Divider>
              {otherAttachments.map((att, index) => (
                <div key={`${att.src}-${index}`}>
                  {att.caption && (
                    <Text strong style={{ display: "block", marginBottom: 8 }}>
                      {att.caption}
                    </Text>
                  )}
                  <AttachmentViewer attachment={att} />
                </div>
              ))}
            </>
          )}

          {result.attachments.length === 0 && (
            <Empty description="No attachments for this result." />
          )}

          <Collapse
            items={[
              {
                key: "json",
                label: "Raw JSON",
                children: (
                  <Space direction="vertical" size="small" style={{ width: "100%" }}>
                    <Text copyable={{ text: json }}>Copy JSON</Text>
                    <pre
                      style={{
                        maxHeight: 340,
                        overflow: "auto",
                        margin: 0,
                        fontSize: 12,
                        whiteSpace: "pre-wrap",
                        wordBreak: "break-word",
                      }}
                    >
                      {json}
                    </pre>
                  </Space>
                ),
              },
            ]}
          />
        </Space>
      )}
    </Modal>
  );
}
