import { Card, Col, Collapse, Row, Space, Tag, Typography } from "antd";

const { Title, Paragraph, Text } = Typography;

const leaderboardMetrics = [
  {
    name: "Avg Score",
    formula: "average(normalized criterion scores)",
    description:
      "Overall quality score for a result group. Each criterion value is normalized by its maximum scale value, then averaged across criteria and attempts.",
  },
  {
    name: "Pass Rate",
    formula: "passing attempts / all attempts",
    description:
      "Share of attempts where the normalized result score is at least 70%. It is a quick success-rate signal.",
  },
  {
    name: "First-shot",
    formula: "score of attempt #1",
    description:
      "Normalized score for the first attempt. The OK/Fix tag is based on the works_out_of_box criterion when available, otherwise on the first-shot score.",
  },
  {
    name: "Reliability",
    formula: "1 - standardDeviation(scores) / 0.5",
    description:
      "Stability across attempts. It is clamped to 0-100%. A model with consistent scores gets a higher reliability value.",
  },
  {
    name: "Local Viability",
    formula: "localQualityRatio * 0.7 + stability * 0.3",
    description:
      "Local-only metric. localQualityRatio compares the local average score against the best cloud average score. Stability uses Reliability when available, otherwise Pass Rate.",
  },
  {
    name: "Avg Duration",
    formula: "average(duration) in seconds",
    description:
      "Average runtime for the filtered attempts in a model and environment group. The admin form accepts seconds, and the UI formats longer values as minutes and seconds.",
  },
  {
    name: "Avg Cost",
    formula: "average(costUsd)",
    description:
      "Average cloud/API cost per attempt for the filtered model and environment group. This is usually the better cost metric for comparing one run against another.",
  },
  {
    name: "Total Cost",
    formula: "sum(costUsd)",
    description:
      "Total cloud/API cost recorded across all filtered attempts. This shows budget spent, but can favor models with fewer recorded attempts.",
  },
];

const resultFields = [
  {
    name: "Task",
    description: "Benchmark scenario being evaluated, for example Snake.",
  },
  {
    name: "Model",
    description: "Model identifier and display name from results.json.",
  },
  {
    name: "Environment",
    description: "Runtime target, such as local DGX or cloud API. Environment type is local or cloud.",
  },
  {
    name: "Attempt",
    description: "Attempt number for the same task, model and environment combination.",
  },
  {
    name: "Tokens",
    description: "Displayed as input / output tokens when token counts are available.",
  },
  {
    name: "Attachments",
    description:
      "Optional screenshots, HTML previews, videos or embeds attached to a specific benchmark result.",
  },
];

export default function Help() {
  return (
    <div className="page-stack">
      <div className="section-heading">
        <div>
          <Title level={2}>Help</Title>
          <Paragraph>
            Metric definitions used by the benchmark views and individual result pages.
          </Paragraph>
        </div>
      </div>

      <Row gutter={[16, 16]}>
        {leaderboardMetrics.map((metric) => (
          <Col key={metric.name} xs={24} md={12} xl={8}>
            <Card className="glass-card" title={metric.name}>
              <Space direction="vertical" size="small">
                <Text>{metric.description}</Text>
                <Tag color="blue">{metric.formula}</Tag>
              </Space>
            </Card>
          </Col>
        ))}
      </Row>

      <Card className="glass-card" title="Score Normalization">
        <Paragraph>
          Raw criterion values use the scale defined in <Text code>tasks.json</Text>.
          A score is normalized as <Text code>value / max(scale.values)</Text>. For
          example, a value of 1 on a 0-1 scale is 100%, and a value of 1.5 on a
          0-2 scale is 75%.
        </Paragraph>
        <Paragraph>
          Result score is the average of all normalized criterion scores present in
          that result. Leaderboard rows then aggregate those result scores for each
          model and environment pair after the active filters are applied.
        </Paragraph>
        <Paragraph>
          Leaderboard ranking is sorted by <Text strong>Avg Score</Text>. If two
          rows have the same Avg Score, ties are broken by average{" "}
          <Text code>works_out_of_box</Text>, then average{" "}
          <Text code>compliance</Text>, then <Text strong>First-shot</Text>.
        </Paragraph>
      </Card>

      <Collapse
        items={[
          {
            key: "fields",
            label: "Fields on the Results page",
            children: (
              <Space direction="vertical" style={{ width: "100%" }}>
                {resultFields.map((field) => (
                  <div key={field.name}>
                    <Text strong>{field.name}: </Text>
                    <Text>{field.description}</Text>
                  </div>
                ))}
              </Space>
            ),
          },
          {
            key: "pareto",
            label: "Pareto Explorer",
            children: (
              <Paragraph>
                Pareto charts compare two metrics at once. For quality, reliability,
                first-shot, pass rate and local viability, higher is better. For cost
                and duration, lower is better. Points near the better edge on both
                axes represent stronger trade-offs.
              </Paragraph>
            ),
          },
        ]}
      />
    </div>
  );
}
