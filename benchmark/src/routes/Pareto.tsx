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
import { leaderboard, type LeaderboardRow } from "@/lib/stats";
import { ParetoScatter } from "@/components/charts/ParetoScatter";
import { formatCost, formatDuration, formatTokensPerSecond } from "@/lib/format";

const { Title, Text } = Typography;

type MetricId =
  | "quality"
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
  label: string;
  higherIsBetter: boolean;
  getValue: (row: LeaderboardRow) => number | null;
  format: (value: number) => string;
}

const METRICS: Record<MetricId, MetricDefinition> = {
  quality: {
    id: "quality",
    label: "Avg Quality",
    higherIsBetter: true,
    getValue: (row) => row.avgScore,
    format: (value) => `${(value * 100).toFixed(1)}%`,
  },
  judgeScore: {
    id: "judgeScore",
    label: "Judge Score",
    higherIsBetter: true,
    getValue: (row) => row.judgeAvgScore,
    format: (value) => `${(value * 100).toFixed(1)}%`,
  },
  cost: {
    id: "cost",
    label: "Avg API Cost",
    higherIsBetter: false,
    getValue: (row) => row.avgCostUsd,
    format: (value) => formatCost(value),
  },
  duration: {
    id: "duration",
    label: "Avg Duration",
    higherIsBetter: false,
    getValue: (row) => (row.avgDurationMs == null ? null : row.avgDurationMs / 60000),
    format: (value) => `${value.toFixed(1)}m`,
  },
  estimatedLlm: {
    id: "estimatedLlm",
    label: "Est. LLM Time",
    higherIsBetter: false,
    getValue: (row) =>
      row.avgEstimatedLlmMs == null ? null : row.avgEstimatedLlmMs / 60000,
    format: (value) => `${value.toFixed(1)}m`,
  },
  prefillSpeed: {
    id: "prefillSpeed",
    label: "Prefill Speed",
    higherIsBetter: true,
    getValue: (row) => row.avgPrefillTokensPerSecond,
    format: (value) => formatTokensPerSecond(value),
  },
  decodeSpeed: {
    id: "decodeSpeed",
    label: "Decode Speed",
    higherIsBetter: true,
    getValue: (row) => row.avgDecodeTokensPerSecond,
    format: (value) => formatTokensPerSecond(value),
  },
  reliability: {
    id: "reliability",
    label: "Reliability",
    higherIsBetter: true,
    getValue: (row) => row.reliabilityScore,
    format: (value) => `${(value * 100).toFixed(1)}%`,
  },
  firstShot: {
    id: "firstShot",
    label: "First-shot",
    higherIsBetter: true,
    getValue: (row) => row.firstShotScore,
    format: (value) => `${(value * 100).toFixed(1)}%`,
  },
  localViability: {
    id: "localViability",
    label: "Local Viability",
    higherIsBetter: true,
    getValue: (row) => row.localViabilityScore,
    format: (value) => `${(value * 100).toFixed(1)}%`,
  },
  passRate: {
    id: "passRate",
    label: "Pass Rate",
    higherIsBetter: true,
    getValue: (row) => row.passRate,
    format: (value) => `${(value * 100).toFixed(1)}%`,
  },
  attempts: {
    id: "attempts",
    label: "Attempts",
    higherIsBetter: true,
    getValue: (row) => row.attemptCount,
    format: (value) => value.toFixed(0),
  },
};

const metricOptions = Object.values(METRICS).map((metric) => ({
  label: metric.label,
  value: metric.id,
}));

export default function Pareto() {
  const [xMetricId, setXMetricId] = useState<MetricId>("duration");
  const [yMetricId, setYMetricId] = useState<MetricId>("localViability");
  const [localOnly, setLocalOnly] = useState(true);
  const filters = useFilters();
  const { data: tasksData, isLoading: tasksLoading } = useTasks();
  const { data: resultsData, isLoading: resultsLoading } = useResults();

  const rows = useMemo(() => {
    if (!tasksData || !resultsData) return [];
    const filtered = applyFilters(resultsData.results, filters);
    const lb = leaderboard(filtered, resultsData, tasksData);
    if (localOnly) return lb.filter((r) => r.environment.type === "local");
    return lb;
  }, [tasksData, resultsData, filters, localOnly]);

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
            id: `${row.modelId}::${row.environmentId}`,
            x,
            y,
            label: `${row.model.name} (${row.environment.name})`,
            provider: row.model.provider,
            attemptCount: row.attemptCount,
            environmentType: row.environment.type,
            xFormatted: xMetric.format(x),
            yFormatted: yMetric.format(y),
          },
        ];
      }),
    [rows, xMetric, yMetric],
  );

  if (tasksLoading || resultsLoading) {
    return (
      <div style={{ display: "flex", justifyContent: "center", padding: 80 }}>
        <Spin size="large" />
      </div>
    );
  }

  const title = `${yMetric.label} vs ${xMetric.label}`;

  return (
    <div className="page-stack">
      <div className="section-heading">
        <div>
          <Title level={2}>Pareto Explorer</Title>
          <p>
            Compare trade-offs across local viability, speed, quality, first-shot
            success, reliability and cloud/API cost.
          </p>
        </div>
      </div>

      <Row gutter={[16, 16]}>
        <Col xs={24} md={8}>
          <Card size="small" className="glass-card">
            <Space direction="vertical" style={{ width: "100%" }}>
              <Text>X axis</Text>
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
              <Text>Y axis</Text>
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
              <Text>Local only:</Text>
              <Switch checked={localOnly} onChange={setLocalOnly} size="small" />
            </Space>
          </Card>
        </Col>
      </Row>

      {points.length < 2 ? (
        <Empty description="Not enough data for this metric pair. Add more results or choose different axes." />
      ) : (
        <Card className="glass-card chart-card" title={title}>
          <ParetoScatter
            points={points}
            xLabel={xMetric.label}
            yLabel={yMetric.label}
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
                  {r.avgCostUsd != null && <div>Avg cost: {formatCost(r.avgCostUsd)}</div>}
                  {r.avgDurationMs != null && <div>Avg: {formatDuration(r.avgDurationMs)}</div>}
                  {r.avgEstimatedLlmMs != null && (
                    <div>LLM est: {formatDuration(r.avgEstimatedLlmMs)}</div>
                  )}
                  {r.reliabilityScore != null && (
                    <div>Reliability: {(r.reliabilityScore * 100).toFixed(1)}%</div>
                  )}
                  {r.judgeAvgScore != null && (
                    <div>Judge score: {(r.judgeAvgScore * 100).toFixed(1)}%</div>
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
