import { Card, Col, Collapse, Row, Space, Tag, Typography } from "antd";
import { useT } from "@/i18n/LanguageProvider";
import type { MessageKey } from "@/i18n/messages";

const { Title, Paragraph, Text } = Typography;

const leaderboardMetrics: { name: MessageKey; formula: string; description: MessageKey }[] = [
  {
    name: "help.metricRefioScoreName",
    formula: "(2 * Avg + Judge) / 3 * (0.8 + 0.2 * Stability)",
    description: "help.metricRefioScoreDesc",
  },
  {
    name: "help.metricAvgScoreName",
    formula: "average(normalized criterion scores)",
    description: "help.metricAvgScoreDesc",
  },
  {
    name: "help.metricJudgeScoreName",
    formula: "average(weighted-normalized judge aggregate per result)",
    description: "help.metricJudgeScoreDesc",
  },
  {
    name: "help.metricPassRateName",
    formula: "passing attempts / all attempts",
    description: "help.metricPassRateDesc",
  },
  {
    name: "help.metricFirstShotName",
    formula: "score of attempt #1",
    description: "help.metricFirstShotDesc",
  },
  {
    name: "help.metricReliabilityName",
    formula: "1 - standardDeviation(scores) / 0.5",
    description: "help.metricReliabilityDesc",
  },
  {
    name: "help.metricAvgStabilityName",
    formula: "mean(score consistency, code similarity, median judge verdict)",
    description: "help.metricAvgStabilityDesc",
  },
  {
    name: "help.metricLocalViabilityName",
    formula: "localQualityRatio * 0.7 + stability * 0.3",
    description: "help.metricLocalViabilityDesc",
  },
  {
    name: "help.metricAvgDurationName",
    formula: "average(duration) in seconds",
    description: "help.metricAvgDurationDesc",
  },
  {
    name: "help.metricLlmEstName",
    formula: "duration split into 20% prefill and 80% decode",
    description: "help.metricLlmEstDesc",
  },
  {
    name: "help.metricTokenSpeedName",
    formula: "tokensIn / estimated prefill time, tokensOut / estimated decode time",
    description: "help.metricTokenSpeedDesc",
  },
  {
    name: "help.metricAvgApiCostName",
    formula: "average(costUsd)",
    description: "help.metricAvgApiCostDesc",
  },
];

const resultFields: { name: MessageKey; description: MessageKey }[] = [
  { name: "help.fieldTaskName", description: "help.fieldTaskDesc" },
  { name: "help.fieldModelName", description: "help.fieldModelDesc" },
  { name: "help.fieldEnvironmentName", description: "help.fieldEnvironmentDesc" },
  { name: "help.fieldAttemptName", description: "help.fieldAttemptDesc" },
  { name: "help.fieldTokensName", description: "help.fieldTokensDesc" },
  { name: "help.fieldAttachmentsName", description: "help.fieldAttachmentsDesc" },
];

export default function Help() {
  const t = useT();
  return (
    <div className="page-stack">
      <div className="section-heading">
        <div>
          <Title level={2}>{t("help.title")}</Title>
          <Paragraph>{t("help.intro")}</Paragraph>
        </div>
      </div>

      <Row gutter={[16, 16]}>
        {leaderboardMetrics.map((metric) => (
          <Col key={metric.name} xs={24} md={12} xl={8}>
            <Card className="glass-card" title={t(metric.name)}>
              <Space direction="vertical" size="small">
                <Text>{t(metric.description)}</Text>
                <Tag color="blue">{metric.formula}</Tag>
              </Space>
            </Card>
          </Col>
        ))}
      </Row>

      <Card className="glass-card" title={t("help.normTitle")}>
        <Paragraph>
          {t("help.normP1a")}
          <Text code>tasks.json</Text>
          {t("help.normP1b")}
          <Text code>value / max(scale.values)</Text>
          {t("help.normP1c")}
        </Paragraph>
        <Paragraph>{t("help.normP2")}</Paragraph>
        <Paragraph>
          {t("help.normP3a")}
          <Text strong>{t("help.metricAvgScoreName")}</Text>
          {t("help.normP3b")}
          <Text code>works_out_of_box</Text>
          {t("help.normP3c")}
          <Text code>compliance</Text>
          {t("help.normP3d")}
          <Text strong>{t("help.metricFirstShotName")}</Text>
          {t("help.normP3e")}
        </Paragraph>
      </Card>

      <Collapse
        items={[
          {
            key: "fields",
            label: t("help.fieldsLabel"),
            children: (
              <Space direction="vertical" style={{ width: "100%" }}>
                {resultFields.map((field) => (
                  <div key={field.name}>
                    <Text strong>{t(field.name)}: </Text>
                    <Text>{t(field.description)}</Text>
                  </div>
                ))}
              </Space>
            ),
          },
          {
            key: "reference-track",
            label: t("help.agentsLabel"),
            children: (
              <Space direction="vertical">
                <Paragraph>
                  {t("help.agentsP1a")}
                  <Text strong>{t("help.agentsP1Harness")}</Text>
                  {t("help.agentsP1b")}
                  <Text code>refio</Text>
                  {t("help.agentsP1c")}
                </Paragraph>
                <Paragraph>{t("help.agentsP2")}</Paragraph>
                <Paragraph>
                  {t("help.agentsP3a")}
                  <Text code>ollama/</Text>
                  {t("help.agentsP3b")}
                </Paragraph>
                <Paragraph>
                  <Text strong>{t("help.agentsP4Trace")}</Text>
                  {t("help.agentsP4a")}
                  <Text strong>{t("help.agentsP4SelfCheck")}</Text>
                  {t("help.agentsP4b")}
                </Paragraph>
                <Paragraph>
                  {t("help.agentsP5a")}
                  <Text strong>agent_logic</Text>
                  {t("help.agentsP5b")}
                  <Text strong>{t("help.agentsP5Cost")}</Text>
                  {t("help.agentsP5c")}
                </Paragraph>
                <Paragraph>{t("help.agentsP6")}</Paragraph>
              </Space>
            ),
          },
          {
            key: "strong-judge",
            label: t("help.judgeLabel"),
            children: (
              <Space direction="vertical">
                <Paragraph>
                  {t("help.judgeP1a")}
                  <Text strong>{t("help.judgeP1Agents")}</Text>
                  {t("help.judgeP1b")}
                  <Text code>npm run judge</Text>
                  {t("help.judgeP1c")}
                </Paragraph>
                <Paragraph>
                  <Text strong>{t("help.judgeP2Criteria")}</Text>
                  {t("help.judgeP2a")}
                  <Text code>code_structure</Text>
                  {t("help.judgeP2b")}
                  <Text code>logic_correctness</Text>
                  {t("help.judgeP2c")}
                  <Text code>agent_logic</Text>
                  {t("help.judgeP2d")}
                  <Text strong>{t("help.judgeP2Not")}</Text>
                  {t("help.judgeP2e")}
                </Paragraph>
                <Paragraph>
                  <Text strong>{t("help.judgeP3Title")}</Text>
                  {t("help.judgeP3a")}
                  <Text strong>{t("help.judgeP3AutoColumn")}</Text>
                  {t("help.judgeP3b")}
                  <Text strong>{t("help.judgeP3Badge")}</Text>
                  {t("help.judgeP3c")}
                  <Text strong>{t("help.metricJudgeScoreName")}</Text>
                  {t("help.judgeP3d")}
                </Paragraph>
                <Paragraph>
                  <Text strong>{t("help.judgeP4Title")}</Text>
                  {t("help.judgeP4a")}
                  <Text code>scoreVariance</Text>
                  {t("help.judgeP4b")}
                  <Text code>codeSimilarity</Text>
                  {t("help.judgeP4c")}
                </Paragraph>
                <Paragraph>
                  <Text strong>{t("help.judgeP5Title")}</Text>
                  {t("help.judgeP5")}
                </Paragraph>
              </Space>
            ),
          },
          {
            key: "stability",
            label: t("help.stabilityLabel"),
            children: (
              <Space direction="vertical">
                <Paragraph>{t("help.stabilityP1")}</Paragraph>
                <Paragraph>{t("help.stabilityP2")}</Paragraph>
                <Space direction="vertical" size={4}>
                  <Text>
                    <Text strong>{t("help.stabilityScoreTitle")}</Text>
                    {t("help.stabilityScoreA")}
                    <Text code>clamp(1 - scoreVariance / 3, 0, 1)</Text>
                    {t("help.stabilityScoreB")}
                    <Text code>scoreVariance</Text>
                    {t("help.stabilityScoreC")}
                  </Text>
                  <Text>
                    <Text strong>{t("help.stabilityCodeTitle")}</Text>
                    {t("help.stabilityCode")}
                  </Text>
                  <Text>
                    <Text strong>{t("help.stabilityVerdictTitle")}</Text>
                    {t("help.stabilityVerdictA")}
                    <Text code>1</Text>
                    {t("help.stabilityVerdictB")}
                    <Text code>0.5</Text>
                    {t("help.stabilityVerdictC")}
                    <Text code>0</Text>
                    {t("help.stabilityVerdictD")}
                  </Text>
                </Space>
                <Paragraph>
                  <Text strong>{t("help.stabilityOverallTitle")}</Text>
                  {t("help.stabilityOverallA")}
                  <Text strong>{t("help.metricAvgStabilityName")}</Text>
                  {t("help.stabilityOverallB")}
                </Paragraph>
                <Paragraph>
                  <Text strong>{t("help.stabilityVsTitle")}</Text>
                  {t("help.stabilityVs")}
                </Paragraph>
                <Paragraph>
                  <Text strong>{t("help.stabilityPageTitle")}</Text>
                  {t("help.stabilityPage")}
                </Paragraph>
              </Space>
            ),
          },
          {
            key: "pareto",
            label: t("help.paretoLabel"),
            children: <Paragraph>{t("help.paretoP")}</Paragraph>,
          },
          {
            key: "compare-radars",
            label: t("help.radarsLabel"),
            children: (
              <Space direction="vertical">
                <Paragraph>{t("help.radarsP1")}</Paragraph>
                <Paragraph>
                  <Text strong>{t("help.radarsCriterionTitle")}</Text>
                  {t("help.radarsCriterionA")}
                  <Text code>score.value / max(scale.values)</Text>
                  {t("help.radarsCriterionB")}
                </Paragraph>
                <Paragraph>
                  <Text strong>{t("help.radarsDerivedTitle")}</Text>
                  {t("help.radarsDerived")}
                </Paragraph>
                <Space direction="vertical" size={4}>
                  <Text>
                    <Text strong>{t("help.radarsRatioTitle")}</Text>
                    {t("help.radarsRatio")}
                  </Text>
                  <Text>
                    <Text strong>{t("help.radarsSpeedTitle")}</Text>
                    {t("help.radarsSpeedA")}
                    <Text code>clamp(tps / p95(tps), 0, 1)</Text>
                    {t("help.radarsSpeedB")}
                  </Text>
                  <Text>
                    <Text strong>{t("help.radarsCostTitle")}</Text>
                    {t("help.radarsCostA")}
                    <Text code>clamp(p5(value) / value, 0, 1)</Text>
                    {t("help.radarsCostB")}
                  </Text>
                </Space>
                <Paragraph>
                  {t("help.radarsAxisA")}
                  <Text strong>{t("help.radarsAxisAny")}</Text>
                  {t("help.radarsAxisB")}
                </Paragraph>
                <Paragraph>
                  <Text strong>{t("help.radarsTaskTitle")}</Text>
                  {t("help.radarsTask")}
                </Paragraph>
              </Space>
            ),
          },
          {
            key: "token-processing",
            label: t("help.tokenLabel"),
            children: (
              <Space direction="vertical">
                <Paragraph>{t("help.tokenP1")}</Paragraph>
                <Paragraph>
                  {t("help.tokenP2a")}
                  <Text code>durationMs</Text>
                  {", "}
                  <Text code>tokensIn</Text>
                  {t("help.tokenP2b")}
                  <Text code>tokensOut</Text>
                  {t("help.tokenP2c")}
                </Paragraph>
                <Paragraph>
                  {t("help.tokenP3a")}
                  <Text code>prefillMs = durationMs * 0.2</Text>
                  {t("help.tokenP3b")}
                  <Text code>decodeMs = durationMs * 0.8</Text>
                  {t("help.tokenP3c")}
                  <Text code>input tok/s = tokensIn / (prefillMs / 1000)</Text>
                  {t("help.tokenP3b")}
                  <Text code>output tok/s = tokensOut / (decodeMs / 1000)</Text>
                  {t("help.tokenP3d")}
                </Paragraph>
                <Paragraph>{t("help.tokenP4")}</Paragraph>
              </Space>
            ),
          },
        ]}
      />
    </div>
  );
}
