import { Typography, Card, Row, Col, Button, Space, Spin, Empty, Statistic } from "antd";
import { Link, useNavigate } from "react-router-dom";
import { useMemo } from "react";
import { LeaderboardTable } from "@/components/tables/LeaderboardTable";
import { ParetoScatter } from "@/components/charts/ParetoScatter";
import { useTasks } from "@/data/queries";
import { useResults } from "@/data/queries";
import { leaderboard, localRowsOnly, visibleTasks, withRefioMode } from "@/lib/stats";
import { formatDuration } from "@/lib/format";
import { applyFilters, useFilters } from "@/store/filters";
import { useRefioMode } from "@/store/refioMode";
import { useT } from "@/i18n/LanguageProvider";

const { Title, Paragraph } = Typography;

export default function Landing() {
  const navigate = useNavigate();
  const filters = useFilters();
  const t = useT();
  const { data: tasksData, isLoading: tasksLoading } = useTasks();
  const { data: resultsData, isLoading: resultsLoading } = useResults();

  const hasExternalAgents = (resultsData?.harnesses ?? []).some((h) => h.kind === "external");

  const refioMode = useRefioMode((s) => s.mode);
  const rows = useMemo(() => {
    if (!tasksData || !resultsData) return [];
    const filtered = applyFilters(resultsData.results, { ...filters, localOnly: false });
    const rows = localRowsOnly(leaderboard(filtered, resultsData, tasksData), filters.localOnly);
    return withRefioMode(rows, refioMode);
  }, [tasksData, resultsData, filters, refioMode]);

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

  // The hero shows the same order as the leaderboard below: by Refio Score.
  const heroSignals = rows
    .filter((r) => r.refioScore != null)
    .sort((a, b) => b.refioScore! - a.refioScore!)
    .slice(0, 3);
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
  const bestRefio =
    rows
      .filter((r) => r.refioScore != null)
      .sort((a, b) => b.refioScore! - a.refioScore!)[0] ?? null;
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
            <span className="eyebrow">{t("landing.eyebrow")}</span>
            <Title className="hero-title" level={1}>
              benchmark.<span className="gradient-text">refio</span>
            </Title>
            <Paragraph className="hero-subtitle">
              {t("landing.emptySubtitle")}
            </Paragraph>
          </div>
        </section>
        <Empty description={t("landing.emptyResults")} />
      </div>
    );
  }

  return (
    <div className="page-stack">
      <section className="hero">
        <div className="hero-copy">
          <span className="eyebrow">{t("landing.eyebrow")}</span>
          <span className="jclab-line">{t("landing.motto")}</span>
          <Title className="hero-title" level={1}>
            {t("landing.heroTitleStart")}
            <span className="gradient-text">{t("landing.heroTitleAccent")}</span>
          </Title>
          <Paragraph className="hero-subtitle">
            {t("landing.heroSubtitle")}
          </Paragraph>
          <Paragraph className="hero-note">
            {t("landing.heroNote")}
          </Paragraph>
          <div className="hero-actions">
            <Button type="primary" size="large" onClick={() => navigate("/compare")}>
              {t("landing.compareModels")}
            </Button>
            <Button size="large" onClick={() => navigate("/pareto")}>
              {t("landing.explorePareto")}
            </Button>
          </div>
        </div>
        <div className="hero-panel" aria-label={t("landing.topSignals")}>
          <div className="panel-topbar">
            <span>{t("landing.liveLeaderboard")}</span>
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
                    {t("landing.signalAttempts", { env: row.environment.name, count: row.attemptCount })}
                  </span>
                </div>
                <div className="signal-score">{(row.refioScore! * 100).toFixed(1)}%</div>
              </div>
            ))}
          </div>
        </div>
      </section>

      <div className="metric-grid">
        <Card className="metric-card insight-card">
          <Statistic
            title={t("landing.bestRefio")}
            value={bestRefio?.refioScore == null ? 0 : bestRefio.refioScore * 100}
            precision={1}
            suffix="%"
          />
          <p>
            {bestRefio
              ? t("landing.bestRefioNote", { model: bestRefio.model.name })
              : t("landing.bestRefioEmpty")}
          </p>
        </Card>
        <Card className="metric-card insight-card">
          <Statistic title={t("landing.bestScore")} value={bestScore * 100} precision={1} suffix="%" />
          <p>{t("landing.bestScoreNote")}</p>
        </Card>
        <Card className="metric-card insight-card">
          <Statistic
            title={t("landing.reliability")}
            value={avgReliability == null ? 0 : avgReliability * 100}
            precision={1}
            suffix="%"
          />
          <p>{t("landing.reliabilityNote")}</p>
        </Card>
        <Card className="metric-card insight-card">
          <Statistic
            title={t("landing.firstShot")}
            value={firstShotSuccessRate == null ? 0 : firstShotSuccessRate * 100}
            precision={1}
            suffix="%"
          />
          <p>{t("landing.firstShotNote")}</p>
        </Card>
        <Card className="metric-card insight-card">
          <Statistic
            title={t("landing.bestJudge")}
            value={bestJudge?.judgeAvgScore == null ? 0 : bestJudge.judgeAvgScore * 100}
            precision={1}
            suffix="%"
          />
          <p>
            {bestJudge
              ? t("landing.bestJudgeNote", { model: bestJudge.model.name })
              : t("landing.bestJudgeEmpty")}
          </p>
        </Card>
        <Card className="metric-card insight-card">
          <Statistic
            title={t("landing.bestLocal")}
            value={bestLocalViability?.localViabilityScore == null ? 0 : bestLocalViability.localViabilityScore * 100}
            precision={1}
            suffix="%"
          />
          <p>
            {bestLocalViability
              ? t("landing.bestLocalNote", { model: bestLocalViability.model.name })
              : t("landing.bestLocalEmpty")}
          </p>
        </Card>
      </div>

      <div className="section-heading">
        <div>
          <Title level={2}>{t("landing.leaderboardTitle")}</Title>
          <p>
            {t("landing.leaderboardIntro")}{" "}
            {t("landing.leaderboardCounts", {
              models: uniqueModels,
              tasks: evaluatedTasks,
              attempts: totalAttempts,
            })}
          </p>
        </div>
        <Button type="link" onClick={() => navigate("/compare")}>
          {t("landing.compareModels")}
        </Button>
      </div>

      <Row gutter={[24, 24]}>
        <Col span={24}>
          <Card className="glass-card">
            <LeaderboardTable />
          </Card>
        </Col>

        {hasExternalAgents && (
          <Col span={24}>
            <Card className="glass-card">
              <Space direction="vertical" size={4}>
                <Title level={4} style={{ margin: 0 }}>
                  {t("landing.externalTitle")}
                </Title>
                <p style={{ margin: 0 }}>
                  {t("landing.externalBody")}
                </p>
                <Link to="/agents">{t("landing.externalLink")}</Link>
              </Space>
            </Card>
          </Col>
        )}

        {paretoPoints.length >= 2 && (
          <Col span={24}>
            <Card
              className="glass-card chart-card"
              title={t("landing.paretoTitle")}
              extra={
                <Button type="link" onClick={() => navigate("/pareto")}>
                  {t("landing.fullView")}
                </Button>
              }
            >
              <ParetoScatter
                points={paretoPoints}
                height={320}
                mini
                xLabel={t("landing.axisDuration")}
                yLabel={t("landing.axisViability")}
              />
            </Card>
          </Col>
        )}
      </Row>

      {tasksData && visibleTasks(tasksData.tasks).length > 0 && (
        <Card className="glass-card" title={t("landing.tasksTitle")}>
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
