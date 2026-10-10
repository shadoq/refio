import { Card, Space, Tag, Button, Popconfirm, Descriptions, Typography } from "antd";
import { EyeOutlined, EditOutlined, CopyOutlined, DeleteOutlined } from "@ant-design/icons";
import { useT } from "@/i18n/LanguageProvider";
import { ArtifactPreview } from "@/components/attachments/ArtifactPreview";
import { inboxScreenshots } from "@/lib/adminArtifacts";
import { formatDuration, formatCost } from "@/lib/format";
import type { Result, ResultsFile } from "@/schema/results";
import type { TasksFile } from "@/schema/tasks";

const { Text } = Typography;

interface ResultCardProps {
  result: Result;
  tasksData: TasksFile | undefined;
  resultsData: ResultsFile | undefined;
  onPreview: (result: Result) => void;
  onEdit: (result: Result) => void;
  onDuplicate: (result: Result) => void;
  onDelete: (resultId: string) => void;
}

// A queue-style detailed card for one promoted result: artifact preview (captured screenshots
// plus an opt-in live HTML render) alongside its scores and metrics, with the same
// preview/edit/duplicate/delete actions as the table row. Backs the Results admin "cards" view.
export function ResultCard({
  result,
  tasksData,
  resultsData,
  onPreview,
  onEdit,
  onDuplicate,
  onDelete,
}: ResultCardProps) {
  const t = useT();
  const task = tasksData?.tasks.find((tk) => tk.id === result.taskId);
  const model = resultsData?.models.find((m) => m.id === result.modelId);
  const env = resultsData?.environments.find((e) => e.id === result.environmentId);

  const html = result.attachments.find((a) => a.type === "html");
  const screenshots = inboxScreenshots(result);

  const criteria = [...(tasksData?.coreCriteria ?? []), ...(task?.extraCriteria ?? [])];
  const nameById = new Map(criteria.map((c) => [c.id, c.name]));

  return (
    <Card
      style={{ marginBottom: 16 }}
      title={
        <Space wrap>
          <Text strong>{task?.name ?? result.taskId}</Text>
          <Text type="secondary">{model?.name ?? result.modelId}</Text>
          {env && <Tag color={env.type === "cloud" ? "blue" : "green"}>{env.name}</Tag>}
          <Tag>{t("resultView.attempt", { n: result.attemptNumber })}</Tag>
        </Space>
      }
      extra={
        <Space>
          <Button
            aria-label={t("resultView.previewResult")}
            icon={<EyeOutlined />}
            size="small"
            onClick={() => onPreview(result)}
          />
          <Button
            aria-label={t("resultView.editResult")}
            icon={<EditOutlined />}
            size="small"
            onClick={() => onEdit(result)}
          />
          <Button
            aria-label={t("resultView.duplicateResult")}
            icon={<CopyOutlined />}
            size="small"
            onClick={() => onDuplicate(result)}
          />
          <Popconfirm
            title={t("resultView.deleteConfirm")}
            onConfirm={() => onDelete(result.id)}
            okText={t("resultView.deleteOk")}
            okButtonProps={{ danger: true }}
          >
            <Button aria-label={t("resultView.deleteResult")} icon={<DeleteOutlined />} size="small" danger />
          </Popconfirm>
        </Space>
      }
    >
      <div style={{ display: "grid", gridTemplateColumns: "1.2fr 1fr", gap: 16 }}>
        <div>
          {html || screenshots.length > 0 ? (
            <ArtifactPreview htmlSrc={html?.src ?? null} screenshots={screenshots} />
          ) : (
            <Text type="secondary">{t("resultView.noArtifact")}</Text>
          )}
        </div>
        <div>
          <Descriptions size="small" column={1} style={{ marginBottom: 12 }}>
            <Descriptions.Item label={t("resultView.duration")}>{formatDuration(result.durationMs)}</Descriptions.Item>
            <Descriptions.Item label={t("resultView.tokensOut")}>{result.tokensOut ?? "-"}</Descriptions.Item>
            <Descriptions.Item label={t("resultView.cost")}>{formatCost(result.costUsd)}</Descriptions.Item>
          </Descriptions>

          <Text strong>{t("resultView.scores")}</Text>
          <div style={{ margin: "6px 0", display: "flex", flexWrap: "wrap", gap: 4 }}>
            {result.scores.length === 0 ? (
              <Text type="secondary">{t("resultView.noScores")}</Text>
            ) : (
              result.scores.map((s) => (
                <Tag key={s.criterionId}>
                  {nameById.get(s.criterionId) ?? s.criterionId}: {s.value}
                </Tag>
              ))
            )}
          </div>

          {result.notes && (
            <Text type="secondary" style={{ fontSize: 12 }}>
              {result.notes}
            </Text>
          )}
        </div>
      </div>
    </Card>
  );
}
