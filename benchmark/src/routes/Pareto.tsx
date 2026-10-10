import { useMemo, useState } from "react";
import {
  Typography,
  Card,
  Space,
  Switch,
  Empty,
  Spin,
  Row,
  Col,
  Statistic,
  Select,
} from "antd";
import { useTasks } from "@/data/queries";
import { useResults } from "@/data/queries";
import { useFilters, applyFilters } from "@/store/filters";
import { leaderboard, localRowsOnly, withRefioMode, type LeaderboardRow } from "@/lib/stats";
import { useRefioMode } from "@/store/refioMode";
import { ParetoScatter } from "@/components/charts/ParetoScatter";
import { formatCost, formatDuration, formatTokensPerSecond } from "@/lib/format";
import { useT, type Translate } from "@/i18n/LanguageProvider";
import type { MessageKey } from "@/i18n/messages";

const { Title, Text } = Typography;

type MetricId =
  | "quality"
  | "refioScore"
  | "judgeScore"
  | "cost"
  | "duration"
  | "estimatedLlm"
  | "prefillSpeed"
  | "decodeSpeed"
  | "reliability"
  | "firstShot"
  | "localViability"
  | "passRate"
  | "attempts";

interface MetricDefinition {
  id: MetricId;
  label: MessageKey;
  higherIsBetter: boolean;
  getValue: (row: LeaderboardRow) => number | null;
  format: (value: number, t: Translate) => string;
}

const METRICS: Record<MetricId, MetricDefinition> = {
  quality: {
    id: "quality",
    label: "pareto.metricQuality",
    higherIsBetter: true,
    getValue: (row) => row.avgScore,
    format: (value) => `${(value * 100).toFixed(1)}%`,
  },
  refioScore: {
    id: "refioScore",
    label: "pareto.metricRefioScore",
    higherIsBetter: true,
    getValue: (row) => row.refioScore,
    format: (value) => `${(value * 100).toFixed(1)}%`,
  },
  judgeScore: {
    id: "judgeScore",
    label: "pareto.metricJudgeScore",
    higherIsBetter: true,
    getValue: (row) => row.judgeAvgScore,
    format: (value) => `${(value * 100).toFixed(1)}%`,
  },
  cost: {
    id: "cost",
    label: "pareto.metricCost",
    higherIsBetter: false,
    getValue: (row) => row.avgCostUsd,
    format: (value) => formatCost(value),
  },
  duration: {
    id: "duration",
    label: "pareto.metricDuration",
    higherIsBetter: false,
    getValue: (row) => (row.avgDurationMs == null ? null : row.avgDurationMs / 60000),
    format: (value, t) => t("pareto.minutes", { value: value.toFixed(1) }),
  },
  estimatedLlm: {
    id: "estimatedLlm",
    label: "pareto.metricEstimatedLlm",
    higherIsBetter: false,
    getValue: (row) =>
      row.avgEstimatedLlmMs == null ? null : row.avgEstimatedLlmMs / 60000,
    format: (value, t) => t("pareto.minutes", { value: value.toFixed(1) }),
  },
  prefillSpeed: {
    id: "prefillSpeed",
    label: "pareto.metricPrefillSpeed",
    higherIsBetter: true,
    getValue: (row) => row.avgPrefillTokensPerSecond,
    format: (value) => formatTokensPerSecond(value),
  },
  decodeSpeed: {
    id: "decodeSpeed",
    label: "pareto.metricDecodeSpeed",
    higherIsBetter: true,
    getValue: (row) => row.avgDecodeTokensPerSecond,
    format: (value) => formatTokensPerSecond(value),
  },
  reliability: {
    id: "reliability",
    label: "pareto.metricReliability",
    higherIsBetter: true,
    getValue: (row) => row.reliabilityScore,
    format: (value) => `${(value * 100).toFixed(1)}%`,
  },
  firstShot: {
    id: "firstShot",
    label: "pareto.metricFirstShot",
    higherIsBetter: true,
    getValue: (row) => row.firstShotScore,
    format: (value) => `${(value * 100).toFixed(1)}%`,
  },
  localViability: {
    id: "localViability",
    label: "pareto.metricLocalViability",
    higherIsBetter: true,
    getValue: (row) => row.localViabilityScore,
    format: (value) => `${(value * 100).toFixed(1)}%`,
  },
  passRate: {
    id: "passRate",
    label: "pareto.metricPassRate",
    higherIsBetter: true,
    getValue: (row) => row.passRate,
    format: (value) => `${(value * 100).toFixed(1)}%`,
  },
  attempts: {
    id: "attempts",
    label: "pareto.metricAttempts",
    higherIsBetter: true,
    getValue: (row) => row.attemptCount,
    format: (value) => value.toFixed(0),
  },
};

export default function Pareto() {
  const t = useT();
  const [xMetricId, setXMetricId] = useState<MetricId>("duration");
  const [yMetricId, setYMetricId] = useState<MetricId>("localViability");
  const [localOnly, setLocalOnly] = useState(true);
  const filters = useFilters();
  const { data: tasksData, isLoading: tasksLoading } = useTasks();
  const { data: resultsData, isLoading: resultsLoading } = useResults();

  const refioMode = useRefioMode((s) => s.mode);
  const rows = useMemo(() => {
    if (!tasksData || !resultsData) return [];
    const filtered = applyFilters(resultsData.results, { ...filters, localOnly: false });
    const all = leaderboard(filtered, resultsData, tasksData);
    // The leader is picked after the header's local-only cut, as on the leaderboard,
    // and before this chart's own local-only switch.
    const lb = withRefioMode(localRowsOnly(all, filters.localOnly), refioMode);
    if (localOnly) return lb.filter((r) => r.environment.type === "local");
    return lb;
  }, [tasksData, resultsData, filters, localOnly, refioMode]);

  const xMetric = METRICS[xMetricId];
  const yMetric = METRICS[yMetricId];

  const points = useMemo(
    () =>
      rows.flatMap((row) => {
        const x = xMetric.getValue(row);
        const y = yMetric.getValue(row);
        if (x == null || y == null) return [];
        return [
          {
            id: `${row.modelId}::${row.environmentId}::${row.harnessId}`,
            x,
            y,
            label:
              row.harness.kind === "external"
                ? `${row.model.name} (${row.harness.name})`
                : `${row.model.name} (${row.environment.name})`,
            provider: row.model.provider,
            attemptCount: row.attemptCount,
            environmentType: row.environment.type,
            xFormatted: xMetric.format(x, t),
            yFormatted: yMetric.format(y, t),
          },
        ];
      }),
    [rows, xMetric, yMetric, t],
  );

  if (tasksLoading || resultsLoading) {
    return (
      <div style={{ display: "flex", justifyContent: "center", padding: 80 }}>
        <Spin size="large" />
      </div>
    );
  }

  const xLabel = t(xMetric.label);
  const yLabel = t(yMetric.label);
  const title = t("pareto.chartTitle", { x: xLabel, y: yLabel });
  const metricOptions = Object.values(METRICS).map((metric) => ({
    label: t(metric.label),
    value: metric.id,
  }));

  // An external agent bills through a subscription, so any cost it reports is a
  // per-token estimate, not what the run was charged. Saying so beats a chart that
  // silently mixes two different kinds of number.
  const externalOnChart = rows.some((r) => r.harness.kind === "external");
  const costAxisInUse = xMetricId === "cost" || yMetricId === "cost";

  return (
    <div className="page-stack">
      <div className="section-heading">
        <div>
          <Title level={2}>{t("pareto.title")}</Title>
          <p>{t("pareto.intro")}</p>
          {externalOnChart && costAxisInUse && (
            <p>
              <Text type="warning">{t("pareto.externalCostWarning")}</Text>
            </p>
          )}
        </div>
      </div>

      <Row gutter={[16, 16]}>
        <Col xs={24} md={8}>
          <Card size="small" className="glass-card">
            <Space direction="vertical" style={{ width: "100%" }}>
              <Text>{t("pareto.xAxis")}</Text>
              <Select
                value={xMetricId}
                options={metricOptions}
                onChange={(value) => setXMetricId(value)}
                style={{ width: "100%" }}
              />
            </Space>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" className="glass-card">
            <Space direction="vertical" style={{ width: "100%" }}>
              <Text>{t("pareto.yAxis")}</Text>
              <Select
                value={yMetricId}
                options={metricOptions}
                onChange={(value) => setYMetricId(value)}
                style={{ width: "100%" }}
              />
            </Space>
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card size="small" className="glass-card">
            <Space style={{ minHeight: 54 }}>
              <Text>{t("pareto.localOnly")}</Text>
              <Switch checked={localOnly} onChange={setLocalOnly} size="small" />
            </Space>
          </Card>
        </Col>
      </Row>

      {points.length < 2 ? (
        <Empty description={t("pareto.empty")} />
      ) : (
        <Card className="glass-card chart-card" title={title}>
          <ParetoScatter
            points={points}
            xLabel={xLabel}
            yLabel={yLabel}
            higherYIsBetter={yMetric.higherIsBetter}
            lowerXIsBetter={!xMetric.higherIsBetter}
          />
        </Card>
      )}

      {rows.length > 0 && (
        <Row gutter={[16, 16]}>
          {rows.slice(0, 4).map((r) => (
            <Col key={`${r.modelId}::${r.environmentId}`} xs={24} sm={12} md={6}>
              <Card size="small" className="glass-card">
                <Statistic
                  title={`${r.model.name}`}
                  value={(r.avgScore * 100).toFixed(1)}
                  suffix="%"
                  precision={1}
                />
                <div style={{ marginTop: 4, fontSize: 12, color: "var(--muted)" }}>
                  {r.avgCostUsd != null && <div>{t("pareto.statAvgCost", { value: formatCost(r.avgCostUsd) })}</div>}
                  {r.avgDurationMs != null && <div>{t("pareto.statAvgDuration", { value: formatDuration(r.avgDurationMs) })}</div>}
                  {r.avgEstimatedLlmMs != null && (
                    <div>{t("pareto.statLlmEst", { value: formatDuration(r.avgEstimatedLlmMs) })}</div>
                  )}
                  {r.reliabilityScore != null && (
                    <div>{t("pareto.statReliability", { value: `${(r.reliabilityScore * 100).toFixed(1)}%` })}</div>
                  )}
                  {r.judgeAvgScore != null && (
                    <div>{t("pareto.statJudgeScore", { value: `${(r.judgeAvgScore * 100).toFixed(1)}%` })}</div>
                  )}
                  <div>{r.environment.name}</div>
                </div>
              </Card>
            </Col>
          ))}
        </Row>
      )}
    </div>
  );
}
