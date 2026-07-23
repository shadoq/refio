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
    name: "Judge Score",
    formula: "average(weighted-normalized judge aggregate per result)",
    description:
      "Overall quality as scored by strong-judge agents (Claude Code, Codex), independent of the human Avg Score. Per result the judges' median value per criterion is weighted-normalized like the human score, then averaged over judged attempts. The count shows judged / all attempts.",
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
    name: "LLM Est.",
    formula: "duration split into 20% prefill and 80% decode",
    description:
      "Estimated token-processing time split into prefill and decode. This uses the measured attempt duration because the current benchmark data does not store TTFT/decode telemetry separately.",
  },
  {
    name: "Token Speed",
    formula: "tokensIn / estimated prefill time, tokensOut / estimated decode time",
    description:
      "Effective prefill and decode throughput derived from each run's token counts and duration. Treat it as benchmark-effective speed, not raw provider telemetry.",
  },
  {
    name: "Avg API Cost",
    formula: "average(costUsd)",
    description:
      "Average cloud/API cost per attempt for the filtered model and environment group. This is usually the better cost metric for comparing one run against another.",
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
    description:
      "Displayed as input / output tokens when token counts are available. Token counts also drive the estimated prefill/decode speed metrics.",
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
            key: "strong-judge",
            label: "Strong-judge scoring",
            children: (
              <Space direction="vertical">
                <Paragraph>
                  On top of the manual scores, artifacts can be scored by{" "}
                  <Text strong>strong-judge agents</Text> - external CLI agents
                  (Claude Code and Codex) run headless and read-only via{" "}
                  <Text code>npm run judge</Text>. Each artifact is rendered with
                  Playwright (two screenshots plus captured console errors), and every
                  judge scores it blind: it never sees the human scores or the other
                  judge's scores.
                </Paragraph>
                <Paragraph>
                  <Text strong>Criteria.</Text> Judges score the same criteria as the
                  human (Compliance, Works out of the box, Look, Code quality) plus two
                  judge-only criteria: <Text code>code_structure</Text> (structure,
                  naming, duplication, dead code) and <Text code>logic_correctness</Text>{" "}
                  (correctness read from the code, not only the screen). The human{" "}
                  <Text code>agent_logic</Text> criterion is <Text strong>not</Text>{" "}
                  judged - it rates the coding agent's workflow (check files, edit,
                  verify, summarize), which cannot be seen in a static artifact.
                </Paragraph>
                <Paragraph>
                  <Text strong>Aggregate and divergence.</Text> Per criterion the
                  aggregate is the median across judges, computed in the viewer and
                  never stored. The Results page shows an aggregate{" "}
                  <Text strong>Auto (judges)</Text> column with a{" "}
                  <Text strong>divergence badge</Text> when the human and the judge
                  aggregate differ by at least 0.5 on a shared criterion. The
                  Leaderboard, Compare and Pareto pages expose the per-model{" "}
                  <Text strong>Judge Score</Text> summary, and Compare adds a dedicated
                  judge radar.
                </Paragraph>
                <Paragraph>
                  <Text strong>Stability.</Text> Across repeated attempts of one model
                  on a task, stability records deterministic metrics -{" "}
                  <Text code>scoreVariance</Text> (mean absolute deviation of the judge
                  aggregate between attempts, lower is more stable) and{" "}
                  <Text code>codeSimilarity</Text> (token-Jaccard over the artifacts) -
                  plus a judge verdict over all attempts. It shows on the task page.
                </Paragraph>
                <Paragraph>
                  <Text strong>Review.</Text> Judge scores are advisory: they never
                  overwrite the manual scores, and a human reads them in the result
                  detail alongside the human ones.
                </Paragraph>
              </Space>
            ),
          },
          {
            key: "pareto",
            label: "Pareto Explorer",
            children: (
              <Paragraph>
                Pareto charts compare two metrics at once. For quality, reliability,
                first-shot, pass rate, token speed and local viability, higher is
                better. For cost, duration and estimated LLM time, lower is better.
                Points near the better edge on both axes represent stronger trade-offs.
              </Paragraph>
            ),
          },
          {
            key: "compare-radars",
            label: "Compare page radars",
            children: (
              <Space direction="vertical">
                <Paragraph>
                  The Compare page renders three radar charts. Each axis is plotted on
                  a 0-100% scale where higher is always better.
                </Paragraph>
                <Paragraph>
                  <Text strong>Average Score per Criterion</Text> uses raw normalized
                  scores per criterion (Compliance, Works out of the box, Look, Code
                  quality, Agent logic). For each model the value on a given axis is
                  the mean of <Text code>score.value / max(scale.values)</Text> across
                  all attempts of that model on that criterion.
                </Paragraph>
                <Paragraph>
                  <Text strong>Derived Benchmark Metrics</Text> aggregates leaderboard
                  fields per model. To keep values stable when models are added or
                  removed from the selection, normalization uses fixed reference
                  points computed from the full leaderboard (all models, after global
                  filters):
                </Paragraph>
                <Space direction="vertical" size={4}>
                  <Text>
                    <Text strong>Avg Score, Pass Rate, First-shot, Reliability,
                    Local Viability</Text> — already in the 0-1 range, used as raw
                    values clamped to [0, 1].
                  </Text>
                  <Text>
                    <Text strong>Input Speed, Output Speed</Text> — higher is better.
                    Value is divided by the 95th percentile of all leaderboard rows
                    and clamped to 1: <Text code>clamp(tps / p95(tps), 0, 1)</Text>.
                    The top tier hits 100%, slower models scale linearly.
                  </Text>
                  <Text>
                    <Text strong>Avg Speed (duration), API Cost</Text> — lower is
                    better. Value is mapped against the 5th percentile floor of all
                    leaderboard rows: <Text code>clamp(p5(value) / value, 0, 1)</Text>.
                    The fastest/cheapest model hits 100%; a model 2× slower or
                    more expensive sits at 50%, 10× at 10%. Values never collapse
                    to 0 just for being above the median.
                  </Text>
                </Space>
                <Paragraph>
                  When a model has multiple environment rows in the leaderboard, the
                  per-axis value is averaged across them. An axis is dropped from the
                  chart whenever <Text strong>any</Text> selected model has no data
                  for it — partial coverage would force the missing model to 0% and
                  visually distort the polygon. In practice this means mixing cloud
                  and local models hides API Cost (null for local) and Local
                  Viability (null for cloud), so only directly comparable metrics
                  remain. Selecting different models does not change the position of
                  any other model on the radar.
                </Paragraph>
                <Paragraph>
                  <Text strong>Model Behavior by Task</Text> averages normalized
                  criterion scores per task per model. Recharts requires at least
                  three axes to draw a polygon, so with only one or two tasks the
                  chart degenerates into a line — use the Per-task Breakdown table
                  below for exact values.
                </Paragraph>
              </Space>
            ),
          },
          {
            key: "token-processing",
            label: "Token speed calculation",
            children: (
              <Space direction="vertical">
                <Paragraph>
                  LLM inference is split into prefill, where input tokens are processed
                  to build model state, and decode, where output tokens are generated
                  sequentially.
                </Paragraph>
                <Paragraph>
                  Current benchmark data stores <Text code>durationMs</Text>,{" "}
                  <Text code>tokensIn</Text> and <Text code>tokensOut</Text>, but not
                  separate TTFT/prefill/decode timings. Until those are captured, the
                  UI estimates the split from measured run time.
                </Paragraph>
                <Paragraph>
                  When both input and output tokens exist:{" "}
                  <Text code>prefillMs = durationMs * 0.2</Text> and{" "}
                  <Text code>decodeMs = durationMs * 0.8</Text>. Then{" "}
                  <Text code>input tok/s = tokensIn / (prefillMs / 1000)</Text> and{" "}
                  <Text code>output tok/s = tokensOut / (decodeMs / 1000)</Text>.
                </Paragraph>
                <Paragraph>
                  If only one token side exists, the full measured duration is assigned
                  to that side. Leaderboard values are averages of these per-attempt
                  estimates for the model and environment group.
                </Paragraph>
              </Space>
            ),
          },
        ]}
      />
    </div>
  );
}
