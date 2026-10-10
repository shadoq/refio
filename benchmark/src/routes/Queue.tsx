import { useState } from "react";
import {
  Typography,
  Card,
  Space,
  Tag,
  Select,
  Button,
  Popconfirm,
  Pagination,
  Empty,
  Spin,
  Descriptions,
  Collapse,
  message,
} from "antd";
import { useResults, useTasks } from "@/data/queries";
import { useT } from "@/i18n/LanguageProvider";
import { usePromoteInboxEntry, useDiscardInboxEntry } from "@/data/mutations";
import { ArtifactPreview } from "@/components/attachments/ArtifactPreview";
import { TraceSummaryTags } from "@/components/results/TraceSummaryTags";
import { TraceTimeline } from "@/components/results/TraceTimeline";
import { inboxScreenshots } from "@/lib/adminArtifacts";
import { filterInboxEntries, inboxFacetOptions, type QueueFilters } from "@/lib/queueFilters";
import type { InboxEntry, Score } from "@/schema/results";
import type { Criterion } from "@/schema/tasks";

const { Title, Text, Paragraph } = Typography;

function fmt(n: number | undefined, suffix = ""): string {
  return n === undefined ? "-" : `${n}${suffix}`;
}

// The deterministic judge entry, if present, drives the score hints shown to the reviewer.
function deterministicScores(entry: InboxEntry) {
  const set = entry.judgeScores.find((j) => j.judgeId === "e2e-deterministic");
  return set?.scores ?? [];
}

function QueueCard({
  entry,
  criteria,
}: {
  entry: InboxEntry;
  criteria: Criterion[];
}) {
  const t = useT();
  const promote = usePromoteInboxEntry();
  const discard = useDiscardInboxEntry();
  const [picked, setPicked] = useState<Record<string, number>>({});

  const html = entry.attachments.find((a) => a.type === "html");
  const screenshots = inboxScreenshots(entry);
  const det = deterministicScores(entry);
  const verdict = entry.autoVerdict?.verdict;

  async function onPromote() {
    const scores: Score[] = criteria
      .filter((c) => picked[c.id] !== undefined)
      .map((c) => ({ criterionId: c.id, value: picked[c.id] }));
    if (scores.length === 0) {
      message.warning(t("queue.scoreFirst"));
      return;
    }
    try {
      await promote.mutateAsync({ entryId: entry.id, scores });
      message.success(t("queue.promoted", { id: entry.id }));
    } catch (e) {
      message.error(t("queue.promoteFailed", { error: String(e) }));
    }
  }

  async function onDiscard() {
    try {
      await discard.mutateAsync({ entryId: entry.id });
      message.success(t("queue.discarded", { id: entry.id }));
    } catch (e) {
      message.error(t("queue.discardFailed", { error: String(e) }));
    }
  }

  return (
    <Card
      style={{ marginBottom: 16 }}
      title={
        <Space wrap>
          <Text strong>{entry.taskId}</Text>
          <Text type="secondary">{entry.modelId}</Text>
          <Tag>{t("queue.attempt", { n: entry.attemptNumber })}</Tag>
          {verdict && <Tag color={verdict === "PASS" ? "green" : "red"}>
              {verdict === "PASS" ? t("queue.verdictPass") : verdict === "FAIL" ? t("queue.verdictFail") : verdict}
            </Tag>}
        </Space>
      }
      extra={
        <Space>
          <Button type="primary" loading={promote.isPending} onClick={onPromote}>
            {t("queue.promote")}
          </Button>
          <Popconfirm
            title={t("queue.discardConfirm")}
            okText={t("queue.discard")}
            okButtonProps={{ danger: true }} onConfirm={onDiscard}>
            <Button danger loading={discard.isPending}>
              {t("queue.discard")}
            </Button>
          </Popconfirm>
        </Space>
      }
    >
      <div style={{ display: "grid", gridTemplateColumns: "1.2fr 1fr", gap: 16 }}>
        <div>
          {html || screenshots.length > 0 ? (
            <ArtifactPreview htmlSrc={html?.src ?? null} screenshots={screenshots} />
          ) : (
            <Empty description={t("queue.noArtifact")} />
          )}
        </div>
        <div>
          <Descriptions size="small" column={1} style={{ marginBottom: 12 }}>
            <Descriptions.Item label={t("queue.duration")}>{fmt(entry.durationMs, " ms")}</Descriptions.Item>
            <Descriptions.Item label={t("queue.tokensOut")}>{fmt(entry.tokensOut)}</Descriptions.Item>
            <Descriptions.Item label={t("queue.cost")}>{fmt(entry.costUsd, " $")}</Descriptions.Item>
          </Descriptions>

          {entry.trace && (
            <div style={{ marginBottom: 12 }}>
              <TraceSummaryTags trace={entry.trace} />
              <Collapse
                ghost
                size="small"
                items={[
                  {
                    key: "trace",
                    label: t("queue.runTrace"),
                    children: <TraceTimeline trace={entry.trace} />,
                  },
                ]}
              />
            </div>
          )}

          <Text strong>{t("queue.deterministicChecks")}</Text>
          <div style={{ margin: "6px 0 12px" }}>
            {det.length === 0 ? (
              <Text type="secondary">{t("queue.none")}</Text>
            ) : (
              det.map((s) => (
                <div key={s.criterionId} style={{ fontSize: 12 }}>
                  <Tag>{s.criterionId}: {s.value}</Tag>
                  {s.rationale && <Text type="secondary">{s.rationale}</Text>}
                </div>
              ))
            )}
          </div>

          <Text strong>{t("queue.yourScores")}</Text>
          <div style={{ marginTop: 6, display: "flex", flexDirection: "column", gap: 8 }}>
            {criteria.map((c) => (
              <div key={c.id} style={{ display: "flex", alignItems: "center", gap: 8 }}>
                <Text style={{ width: 150 }}>{c.name}</Text>
                <Select
                  size="small"
                  style={{ width: 160 }}
                  placeholder={t("queue.scorePlaceholder")}
                  value={picked[c.id]}
                  onChange={(v) => setPicked((p) => ({ ...p, [c.id]: v }))}
                  options={c.scale.values.map((v) => ({
                    value: v,
                    label: c.scale.labels?.[String(v)] ? `${v} - ${c.scale.labels[String(v)]}` : `${v}`,
                  }))}
                />
              </div>
            ))}
          </div>
        </div>
      </div>
      {entry.notes && (
        <Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0, fontSize: 12 }}>
          {entry.notes}
        </Paragraph>
      )}
    </Card>
  );
}

const PAGE_SIZE = 10;

export default function Queue() {
  const t = useT();
  const { data: results } = useResults();
  const { data: tasks } = useTasks();
  const [filters, setFilters] = useState<QueueFilters>({});
  const [page, setPage] = useState(1);

  if (!results || !tasks) {
    return (
      <div style={{ display: "flex", justifyContent: "center", padding: 40 }}>
        <Spin />
      </div>
    );
  }

  const inbox = results.inbox ?? [];
  const facets = inboxFacetOptions(inbox);
  const filtered = filterInboxEntries(inbox, filters);
  const paged = filtered.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);

  function criteriaFor(taskId: string): Criterion[] {
    const task = tasks!.tasks.find((t) => t.id === taskId);
    return [...tasks!.coreCriteria, ...(task?.extraCriteria ?? [])];
  }

  // Changing any filter resets to the first page so the visible slice is never stale.
  function updateFilter(patch: Partial<QueueFilters>) {
    setFilters((f) => ({ ...f, ...patch }));
    setPage(1);
  }

  const taskLabel = (id: string) => tasks!.tasks.find((t) => t.id === id)?.name ?? id;

  return (
    <div>
      <Title level={3}>{t("queue.title")}</Title>
      <Paragraph type="secondary">{t("queue.intro")}</Paragraph>

      {inbox.length > 0 && (
        <Space wrap style={{ marginBottom: 16 }}>
          <Select
            allowClear
            placeholder={t("queue.filterTask")}
            style={{ minWidth: 200 }}
            value={filters.taskId}
            onChange={(v) => updateFilter({ taskId: v })}
            options={facets.taskIds.map((id) => ({ value: id, label: taskLabel(id) }))}
          />
          <Select
            allowClear
            placeholder={t("queue.filterModel")}
            style={{ minWidth: 180 }}
            value={filters.modelId}
            onChange={(v) => updateFilter({ modelId: v })}
            options={facets.modelIds.map((id) => ({ value: id, label: id }))}
          />
          <Select
            allowClear
            placeholder={t("queue.filterEnvironment")}
            style={{ minWidth: 150 }}
            value={filters.environmentId}
            onChange={(v) => updateFilter({ environmentId: v })}
            options={facets.environmentIds.map((id) => ({ value: id, label: id }))}
          />
          {facets.harnessIds.length > 1 && (
            <Select
              allowClear
              placeholder={t("queue.filterHarness")}
              style={{ minWidth: 150 }}
              value={filters.harnessId}
              onChange={(v) => updateFilter({ harnessId: v })}
              options={facets.harnessIds.map((id) => ({ value: id, label: id }))}
            />
          )}
          <Select
            allowClear
            placeholder={t("queue.filterVerdict")}
            style={{ minWidth: 120 }}
            value={filters.verdict}
            onChange={(v) => updateFilter({ verdict: v as QueueFilters["verdict"] })}
            options={[
              { value: "PASS", label: t("queue.verdictPass") },
              { value: "FAIL", label: t("queue.verdictFail") },
            ]}
          />
          <Text type="secondary">
            {t("queue.countOf", { shown: filtered.length, total: inbox.length })}
          </Text>
        </Space>
      )}

      {inbox.length === 0 ? (
        <Empty description={t("queue.empty")} />
      ) : filtered.length === 0 ? (
        <Empty description={t("queue.noMatch")} />
      ) : (
        <>
          {paged.map((entry) => (
            <QueueCard key={entry.id} entry={entry} criteria={criteriaFor(entry.taskId)} />
          ))}
          {filtered.length > PAGE_SIZE && (
            <div style={{ display: "flex", justifyContent: "center", marginTop: 16 }}>
              <Pagination
                current={page}
                pageSize={PAGE_SIZE}
                total={filtered.length}
                onChange={setPage}
                showSizeChanger={false}
              />
            </div>
          )}
        </>
      )}
    </div>
  );
}
