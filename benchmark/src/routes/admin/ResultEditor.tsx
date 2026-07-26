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
  Switch,
  Tooltip,
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
import { ResultCard } from "@/components/results/ResultCard";
import { generateId } from "@/lib/ids";
import { formatDuration, formatCost } from "@/lib/format";
import type { Criterion } from "@/schema/tasks";
import { type input as ZodInput } from "zod";

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
  const scaleOptions = criterion.scale.values.map((v) => ({
    label: criterion.scale.labels?.[String(v)] ?? String(v),
    value: v,
  }));

  return (
    <Form.Item
      key={criterion.id}
      label={`${criterion.name} (${criterion.scale.values.join(", ")})`}
      style={{ marginBottom: 8 }}
    >
      <Controller
        name={`scores.${index}.value` as const}
        control={control}
        render={({ field }) => (
          <Select
            {...field}
            options={scaleOptions}
            style={{ width: 200 }}
            placeholder="Select score"
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
  const [modalMode, setModalMode] = useState<"new" | "edit" | "duplicate">("new");
  const [open, setOpen] = useState(false);
  const [previewResult, setPreviewResult] = useState<Result | null>(null);
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
    reset({
      id: generateId(),
      taskId: "",
      modelId: "",
      environmentId: "",
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
    setModalMode("new");
  }

  async function onSubmit(data: FormData) {
    if (!resultsData) return;
    // Cast because zodResolver gives us z.input, mutation expects z.output (same structure at runtime)
    const result = data as unknown as Result;
    // Keep the persisted JSON clean: only carry the flag when it is actually set.
    if (!result.excludeFromStats) delete result.excludeFromStats;
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

  // Quick inline toggle of the "exclude from stats" flag, without opening the editor.
  async function toggleExclude(record: Result, excluded: boolean) {
    if (!resultsData) return;
    const result: Result = { ...record };
    if (excluded) result.excludeFromStats = true;
    else delete result.excludeFromStats;
    await upsert.mutateAsync({ current: resultsData, result });
  }

  async function handleFileUpload(file: File) {
    const resultId = getValues("id");
    if (!resultId) {
      void message.error("Save the result ID first");
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
      void message.success(`Uploaded: ${path}`);
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

  const environmentTypeOptions = [
    { label: "Local", value: "local" },
    { label: "Cloud", value: "cloud" },
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
      title: "Task",
      dataIndex: "taskId",
      key: "task",
      width: 120,
      render: (id: string) =>
        tasksData?.tasks.find((t) => t.id === id)?.name ?? id,
    },
    {
      title: "Model",
      dataIndex: "modelId",
      key: "model",
      render: (id: string) =>
        resultsData?.models.find((m) => m.id === id)?.name ?? id,
    },
    {
      title: "Env",
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
      title: "Duration",
      dataIndex: "durationMs",
      key: "duration",
      width: 90,
      render: (ms: number | undefined) => formatDuration(ms),
    },
    {
      title: "Cost",
      dataIndex: "costUsd",
      key: "cost",
      width: 80,
      render: (usd: number | undefined) => formatCost(usd),
    },
    {
      title: "Attachments",
      key: "att",
      width: 90,
      render: (_: unknown, record: Result) => record.attachments.length || "—",
    },
    {
      title: (
        <Tooltip title="Exclude this run from all computed metrics (kept as evidence the model was tested)">
          <span>Excluded</span>
        </Tooltip>
      ),
      key: "excluded",
      width: 90,
      render: (_: unknown, record: Result) => (
        <Switch
          size="small"
          checked={record.excludeFromStats === true}
          onChange={(checked) => toggleExclude(record, checked)}
        />
      ),
    },
    {
      title: "Actions",
      key: "actions",
      width: 190,
      render: (_: unknown, record: Result) => (
        <Space>
          <Button
            aria-label="Preview result"
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
            title="Delete this result?"
            onConfirm={() => handleDelete(record.id)}
            okText="Delete"
            okButtonProps={{ danger: true }}
          >
            <Button icon={<DeleteOutlined />} size="small" danger />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Space style={{ marginBottom: 16 }} wrap>
        <Title level={3} style={{ margin: 0 }}>
          Results
        </Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={openNew}>
          New Result
        </Button>
        <Segmented
          options={[
            { label: "Table", value: "table" },
            { label: "Cards", value: "cards" },
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
            placeholder="Models"
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
            placeholder="Tasks"
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
            placeholder="Environments"
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
            placeholder="Env type"
            options={environmentTypeOptions}
            value={environmentTypeFilter}
            onChange={setEnvironmentTypeFilter}
            style={{ minWidth: 140 }}
            maxTagCount="responsive"
          />
          <Input.Search
            allowClear
            placeholder="Search ID, model, notes"
            value={searchText}
            onChange={(event) => setSearchText(event.target.value)}
            style={{ width: 240 }}
          />
          <Text type="secondary">
            {filteredResults.length} / {resultsData?.results.length ?? 0}
          </Text>
          {hasResultFilters && (
            <Button icon={<ClearOutlined />} onClick={clearResultFilters}>
              Clear
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
        <Empty description="No results match the current filters" />
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
            ? "Edit Result"
            : modalMode === "duplicate"
              ? "Duplicate as Next Attempt"
              : "New Result"
        }
        open={open}
        onCancel={handleClose}
        onOk={handleSubmit(onSubmit)}
        confirmLoading={upsert.isPending}
        destroyOnClose
        width={700}
      >
        <Form layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item
            label="Task"
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
                  placeholder="Select task"
                  showSearch
                />
              )}
            />
          </Form.Item>

          <Form.Item
            label="Model"
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
                  placeholder="Select model"
                  showSearch
                />
              )}
            />
          </Form.Item>

          <Form.Item
            label="Environment"
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
                  placeholder="Select environment"
                />
              )}
            />
          </Form.Item>

          <Form.Item
            label="Attempt #"
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

          {activeCriteria.length > 0 && (
            <>
              <Divider>Scores</Divider>
              {activeCriteria.map((criterion, idx) => (
                <ScoreRow
                  key={criterion.id}
                  criterion={criterion}
                  control={control}
                  index={idx}
                />
              ))}
            </>
          )}

          <Divider>Metrics (optional)</Divider>

          <Form.Item label="Duration (seconds)">
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
                  placeholder="e.g. 45"
                />
              )}
            />
          </Form.Item>

          <Form.Item label="Tokens In">
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

          <Form.Item label="Tokens Out">
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

          <Form.Item label="Cost (USD)">
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
                  placeholder="e.g. 0.024"
                />
              )}
            />
          </Form.Item>

          <Form.Item label="Run At">
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

          <Form.Item label="Notes">
            <Controller
              name="notes"
              control={control}
              render={({ field }) => (
                <Input.TextArea
                  {...field}
                  value={field.value ?? ""}
                  rows={3}
                  placeholder="Observations about this run"
                />
              )}
            />
          </Form.Item>

          <Form.Item label="Exclude from stats">
            <Controller
              name="excludeFromStats"
              control={control}
              render={({ field }) => (
                <Space>
                  <Switch checked={!!field.value} onChange={field.onChange} />
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    Run kept as evidence the model was tested, but dropped from every computed metric.
                  </Text>
                </Space>
              )}
            />
          </Form.Item>

          <Divider>Attachments</Divider>

          <Form.Item label="Upload result file (image / html / video / zip)">
            <Upload
              beforeUpload={handleFileUpload}
              showUploadList={false}
              accept="image/*,.html,.htm,.mp4,.webm,.mov,.zip,.7z,.tar,.gz"
            >
              <Button icon={<UploadOutlined />} loading={uploading}>
                Upload Attachment
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
                      Remove
                    </Button>
                  </div>
                ))}
              </div>
            )}
          />
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
