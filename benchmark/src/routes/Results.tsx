import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import {
  Button,
  Card,
  Empty,
  Input,
  Select,
  Space,
  Spin,
  Table,
  Tag,
  Tooltip,
  Typography,
} from "antd";
import { ClearOutlined, EyeOutlined } from "@ant-design/icons";
import type { ColumnsType } from "antd/es/table";
import { ResultDetailModal } from "@/components/results/ResultDetailModal";
import { useResults, useTasks } from "@/data/queries";
import { useT } from "@/i18n/LanguageProvider";
import {
  formatCost,
  formatDuration,
  formatScore,
  formatTokens,
  formatTokensPerSecond,
} from "@/lib/format";
import { normalizeResult, visibleTasks } from "@/lib/stats";
import { DEFAULT_HARNESS_IDS } from "@/store/filters";
import {
  aggregateJudgeScores,
  maxSharedDivergence,
  weightedNormalized,
} from "@/lib/judge/scoring";
import { estimateResultTokenProcessing } from "@/lib/tokenSpeed";
import type { Environment, Result } from "@/schema/results";
import type { Criterion, Task, TasksFile } from "@/schema/tasks";

// Two points on the 0-6 scale: a human "good" (4) against a judge "partial" (2).
const DIVERGENCE_THRESHOLD = 2;

// Full criteria set a judge scores: human core + task extra + judge-only.
function judgeCriteriaFor(tasks: TasksFile, task: Task | undefined): Criterion[] {
  return [...tasks.coreCriteria, ...(task?.extraCriteria ?? []), ...tasks.judgeCriteria];
}

const { Title, Text, Paragraph } = Typography;

interface ResultRow {
  key: string;
  result: Result;
  task: Task | undefined;
  modelName: string;
  environment: Environment | undefined;
  score: number;
  judgeScore: number | null;
  judgeCount: number;
  judgeDivergence: number;
}

export default function Results() {
  const t = useT();
  const { data: tasksData, isLoading: tasksLoading } = useTasks();
  const { data: resultsData, isLoading: resultsLoading } = useResults();
  const [modelFilter, setModelFilter] = useState<string[]>([]);
  const [taskFilter, setTaskFilter] = useState<string[]>([]);
  const [environmentFilter, setEnvironmentFilter] = useState<string[]>([]);
  const [environmentTypeFilter, setEnvironmentTypeFilter] = useState<Array<"local" | "cloud">>([]);
  // This page answers "which model for Refio", so an external agent's run is opt-in
  // here exactly as it is on the leaderboard; the agents page is where they are shown.
  const [harnessFilter, setHarnessFilter] = useState<string[]>(DEFAULT_HARNESS_IDS);
  const [searchText, setSearchText] = useState("");
  const [detailResult, setDetailResult] = useState<Result | null>(null);

  const taskById = useMemo(
    () => new Map((tasksData?.tasks ?? []).map((task) => [task.id, task])),
    [tasksData],
  );

  const modelById = useMemo(
    () => new Map((resultsData?.models ?? []).map((model) => [model.id, model])),
    [resultsData],
  );

  const environmentById = useMemo(
    () => new Map((resultsData?.environments ?? []).map((env) => [env.id, env])),
    [resultsData],
  );

  const rows = useMemo<ResultRow[]>(() => {
    if (!tasksData || !resultsData) return [];
    const query = searchText.trim().toLowerCase();

    return resultsData.results
      .filter((result) => {
        const model = modelById.get(result.modelId);
        const task = taskById.get(result.taskId);
        const environment = environmentById.get(result.environmentId);

        // Hidden tasks are excluded from the public results view entirely.
        if (task?.hidden) return false;
        if (modelFilter.length > 0 && !modelFilter.includes(result.modelId)) return false;
        if (harnessFilter.length > 0 && !harnessFilter.includes(result.harnessId)) return false;
        if (taskFilter.length > 0 && !taskFilter.includes(result.taskId)) return false;
        if (environmentFilter.length > 0 && !environmentFilter.includes(result.environmentId)) return false;
        if (
          environmentTypeFilter.length > 0 &&
          (!environment || !environmentTypeFilter.includes(environment.type))
        ) {
          return false;
        }
        if (!query) return true;

        return [
          result.id,
          result.modelId,
          model?.name,
          result.taskId,
          task?.name,
          result.environmentId,
          environment?.name,
          result.notes,
        ]
          .filter(Boolean)
          .some((value) => String(value).toLowerCase().includes(query));
      })
      .map((result) => {
        const task = taskById.get(result.taskId);
        const aggregate = aggregateJudgeScores(result.judgeScores ?? []);
        const judgeCount = (result.judgeScores ?? []).filter((j) => j.error == null).length;
        return {
          key: result.id,
          result,
          task,
          modelName: modelById.get(result.modelId)?.name ?? result.modelId,
          environment: environmentById.get(result.environmentId),
          score: normalizeResult(result, tasksData),
          judgeScore:
            judgeCount > 0
              ? weightedNormalized(aggregate, judgeCriteriaFor(tasksData, task))
              : null,
          judgeCount,
          judgeDivergence: maxSharedDivergence(result.scores, aggregate),
        };
      })
      .sort((a, b) => b.result.runAt.localeCompare(a.result.runAt));
  }, [
    environmentById,
    environmentFilter,
    environmentTypeFilter,
    harnessFilter,
    modelById,
    modelFilter,
    resultsData,
    searchText,
    taskById,
    taskFilter,
    tasksData,
  ]);

  const selectedRow = detailResult
    ? rows.find((row) => row.result.id === detailResult.id)
    : undefined;

  const hasFilters =
    modelFilter.length > 0 ||
    taskFilter.length > 0 ||
    environmentFilter.length > 0 ||
    environmentTypeFilter.length > 0 ||
    // Clearing brings the page back to the Refio track, not to everything.
    harnessFilter.join() !== DEFAULT_HARNESS_IDS.join() ||
    searchText.trim().length > 0;

  const clearFilters = () => {
    setModelFilter([]);
    setTaskFilter([]);
    setEnvironmentFilter([]);
    setEnvironmentTypeFilter([]);
    setHarnessFilter(DEFAULT_HARNESS_IDS);
    setSearchText("");
  };

  const columns: ColumnsType<ResultRow> = [
    {
      title: t("results.colTask"),
      key: "task",
      width: 220,
      render: (_, row) =>
        row.task ? (
          <Link to={`/tasks/${row.task.id}`}>{row.task.name}</Link>
        ) : (
          row.result.taskId
        ),
    },
    {
      title: t("results.colModel"),
      key: "model",
      width: 220,
      render: (_, row) => (
        <div>
          <Text strong>{row.modelName}</Text>
          <br />
          <Text type="secondary" style={{ fontSize: 12 }}>
            {row.result.modelId}
          </Text>
        </div>
      ),
    },
    {
      title: t("results.colEnvironment"),
      key: "environment",
      width: 160,
      render: (_, row) => (
        <Space direction="vertical" size={2}>
          <Text>{row.environment?.name ?? row.result.environmentId}</Text>
          <Tag color={row.environment?.type === "cloud" ? "blue" : "green"}>
            {row.environment?.type === "cloud"
              ? t("results.envCloud")
              : row.environment?.type === "local"
                ? t("results.envLocal")
                : (row.environment?.type ?? t("results.envUnknown"))}
          </Tag>
        </Space>
      ),
    },
    {
      title: t("results.colAttempt"),
      dataIndex: ["result", "attemptNumber"],
      key: "attempt",
      width: 90,
      sorter: (a, b) => a.result.attemptNumber - b.result.attemptNumber,
    },
    {
      title: t("results.colScore"),
      key: "score",
      width: 110,
      sorter: (a, b) => a.score - b.score,
      render: (_, row) => <Text strong>{formatScore(row.score)}</Text>,
    },
    {
      title: t("results.colJudges"),
      key: "judgeScore",
      width: 150,
      sorter: (a: ResultRow, b: ResultRow) => (a.judgeScore ?? -1) - (b.judgeScore ?? -1),
      render: (_: unknown, row: ResultRow) =>
        row.judgeScore == null ? (
          <Text type="secondary">-</Text>
        ) : (
          <Space size={4}>
            <Text strong>{formatScore(row.judgeScore)}</Text>
            <Text type="secondary" style={{ fontSize: 11 }}>
              ×{row.judgeCount}
            </Text>
            {row.judgeDivergence >= DIVERGENCE_THRESHOLD && (
              <Tooltip title={t("results.divergence", { diff: row.judgeDivergence })}>
                <Tag color="orange" style={{ marginInlineEnd: 0 }}>
                  Δ
                </Tag>
              </Tooltip>
            )}
          </Space>
        ),
    },
    {
      title: t("results.colDuration"),
      key: "duration",
      width: 110,
      render: (_, row) => formatDuration(row.result.durationMs),
      sorter: (a, b) => (a.result.durationMs ?? 0) - (b.result.durationMs ?? 0),
    },
    {
      title: t("results.colTokens"),
      key: "tokens",
      width: 120,
      render: (_, row) => {
        if (row.result.tokensIn == null && row.result.tokensOut == null) return "-";
        return `${formatTokens(row.result.tokensIn)} / ${formatTokens(row.result.tokensOut)}`;
      },
    },
    {
      title: t("results.colLlmEst"),
      key: "estimatedLlm",
      width: 110,
      render: (_, row) =>
        formatDuration(estimateResultTokenProcessing(row.result).totalMs),
      sorter: (a, b) =>
        (estimateResultTokenProcessing(a.result).totalMs ?? 0) -
        (estimateResultTokenProcessing(b.result).totalMs ?? 0),
    },
    {
      title: t("results.colTokenSpeed"),
      key: "tokenSpeed",
      width: 150,
      render: (_, row) => {
        const estimate = estimateResultTokenProcessing(row.result);
        return (
          <span>
            {t("results.speedIn", { value: formatTokensPerSecond(estimate.prefillTokensPerSecond) })}
            <br />
            {t("results.speedOut", { value: formatTokensPerSecond(estimate.decodeTokensPerSecond) })}
          </span>
        );
      },
    },
    {
      title: t("results.colCost"),
      key: "cost",
      width: 100,
      render: (_, row) => formatCost(row.result.costUsd),
      sorter: (a, b) => (a.result.costUsd ?? 0) - (b.result.costUsd ?? 0),
    },
    {
      title: t("results.colRun"),
      key: "runAt",
      width: 140,
      render: (_, row) => new Date(row.result.runAt).toLocaleDateString(),
      sorter: (a, b) => a.result.runAt.localeCompare(b.result.runAt),
    },
    {
      title: "",
      key: "action",
      width: 72,
      render: (_, row) => (
        <Button
          aria-label={t("results.viewResult")}
          icon={<EyeOutlined />}
          onClick={(event) => {
            event.stopPropagation();
            setDetailResult(row.result);
          }}
        />
      ),
    },
  ];

  if (tasksLoading || resultsLoading) {
    return (
      <div style={{ display: "flex", justifyContent: "center", padding: 80 }}>
        <Spin size="large" />
      </div>
    );
  }

  if (!tasksData || !resultsData || resultsData.results.length === 0) {
    return <Empty description={t("results.noResults")} />;
  }

  return (
    <div>
      <Title level={2}>{t("results.title")}</Title>
      <Paragraph type="secondary">{t("results.intro")}</Paragraph>

      <Card style={{ marginBottom: 16 }}>
        <Space wrap>
          <Select
            mode="multiple"
            allowClear
            showSearch
            optionFilterProp="label"
            placeholder={t("results.filterModels")}
            value={modelFilter}
            onChange={setModelFilter}
            options={resultsData.models.map((model) => ({
              value: model.id,
              label: model.name,
            }))}
            style={{ minWidth: 220 }}
          />
          <Select
            mode="multiple"
            allowClear
            showSearch
            optionFilterProp="label"
            placeholder={t("results.filterTasks")}
            value={taskFilter}
            onChange={setTaskFilter}
            options={visibleTasks(tasksData.tasks).map((task) => ({
              value: task.id,
              label: task.name,
            }))}
            style={{ minWidth: 200 }}
          />
          <Select
            mode="multiple"
            allowClear
            showSearch
            optionFilterProp="label"
            placeholder={t("results.filterEnvironments")}
            value={environmentFilter}
            onChange={setEnvironmentFilter}
            options={resultsData.environments.map((env) => ({
              value: env.id,
              label: env.name,
            }))}
            style={{ minWidth: 190 }}
          />
          <Select
            mode="multiple"
            allowClear
            placeholder={t("results.filterEnvType")}
            value={environmentTypeFilter}
            onChange={setEnvironmentTypeFilter}
            options={[
              { value: "local", label: t("results.filterLocal") },
              { value: "cloud", label: t("results.filterCloud") },
            ]}
            style={{ minWidth: 140 }}
          />
          <Select
            mode="multiple"
            allowClear
            placeholder={t("results.filterHarness")}
            value={harnessFilter}
            onChange={setHarnessFilter}
            options={(resultsData.harnesses ?? []).map((harness) => ({
              value: harness.id,
              label: harness.name,
            }))}
            style={{ minWidth: 170 }}
          />
          <Input.Search
            allowClear
            placeholder={t("results.search")}
            value={searchText}
            onChange={(event) => setSearchText(event.target.value)}
            style={{ width: 240 }}
          />
          <Text type="secondary">
            {rows.length} / {resultsData.results.length}
          </Text>
          {hasFilters && (
            <Button icon={<ClearOutlined />} onClick={clearFilters}>
              {t("results.clear")}
            </Button>
          )}
        </Space>
      </Card>

      <Card>
        <Table<ResultRow>
          columns={columns}
          dataSource={rows}
          rowKey="key"
          size="middle"
          pagination={{ pageSize: 20, showSizeChanger: true }}
          scroll={{ x: "max-content" }}
          onRow={(row) => ({
            onClick: () => setDetailResult(row.result),
            style: { cursor: "pointer" },
          })}
        />
      </Card>

      <ResultDetailModal
        key={detailResult?.id ?? "none"}
        detailResult={detailResult}
        tasksFile={tasksData}
        task={detailResult ? taskById.get(detailResult.taskId) : undefined}
        modelName={selectedRow?.modelName}
        environmentName={selectedRow?.environment?.name}
        onClose={() => setDetailResult(null)}
      />
    </div>
  );
}
