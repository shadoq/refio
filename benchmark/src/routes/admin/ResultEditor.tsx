import { useState, useMemo, useEffect } from "react";
import {
  Table,
  Button,
  Modal,
  Form,
  Input,
  InputNumber,
  Select,
  Space,
  Popconfirm,
  Typography,
  Tag,
  DatePicker,
  Divider,
  Upload,
  message,
  Card,
  Segmented,
  Pagination,
  Empty,
  Collapse,
} from "antd";
import {
  PlusOutlined,
  EditOutlined,
  DeleteOutlined,
  UploadOutlined,
  ClearOutlined,
  CopyOutlined,
  EyeOutlined,
} from "@ant-design/icons";
import { useForm, Controller, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import dayjs from "dayjs";
import { ResultSchema, type Result, type Attachment } from "@/schema/results";
import { useTasks, useResults } from "@/data/queries";
import { useUpsertResult, useDeleteResult } from "@/data/mutations";
import { uploadAttachment } from "@/data/saver";
import { ResultPreviewModal } from "./ResultPreviewModal";
import { ResultEvidencePanel } from "./ResultEvidencePanel";
import { ResultCard } from "@/components/results/ResultCard";
import { generateId } from "@/lib/ids";
import { formatDuration, formatCost } from "@/lib/format";
import type { Criterion } from "@/schema/tasks";
import { type input as ZodInput } from "zod";
import { useT } from "@/i18n/LanguageProvider";

const { Title, Text } = Typography;

const CARD_PAGE_SIZE = 6;

type FormData = ZodInput<typeof ResultSchema>;

function durationMsToSeconds(value: number | null | undefined): number | undefined {
  return value == null ? undefined : value / 1000;
}

function durationSecondsToMs(value: string | number | null): number | undefined {
  if (value == null || value === "") return undefined;
  return Math.round(Number(value) * 1000);
}

function ScoreRow({
  criterion,
  control,
  index,
}: {
  criterion: Criterion;
  control: ReturnType<typeof useForm<FormData>>["control"];
  index: number;
}) {
  const t = useT();
  const scaleOptions = criterion.scale.values.map((v) => ({
    label: criterion.scale.labels?.[String(v)] ? `${v} - ${criterion.scale.labels[String(v)]}` : String(v),
    value: v,
  }));

  return (
    <Form.Item
      key={criterion.id}
      label={criterion.name}
      style={{ marginBottom: 8 }}
    >
      <Controller
        name={`scores.${index}.value` as const}
        control={control}
        render={({ field }) => (
          <Select
            {...field}
            options={scaleOptions}
            style={{ width: "100%" }}
            placeholder={t("admin.resultSelectScore")}
          />
        )}
      />
      {/* Hidden field for criterionId */}
      <Controller
        name={`scores.${index}.criterionId` as const}
        control={control}
        render={({ field }) => <input type="hidden" {...field} />}
      />
    </Form.Item>
  );
}

export default function ResultEditor() {
  const t = useT();
  const [modalMode, setModalMode] = useState<"new" | "edit" | "duplicate">("new");
  const [open, setOpen] = useState(false);
  const [previewResult, setPreviewResult] = useState<Result | null>(null);
  // The stored result being edited, shown below the scores while they are changed.
  const [editingResult, setEditingResult] = useState<Result | null>(null);
  const [uploading, setUploading] = useState(false);
  const [modelFilter, setModelFilter] = useState<string[]>([]);
  const [taskFilter, setTaskFilter] = useState<string[]>([]);
  const [environmentFilter, setEnvironmentFilter] = useState<string[]>([]);
  const [environmentTypeFilter, setEnvironmentTypeFilter] = useState<
    Array<"local" | "cloud">
  >([]);
  const [searchText, setSearchText] = useState("");
  const [viewMode, setViewMode] = useState<"table" | "cards">("table");
  const [cardPage, setCardPage] = useState(1);

  const { data: tasksData } = useTasks();
  const { data: resultsData } = useResults();
  const upsert = useUpsertResult();
  const remove = useDeleteResult();

  const now = new Date().toISOString();

  const {
    control,
    handleSubmit,
    reset,
    setValue,
    getValues,
    formState: { errors },
  } = useForm<FormData>({
    resolver: zodResolver(ResultSchema),
  });

  const selectedTaskId = useWatch({ control, name: "taskId" });

  // Build the ordered list of criteria for the selected task
  const activeCriteria: Criterion[] = useMemo(() => {
    if (!tasksData || !selectedTaskId) return [];
    const core = tasksData.coreCriteria;
    const task = tasksData.tasks.find((t) => t.id === selectedTaskId);
    return [...core, ...(task?.extraCriteria ?? [])];
  }, [tasksData, selectedTaskId]);

  // Name lookup for the judge breakdown: everything a judge may have scored.
  const judgeCriteria: Criterion[] = useMemo(
    () => (tasksData ? [...activeCriteria, ...tasksData.judgeCriteria] : []),
    [tasksData, activeCriteria],
  );

  // Whenever activeCriteria changes, rebuild the scores array in the form
  useEffect(() => {
    if (activeCriteria.length === 0) return;
    const current = getValues("scores") ?? [];
    const newScores = activeCriteria.map((c) => {
      const existing = current.find((s) => s.criterionId === c.id);
      return { criterionId: c.id, value: existing?.value ?? c.scale.values[0] };
    });
    setValue("scores", newScores);
  }, [activeCriteria, getValues, setValue]);

  function openNew() {
    setModalMode("new");
    setEditingResult(null);
    reset({
      id: generateId(),
      taskId: "",
      modelId: "",
      environmentId: "",
      harnessId: "refio",
      attemptNumber: 1,
      scores: [],
      attachments: [],
      runAt: now,
      createdAt: now,
    });
    setOpen(true);
  }

  function openEdit(result: Result) {
    setModalMode("edit");
    setEditingResult(result);
    reset({
      ...result,
    });
    setOpen(true);
  }

  function getNextAttemptNumber(result: Result) {
    const siblingAttempts =
      resultsData?.results
        .filter(
          (item) =>
            item.taskId === result.taskId &&
            item.modelId === result.modelId &&
            item.environmentId === result.environmentId,
        )
        .map((item) => item.attemptNumber) ?? [];
    return Math.max(0, ...siblingAttempts) + 1;
  }

  function openDuplicate(result: Result) {
    const duplicatedAt = new Date().toISOString();
    setModalMode("duplicate");
    setEditingResult(null);
    reset({
      ...result,
      id: generateId(),
      attemptNumber: getNextAttemptNumber(result),
      attachments: [],
      runAt: duplicatedAt,
      createdAt: duplicatedAt,
    });
    setOpen(true);
  }

  function handleClose() {
    setOpen(false);
    setEditingResult(null);
    setModalMode("new");
  }

  async function onSubmit(data: FormData) {
    if (!resultsData) return;
    // Cast because zodResolver gives us z.input, mutation expects z.output (same structure at runtime)
    const result = data as unknown as Result;
    await upsert.mutateAsync({
      current: resultsData,
      result,
    });
    handleClose();
  }

  async function handleDelete(resultId: string) {
    if (!resultsData) return;
    await remove.mutateAsync({ current: resultsData, resultId });
  }

  async function handleFileUpload(file: File) {
    const resultId = getValues("id");
    if (!resultId) {
      void message.error(t("admin.resultSaveIdFirst"));
      return false;
    }
    try {
      setUploading(true);
      const modelId = getValues("modelId");
      const attemptNumber = Number(getValues("attemptNumber") ?? 1);
      const model = resultsData?.models.find((item) => item.id === modelId);
      const current = getValues("attachments") ?? [];
      const path = await uploadAttachment(resultId, file, {
        modelProvider: model?.provider,
        modelName: model?.name,
        modelId,
        attemptNumber,
        fileNumber: current.length + 1,
      });
      const ext = file.name.split(".").pop()?.toLowerCase() ?? "";
      const type: Attachment["type"] =
        ["png", "jpg", "jpeg", "gif", "webp"].includes(ext)
          ? "image"
          : ["html", "htm"].includes(ext)
            ? "html"
            : ["mp4", "webm", "mov"].includes(ext)
              ? "video"
              : ["zip", "7z", "tar", "gz"].includes(ext)
                ? "archive"
                : "file";
      setValue("attachments", [...current, { type, src: path }]);
      void message.success(t("admin.resultUploaded", { path }));
    } catch (e) {
      void message.error(String(e));
    } finally {
      setUploading(false);
    }
    return false; // prevent antd default upload behavior
  }

  const taskOptions = (tasksData?.tasks ?? []).map((t) => ({
    label: t.name,
    value: t.id,
  }));

  const modelOptions = (resultsData?.models ?? []).map((m) => ({
    label: m.name,
    value: m.id,
  }));

  const envOptions = (resultsData?.environments ?? []).map((e) => ({
    label: e.name,
    value: e.id,
  }));

  // Refio is always offered even before anyone edits the registry, so an existing
  // result can always be re-saved with a valid harness.
  const harnessOptions = [
    { label: "Refio", value: "refio" },
    ...(resultsData?.harnesses ?? [])
      .filter((h) => h.id !== "refio")
      .map((h) => ({ label: h.name, value: h.id })),
  ];

  const environmentTypeOptions = [
    { label: t("admin.envTypeLocal"), value: "local" },
    { label: t("admin.envTypeCloud"), value: "cloud" },
  ];

  const filteredResults = useMemo(() => {
    const results = resultsData?.results ?? [];
    const envById = new Map(
      (resultsData?.environments ?? []).map((env) => [env.id, env]),
    );
    const query = searchText.trim().toLowerCase();

    return results.filter((result) => {
      if (modelFilter.length > 0 && !modelFilter.includes(result.modelId)) {
        return false;
      }
      if (taskFilter.length > 0 && !taskFilter.includes(result.taskId)) {
        return false;
      }
      if (
        environmentFilter.length > 0 &&
        !environmentFilter.includes(result.environmentId)
      ) {
        return false;
      }

      const envType = envById.get(result.environmentId)?.type;
      if (
        environmentTypeFilter.length > 0 &&
        (!envType || !environmentTypeFilter.includes(envType))
      ) {
        return false;
      }

      if (!query) return true;

      const taskName =
        tasksData?.tasks.find((task) => task.id === result.taskId)?.name ?? "";
      const modelName =
        resultsData?.models.find((model) => model.id === result.modelId)?.name ??
        "";
      const envName = envById.get(result.environmentId)?.name ?? "";
      const haystack = [
        result.id,
        result.taskId,
        taskName,
        result.modelId,
        modelName,
        result.environmentId,
        envName,
        result.notes ?? "",
      ]
        .join(" ")
        .toLowerCase();

      return haystack.includes(query);
    });
  }, [
    environmentFilter,
    environmentTypeFilter,
    modelFilter,
    resultsData,
    searchText,
    taskFilter,
    tasksData,
  ]);

  const hasResultFilters =
    modelFilter.length > 0 ||
    taskFilter.length > 0 ||
    environmentFilter.length > 0 ||
    environmentTypeFilter.length > 0 ||
    searchText.trim().length > 0;

  function clearResultFilters() {
    setModelFilter([]);
    setTaskFilter([]);
    setEnvironmentFilter([]);
    setEnvironmentTypeFilter([]);
    setSearchText("");
  }

  // Keep the cards page within range as filtering or deleting shrinks the result set.
  const cardPageCount = Math.max(1, Math.ceil(filteredResults.length / CARD_PAGE_SIZE));
  const currentCardPage = Math.min(cardPage, cardPageCount);

  const columns = [
    {
      title: t("admin.resultColTask"),
      dataIndex: "taskId",
      key: "task",
      width: 120,
      render: (id: string) =>
        tasksData?.tasks.find((t) => t.id === id)?.name ?? id,
    },
    {
      title: t("admin.resultColModel"),
      dataIndex: "modelId",
      key: "model",
      render: (id: string) =>
        resultsData?.models.find((m) => m.id === id)?.name ?? id,
    },
    {
      title: t("admin.resultColEnv"),
      dataIndex: "environmentId",
      key: "env",
      width: 120,
      render: (id: string) => {
        const env = resultsData?.environments.find((e) => e.id === id);
        return env ? (
          <Tag color={env.type === "cloud" ? "blue" : "green"}>{env.name}</Tag>
        ) : (
          id
        );
      },
    },
    {
      title: "#",
      dataIndex: "attemptNumber",
      key: "attempt",
      width: 50,
    },
    {
      title: t("admin.resultColDuration"),
      dataIndex: "durationMs",
      key: "duration",
      width: 90,
      render: (ms: number | undefined) => formatDuration(ms),
    },
    {
      title: t("admin.resultColCost"),
      dataIndex: "costUsd",
      key: "cost",
      width: 80,
      render: (usd: number | undefined) => formatCost(usd),
    },
    {
      title: t("admin.resultColAttachments"),
      key: "att",
      width: 90,
      render: (_: unknown, record: Result) => record.attachments.length || "-",
    },
    {
      title: t("admin.colActions"),
      key: "actions",
      width: 190,
      render: (_: unknown, record: Result) => (
        <Space>
          <Button
            aria-label={t("admin.resultPreviewAria")}
            icon={<EyeOutlined />}
            size="small"
            onClick={() => setPreviewResult(record)}
          />
          <Button
            icon={<EditOutlined />}
            size="small"
            onClick={() => openEdit(record)}
          />
          <Button
            icon={<CopyOutlined />}
            size="small"
            onClick={() => openDuplicate(record)}
          />
          <Popconfirm
            title={t("admin.resultDeleteConfirm")}
            onConfirm={() => handleDelete(record.id)}
            okText={t("admin.deleteOk")}
            okButtonProps={{ danger: true }}
          >
            <Button icon={<DeleteOutlined />} size="small" danger />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  // The form is split into blocks so the edit dialog can lead with the scores and the
  // result itself, and fold the run details away, while new and duplicate keep the
  // original top-to-bottom order.
  const identityFields = (
    <>
      <Form.Item
        label={t("admin.resultTask")}
        validateStatus={errors.taskId ? "error" : ""}
        help={errors.taskId?.message}
      >
        <Controller
          name="taskId"
          control={control}
          render={({ field }) => (
            <Select
              {...field}
              options={taskOptions}
              placeholder={t("admin.resultSelectTask")}
              showSearch
            />
          )}
        />
      </Form.Item>

      <Form.Item
        label={t("admin.resultModel")}
        validateStatus={errors.modelId ? "error" : ""}
        help={errors.modelId?.message}
      >
        <Controller
          name="modelId"
          control={control}
          render={({ field }) => (
            <Select
              {...field}
              options={modelOptions}
              placeholder={t("admin.resultSelectModel")}
              showSearch
            />
          )}
        />
      </Form.Item>

      <Form.Item
        label={t("admin.resultEnvironment")}
        validateStatus={errors.environmentId ? "error" : ""}
        help={errors.environmentId?.message}
      >
        <Controller
          name="environmentId"
          control={control}
          render={({ field }) => (
            <Select
              {...field}
              options={envOptions}
              placeholder={t("admin.resultSelectEnvironment")}
            />
          )}
        />
      </Form.Item>

      <Form.Item
        label={t("admin.resultHarness")}
        validateStatus={errors.harnessId ? "error" : ""}
        help={errors.harnessId?.message}
      >
        <Controller
          name="harnessId"
          control={control}
          render={({ field }) => (
            <Select {...field} options={harnessOptions} placeholder={t("admin.resultSelectHarness")} />
          )}
        />
      </Form.Item>

      <Form.Item
        label={t("admin.resultAttempt")}
        validateStatus={errors.attemptNumber ? "error" : ""}
        help={errors.attemptNumber?.message}
      >
        <Controller
          name="attemptNumber"
          control={control}
          render={({ field }) => (
            <InputNumber {...field} min={1} style={{ width: 100 }} />
          )}
        />
      </Form.Item>
    </>
  );

  const scoreFields = activeCriteria.length > 0 && (
    <>
      <Divider>{t("admin.resultScores")}</Divider>
      <div
        style={{
          display: "grid",
          gridTemplateColumns: "repeat(auto-fill, minmax(200px, 1fr))",
          columnGap: 16,
        }}
      >
        {activeCriteria.map((criterion, idx) => (
          <ScoreRow key={criterion.id} criterion={criterion} control={control} index={idx} />
        ))}
      </div>
    </>
  );

  const metricsFields = (
    <>
      <Divider>{t("admin.resultMetrics")}</Divider>

      <Form.Item label={t("admin.resultDurationSeconds")}>
        <Controller
          name="durationMs"
          control={control}
          render={({ field }) => (
            <InputNumber
              value={durationMsToSeconds(field.value)}
              onBlur={field.onBlur}
              onChange={(value) => field.onChange(durationSecondsToMs(value))}
              min={0}
              step={1}
              precision={1}
              style={{ width: 160 }}
              placeholder={t("admin.example", { value: "45" })}
            />
          )}
        />
      </Form.Item>

      <Form.Item label={t("admin.resultTokensIn")}>
        <Controller
          name="tokensIn"
          control={control}
          render={({ field }) => (
            <InputNumber
              {...field}
              value={field.value ?? undefined}
              min={0}
              style={{ width: 160 }}
            />
          )}
        />
      </Form.Item>

      <Form.Item label={t("admin.resultTokensOut")}>
        <Controller
          name="tokensOut"
          control={control}
          render={({ field }) => (
            <InputNumber
              {...field}
              value={field.value ?? undefined}
              min={0}
              style={{ width: 160 }}
            />
          )}
        />
      </Form.Item>

      <Form.Item label={t("admin.resultCostUsd")}>
        <Controller
          name="costUsd"
          control={control}
          render={({ field }) => (
            <InputNumber
              {...field}
              value={field.value ?? undefined}
              min={0}
              step={0.001}
              precision={4}
              style={{ width: 160 }}
              placeholder={t("admin.example", { value: "0.024" })}
            />
          )}
        />
      </Form.Item>

      <Form.Item label={t("admin.resultRunAt")}>
        <Controller
          name="runAt"
          control={control}
          render={({ field }) => (
            <DatePicker
              showTime
              value={field.value ? dayjs(field.value) : null}
              onChange={(d) => field.onChange(d?.toISOString() ?? now)}
              style={{ width: 220 }}
            />
          )}
        />
      </Form.Item>
    </>
  );

  const notesField = (
    <>
      <Form.Item label={t("admin.fieldNotes")}>
        <Controller
          name="notes"
          control={control}
          render={({ field }) => (
            <Input.TextArea
              {...field}
              value={field.value ?? ""}
              rows={3}
              placeholder={t("admin.resultNotesPlaceholder")}
            />
          )}
        />
      </Form.Item>
    </>
  );

  const attachmentFields = (
    <>
      <Divider>{t("admin.resultAttachments")}</Divider>

      <Form.Item label={t("admin.resultUploadLabel")}>
        <Upload
          beforeUpload={handleFileUpload}
          showUploadList={false}
          accept="image/*,.html,.htm,.mp4,.webm,.mov,.zip,.7z,.tar,.gz"
        >
          <Button icon={<UploadOutlined />} loading={uploading}>
            {t("admin.resultUploadButton")}
          </Button>
        </Upload>
      </Form.Item>

      <Controller
        name="attachments"
        control={control}
        render={({ field }) => (
          <div>
            {(field.value ?? []).map((att, idx) => (
              <div
                key={idx}
                style={{
                  display: "flex",
                  alignItems: "center",
                  gap: 8,
                  marginBottom: 4,
                }}
              >
                <Tag>{att.type}</Tag>
                <Text style={{ flex: 1, fontSize: 12 }}>{att.src}</Text>
                <Button
                  size="small"
                  danger
                  onClick={() =>
                    field.onChange(field.value?.filter((_, i) => i !== idx))
                  }
                >
                  {t("admin.resultRemove")}
                </Button>
              </div>
            ))}
          </div>
        )}
      />
    </>
  );

  return (
    <div>
      <Space style={{ marginBottom: 16 }} wrap>
        <Title level={3} style={{ margin: 0 }}>
          {t("admin.resultTitle")}
        </Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={openNew}>
          {t("admin.resultNew")}
        </Button>
        <Segmented
          options={[
            { label: t("admin.resultViewTable"), value: "table" },
            { label: t("admin.resultViewCards"), value: "cards" },
          ]}
          value={viewMode}
          onChange={(v) => setViewMode(v as "table" | "cards")}
        />
      </Space>

      <Card size="small" style={{ marginBottom: 16 }}>
        <Space size="small" wrap>
          <Select
            mode="multiple"
            allowClear
            showSearch
            placeholder={t("admin.resultFilterModels")}
            options={modelOptions}
            value={modelFilter}
            onChange={setModelFilter}
            style={{ minWidth: 220 }}
            maxTagCount="responsive"
            optionFilterProp="label"
          />
          <Select
            mode="multiple"
            allowClear
            showSearch
            placeholder={t("admin.resultFilterTasks")}
            options={taskOptions}
            value={taskFilter}
            onChange={setTaskFilter}
            style={{ minWidth: 180 }}
            maxTagCount="responsive"
            optionFilterProp="label"
          />
          <Select
            mode="multiple"
            allowClear
            showSearch
            placeholder={t("admin.resultFilterEnvironments")}
            options={envOptions}
            value={environmentFilter}
            onChange={setEnvironmentFilter}
            style={{ minWidth: 180 }}
            maxTagCount="responsive"
            optionFilterProp="label"
          />
          <Select
            mode="multiple"
            allowClear
            placeholder={t("admin.resultFilterEnvType")}
            options={environmentTypeOptions}
            value={environmentTypeFilter}
            onChange={setEnvironmentTypeFilter}
            style={{ minWidth: 140 }}
            maxTagCount="responsive"
          />
          <Input.Search
            allowClear
            placeholder={t("admin.resultSearch")}
            value={searchText}
            onChange={(event) => setSearchText(event.target.value)}
            style={{ width: 240 }}
          />
          <Text type="secondary">
            {filteredResults.length} / {resultsData?.results.length ?? 0}
          </Text>
          {hasResultFilters && (
            <Button icon={<ClearOutlined />} onClick={clearResultFilters}>
              {t("admin.clear")}
            </Button>
          )}
        </Space>
      </Card>

      {viewMode === "table" ? (
        <Table
          columns={columns}
          dataSource={filteredResults}
          rowKey="id"
          size="middle"
          pagination={{ pageSize: 20 }}
        />
      ) : filteredResults.length === 0 ? (
        <Empty description={t("admin.resultNoMatch")} />
      ) : (
        <>
          {filteredResults
            .slice((currentCardPage - 1) * CARD_PAGE_SIZE, currentCardPage * CARD_PAGE_SIZE)
            .map((result) => (
              <ResultCard
                key={result.id}
                result={result}
                tasksData={tasksData}
                resultsData={resultsData}
                onPreview={setPreviewResult}
                onEdit={openEdit}
                onDuplicate={openDuplicate}
                onDelete={handleDelete}
              />
            ))}
          {filteredResults.length > CARD_PAGE_SIZE && (
            <div style={{ display: "flex", justifyContent: "center", marginTop: 8 }}>
              <Pagination
                current={currentCardPage}
                pageSize={CARD_PAGE_SIZE}
                total={filteredResults.length}
                onChange={setCardPage}
                showSizeChanger={false}
              />
            </div>
          )}
        </>
      )}

      <Modal
        title={
          modalMode === "edit"
            ? t("admin.resultEditTitle")
            : modalMode === "duplicate"
              ? t("admin.resultDuplicateTitle")
              : t("admin.resultNew")
        }
        open={open}
        onCancel={handleClose}
        onOk={handleSubmit(onSubmit)}
        confirmLoading={upsert.isPending}
        destroyOnClose
        width={editingResult ? "94vw" : 700}
        style={editingResult ? { top: 16 } : undefined}
      >
        <Form layout="vertical" style={{ marginTop: 16 }}>
          {editingResult ? (
            <>
              {scoreFields}
              {notesField}
              <Divider>{t("admin.resultSection")}</Divider>
              <ResultEvidencePanel result={editingResult} criteria={judgeCriteria} />
              <Collapse
                ghost
                style={{ marginTop: 16 }}
                items={[
                  {
                    key: "details",
                    label: t("admin.resultRunDetails"),
                    children: (
                      <>
                        {identityFields}
                        {metricsFields}
                        {attachmentFields}
                      </>
                    ),
                  },
                ]}
              />
            </>
          ) : (
            <>
              {identityFields}
              {scoreFields}
              {metricsFields}
              {notesField}
              {attachmentFields}
            </>
          )}
        </Form>
      </Modal>

      <ResultPreviewModal
        result={previewResult}
        tasksData={tasksData}
        resultsData={resultsData}
        onClose={() => setPreviewResult(null)}
      />
    </div>
  );
}
