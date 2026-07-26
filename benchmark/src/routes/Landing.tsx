import { Typography, Card, Row, Col, Button, Spin, Empty, Statistic } from "antd";
import { Link, useNavigate } from "react-router-dom";
import { useMemo } from "react";
import { LeaderboardTable } from "@/components/tables/LeaderboardTable";
import { ParetoScatter } from "@/components/charts/ParetoScatter";
import { useTasks } from "@/data/queries";
import { useResults } from "@/data/queries";
import { leaderboard, visibleTasks } from "@/lib/stats";
import { formatDuration } from "@/lib/format";
import { applyFilters, useFilters } from "@/store/filters";

const { Title, Paragraph } = Typography;

export default function Landing() {
  const navigate = useNavigate();
  const filters = useFilters();
  const { data: tasksData, isLoading: tasksLoading } = useTasks();
  const { data: resultsData, isLoading: resultsLoading } = useResults();

  const rows = useMemo(() => {
    if (!tasksData || !resultsData) return [];
    const filtered = applyFilters(resultsData.results, filters);
    return leaderboard(filtered, resultsData, tasksData);
  }, [tasksData, resultsData, filters]);

  const paretoPoints = useMemo(
    () =>
      rows
        .filter((r) => r.environment.type === "local")
        .filter((r) => r.avgDurationMs != null && r.localViabilityScore != null)
        .map((r) => ({
          id: `${r.modelId}::${r.environmentId}`,
          x: r.avgDurationMs! / 60000,
          y: r.localViabilityScore!,
          label: r.model.name,
          provider: r.model.provider,
          attemptCount: r.attemptCount,
          environmentType: r.environment.type,
          xFormatted: formatDuration(r.avgDurationMs),
          yFormatted: `${(r.localViabilityScore! * 100).toFixed(1)}%`,
        })),
    [rows],
  );

  const heroSignals = rows.slice(0, 3);
  const bestScore = rows[0]?.avgScore ?? 0;
  const uniqueModels = new Set(rows.map((r) => r.modelId)).size;
  const evaluatedTasks = Math.max(0, ...rows.map((r) => r.tasksEvaluated));
  const totalAttempts = rows.reduce((sum, row) => sum + row.attemptCount, 0);
  const reliabilityRows = rows.filter((r) => r.reliabilityScore != null);
  const avgReliability =
    reliabilityRows.length > 0
      ? reliabilityRows.reduce((sum, row) => sum + row.reliabilityScore!, 0) /
        reliabilityRows.length
      : null;
  const firstShotRows = rows.filter((r) => r.firstShotSuccess != null);
  const firstShotSuccessRate =
    firstShotRows.length > 0
      ? firstShotRows.filter((r) => r.firstShotSuccess).length / firstShotRows.length
      : null;
  const bestLocalViability =
    rows
      .filter((r) => r.localViabilityScore != null)
      .sort((a, b) => b.localViabilityScore! - a.localViabilityScore!)[0] ?? null;
  const judgeRows = rows.filter((r) => r.judgeAvgScore != null);
  const bestJudge =
    judgeRows.slice().sort((a, b) => b.judgeAvgScore! - a.judgeAvgScore!)[0] ?? null;

  if (tasksLoading || resultsLoading) {
    return (
      <div style={{ display: "flex", justifyContent: "center", padding: 80 }}>
        <Spin size="large" />
      </div>
    );
  }

  if (!resultsData || resultsData.results.length === 0) {
    return (
      <div className="page-stack">
        <section className="hero">
          <div className="hero-copy">
            <span className="eyebrow">Refio evaluation</span>
            <Title className="hero-title" level={1}>
              benchmark.<span className="gradient-text">refio</span>
            </Title>
            <Paragraph className="hero-subtitle">
              Local LLMs evaluated head-to-head on real coding tasks.
            </Paragraph>
          </div>
        </section>
        <Empty description="No benchmark results yet. Add results via Admin > Results." />
      </div>
    );
  }

  return (
    <div className="page-stack">
      <section className="hero">
        <div className="hero-copy">
          <span className="eyebrow">Refio evaluation</span>
          <span className="jclab-line">Passion creates. Knowledge helps.</span>
          <Title className="hero-title" level={1}>
            Small tasks, <span className="gradient-text">measured.</span>
          </Title>
          <Paragraph className="hero-subtitle">
            Simple repeatable tasks for comparing local and cloud models:
            first-shot usability, reliability, speed and local viability in one benchmark cockpit.
          </Paragraph>
          <Paragraph className="hero-note">
            This is a subjective benchmark of each test result, enriched with
            statistical data collected by the Refio plugin. It is designed to compare
            models, especially local ones, on lightweight tasks where smaller models
            still have a realistic chance to produce a usable result.
          </Paragraph>
          <div className="hero-actions">
            <Button type="primary" size="large" onClick={() => navigate("/compare")}>
              Compare models
            </Button>
            <Button size="large" onClick={() => navigate("/pareto")}>
              Explore Pareto front
            </Button>
          </div>
        </div>
        <div className="hero-panel" aria-label="Top benchmark signals">
          <div className="panel-topbar">
            <span>live leaderboard</span>
            <span className="panel-dots">
              <span />
              <span />
              <span />
            </span>
          </div>
          <div className="signal-list">
            {heroSignals.map((row, index) => (
              <div className="signal-row" key={`${row.modelId}::${row.environmentId}`}>
                <div>
                  <strong>
                    #{index + 1} {row.model.name}
                  </strong>
                  <span>
                    {row.environment.name} / {row.attemptCount} attempts
                  </span>
                </div>
                <div className="signal-score">{(row.avgScore * 100).toFixed(1)}%</div>
              </div>
            ))}
          </div>
        </div>
      </section>

      <div className="metric-grid">
        <Card className="metric-card">
          <Statistic title="Best score" value={bestScore * 100} precision={1} suffix="%" />
        </Card>
        <Card className="metric-card">
          <Statistic title="Models" value={uniqueModels} />
        </Card>
        <Card className="metric-card">
          <Statistic title="Tasks covered" value={evaluatedTasks} />
        </Card>
        <Card className="metric-card">
          <Statistic title="Attempts" value={totalAttempts} />
        </Card>
        <Card className="metric-card insight-card">
          <Statistic
            title="Reliability"
            value={avgReliability == null ? 0 : avgReliability * 100}
            precision={1}
            suffix="%"
          />
          <p>Consistency across repeated attempts.</p>
        </Card>
        <Card className="metric-card insight-card">
          <Statistic
            title="First-shot success"
            value={firstShotSuccessRate == null ? 0 : firstShotSuccessRate * 100}
            precision={1}
            suffix="%"
          />
          <p>How often attempt #1 is already usable.</p>
        </Card>
        <Card className="metric-card insight-card">
          <Statistic
            title="Best judge score"
            value={bestJudge?.judgeAvgScore == null ? 0 : bestJudge.judgeAvgScore * 100}
            precision={1}
            suffix="%"
          />
          <p>
            {bestJudge
              ? `${bestJudge.model.name}, scored by strong-judge agents.`
              : "Run npm run judge to add strong-judge scores."}
          </p>
        </Card>
        <Card className="metric-card insight-card">
          <Statistic
            title="Best local viability"
            value={bestLocalViability?.localViabilityScore == null ? 0 : bestLocalViability.localViabilityScore * 100}
            precision={1}
            suffix="%"
          />
          <p>
            {bestLocalViability
              ? `${bestLocalViability.model.name} vs cloud baseline, blended with stability.`
              : "Add local and cloud runs to calculate the local viability gap."}
          </p>
        </Card>
      </div>

      <div className="section-heading">
        <div>
          <Title level={2}>Leaderboard</Title>
          <p>
            Ranked model and environment combinations with score, pass-rate, cost and
            runtime context.
          </p>
        </div>
        <Button type="link" onClick={() => navigate("/compare")}>
          Compare models
        </Button>
      </div>

      <Row gutter={[24, 24]}>
        <Col span={24}>
          <Card className="glass-card">
            <LeaderboardTable />
          </Card>
        </Col>

        {paretoPoints.length >= 2 && (
          <Col span={24}>
            <Card
              className="glass-card chart-card"
              title="Local Pareto: Viability vs Avg Runtime"
              extra={
                <Button type="link" onClick={() => navigate("/pareto")}>
                  Full view
                </Button>
              }
            >
              <ParetoScatter
                points={paretoPoints}
                height={320}
                mini
                xLabel="Avg Duration"
                yLabel="Local Viability"
              />
            </Card>
          </Col>
        )}
      </Row>

      {tasksData && visibleTasks(tasksData.tasks).length > 0 && (
        <Card className="glass-card" title="Tasks">
          <div className="task-link-grid">
            {visibleTasks(tasksData.tasks).map((task) => (
              <Link className="task-link-card" key={task.id} to={`/tasks/${task.id}`}>
                <span>{task.name}</span>
                <small>{task.id}</small>
              </Link>
            ))}
          </div>
        </Card>
      )}
    </div>
  );
}
