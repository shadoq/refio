import { useMemo } from "react";
import { Card, Image, Space, Table, Tag, Typography } from "antd";
import { aggregateJudgeScores } from "@/lib/judge/scoring";
import type { Result } from "@/schema/results";
import type { Criterion } from "@/schema/tasks";

const { Text } = Typography;

function avgOf(scores: Array<{ value: number }>): string {
  if (scores.length === 0) return "n/a";
  return (scores.reduce((sum, s) => sum + s.value, 0) / scores.length).toFixed(2);
}

interface JudgeBreakdownProps {
  detailResult: Result;
  criteria: Criterion[];
}

// Strong-judge verdicts for one result: per-judge criterion scores, rationales
// and screenshots, plus the median aggregate. `criteria` is only a name lookup,
// so any id it does not cover falls back to the raw criterion id.
export function JudgeBreakdown({ detailResult, criteria }: JudgeBreakdownProps) {
  const nameById = useMemo(
    () => new Map(criteria.map((c) => [c.id, c.name])),
    [criteria],
  );

  const judgeSets = detailResult.judgeScores ?? [];
  if (judgeSets.length === 0) return null;
  const aggregate = aggregateJudgeScores(judgeSets);

  return (
    <Space direction="vertical" style={{ width: "100%" }} size="small">
      <Text strong>Strong-judge scores</Text>
      {judgeSets.map((set) => (
        <Card
          key={set.judgeId}
          size="small"
          title={
            <Space wrap>
              <Text strong>{set.judgeId}</Text>
              <Text type="secondary" style={{ fontSize: 12 }}>
                {set.judgeModel}
              </Text>
              {set.error ? (
                <Tag color="red">error</Tag>
              ) : (
                <Tag color="blue">avg {avgOf(set.scores)}</Tag>
              )}
            </Space>
          }
        >
          {set.error ? (
            <Text type="danger">{set.error}</Text>
          ) : (
            <Table
              size="small"
              pagination={false}
              rowKey="criterionId"
              dataSource={set.scores}
              columns={[
                {
                  title: "Criterion",
                  dataIndex: "criterionId",
                  key: "criterionId",
                  render: (id: string) => nameById.get(id) ?? id,
                },
                { title: "Value", dataIndex: "value", key: "value", width: 70 },
                {
                  title: "Rationale",
                  dataIndex: "rationale",
                  key: "rationale",
                  render: (r?: string) => r ?? "-",
                },
              ]}
            />
          )}
          {set.screenshots.length > 0 && (
            <Image.PreviewGroup>
              <Space wrap style={{ marginTop: 8 }}>
                {set.screenshots.map((src) => (
                  <Image
                    key={src}
                    src={src.startsWith("http") ? src : `/data/${src}`}
                    alt="judge screenshot"
                    width={160}
                    style={{ border: "1px solid rgba(0,0,0,0.15)" }}
                  />
                ))}
              </Space>
            </Image.PreviewGroup>
          )}
        </Card>
      ))}
      <Text type="secondary" style={{ fontSize: 12 }}>
        Aggregate (median):{" "}
        {Object.entries(aggregate)
          .map(([id, value]) => `${nameById.get(id) ?? id}=${value}`)
          .join(", ")}
      </Text>
    </Space>
  );
}
