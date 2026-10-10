import { useState } from "react";
import {
  Table,
  Button,
  Modal,
  Form,
  Input,
  InputNumber,
  Space,
  Switch,
  Tag,
  Popconfirm,
  Typography,
  Divider,
  Tabs,
} from "antd";
import { useForm, Controller, useFieldArray, Control } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { PlusOutlined, EditOutlined, DeleteOutlined } from "@ant-design/icons";
import { TaskSchema, CriterionSchema, type Task, type TasksFile } from "@/schema/tasks";
import { useTasks } from "@/data/queries";
import { useUpsertTask, useDeleteTask, useUpdateCoreCriteria } from "@/data/mutations";
import { generateId } from "@/lib/ids";
import { useT } from "@/i18n/LanguageProvider";

const { Title, Text } = Typography;

// Use z.input so the form type matches what zodResolver infers (with optional defaults)
type TaskFormData = z.input<typeof TaskSchema>;
type CoreCriteriaForm = { criteria: z.input<typeof CriterionSchema>[] };

const emptyCriterion = () => ({
  id: generateId(),
  name: "",
  description: "",
  scale: { values: [0, 1, 2, 3, 4, 5, 6] },
  weight: 1.0,
});

function CriterionFields({
  control,
  prefix,
  fields,
  append,
  remove: removeFn,
}: {
  control: Control<TaskFormData>;
  prefix: "extraCriteria";
  fields: { id: string }[];
  append: (value: NonNullable<TaskFormData["extraCriteria"]>[number]) => void;
  remove: (index: number) => void;
}) {
  const t = useT();
  return (
    <div>
      {fields.map((field, idx) => (
        <div
          key={field.id}
          style={{
            border: "1px solid #d9d9d9",
            borderRadius: 6,
            padding: 12,
            marginBottom: 8,
          }}
        >
          <Space style={{ marginBottom: 8 }}>
            <Text strong>{t("admin.taskCriterionN", { n: idx + 1 })}</Text>
            <Button
              size="small"
              danger
              icon={<DeleteOutlined />}
              onClick={() => removeFn(idx)}
            />
          </Space>
          <Form.Item label={t("admin.fieldId")} style={{ marginBottom: 8 }}>
            <Controller
              name={`${prefix}.${idx}.id` as `extraCriteria.${number}.id`}
              control={control}
              render={({ field: f }) => (
                <Input {...f} placeholder="criterion-id" />
              )}
            />
          </Form.Item>
          <Form.Item label={t("admin.fieldName")} style={{ marginBottom: 8 }}>
            <Controller
              name={`${prefix}.${idx}.name` as `extraCriteria.${number}.name`}
              control={control}
              render={({ field: f }) => (
                <Input {...f} placeholder={t("admin.displayNamePlaceholder")} />
              )}
            />
          </Form.Item>
          <Form.Item label={t("admin.fieldDescription")} style={{ marginBottom: 8 }}>
            <Controller
              name={
                `${prefix}.${idx}.description` as `extraCriteria.${number}.description`
              }
              control={control}
              render={({ field: f }) => (
                <Input.TextArea {...f} rows={2} placeholder={t("admin.taskCriterionDescPlaceholder")} />
              )}
            />
          </Form.Item>
          <Form.Item label={t("admin.taskScaleValues")} style={{ marginBottom: 8 }}>
            <Controller
              name={
                `${prefix}.${idx}.scale.values` as `extraCriteria.${number}.scale.values`
              }
              control={control}
              render={({ field: f }) => (
                <Input
                  value={f.value?.join(", ") ?? ""}
                  onChange={(e) =>
                    f.onChange(
                      e.target.value
                        .split(",")
                        .map((v) => parseFloat(v.trim()))
                        .filter((n) => !isNaN(n)),
                    )
                  }
                  placeholder={t("admin.example", { value: "0, 0.5, 1" })}
                />
              )}
            />
          </Form.Item>
          <Form.Item label={t("admin.fieldWeight")} style={{ marginBottom: 0 }}>
            <Controller
              name={`${prefix}.${idx}.weight` as `extraCriteria.${number}.weight`}
              control={control}
              render={({ field: f }) => (
                <InputNumber {...f} min={0.1} step={0.1} style={{ width: 100 }} />
              )}
            />
          </Form.Item>
        </div>
      ))}
      <Button icon={<PlusOutlined />} onClick={() => append(emptyCriterion())}>
        {t("admin.taskAddCriterion")}
      </Button>
    </div>
  );
}

function CoreCriteriaTab() {
  const t = useT();
  const { data: tasksData } = useTasks();
  const updateCore = useUpdateCoreCriteria();

  const { control, handleSubmit } = useForm<CoreCriteriaForm>({
    defaultValues: { criteria: tasksData?.coreCriteria ?? [] },
  });

  const { fields, append, remove } = useFieldArray({
    control,
    name: "criteria",
  });

  async function onSubmit(data: CoreCriteriaForm) {
    if (!tasksData) return;
    await updateCore.mutateAsync({
      current: tasksData,
      coreCriteria: data.criteria as TasksFile["coreCriteria"],
    });
  }

  return (
    <Form layout="vertical" onFinish={handleSubmit(onSubmit)}>
      {fields.map((field, idx) => (
        <div
          key={field.id}
          style={{
            border: "1px solid #d9d9d9",
            borderRadius: 6,
            padding: 12,
            marginBottom: 8,
          }}
        >
          <Space style={{ marginBottom: 8 }}>
            <Text strong>{t("admin.taskCoreCriterionN", { n: idx + 1 })}</Text>
            <Button
              size="small"
              danger
              icon={<DeleteOutlined />}
              onClick={() => remove(idx)}
            />
          </Space>
          <Form.Item label={t("admin.fieldId")} style={{ marginBottom: 8 }}>
            <Controller
              name={`criteria.${idx}.id` as `criteria.${number}.id`}
              control={control}
              render={({ field: f }) => (
                <Input {...f} placeholder="criterion-id" />
              )}
            />
          </Form.Item>
          <Form.Item label={t("admin.fieldName")} style={{ marginBottom: 8 }}>
            <Controller
              name={`criteria.${idx}.name` as `criteria.${number}.name`}
              control={control}
              render={({ field: f }) => <Input {...f} />}
            />
          </Form.Item>
          <Form.Item label={t("admin.fieldDescription")} style={{ marginBottom: 8 }}>
            <Controller
              name={
                `criteria.${idx}.description` as `criteria.${number}.description`
              }
              control={control}
              render={({ field: f }) => <Input.TextArea {...f} rows={2} />}
            />
          </Form.Item>
          <Form.Item label={t("admin.taskScaleValues")} style={{ marginBottom: 8 }}>
            <Controller
              name={
                `criteria.${idx}.scale.values` as `criteria.${number}.scale.values`
              }
              control={control}
              render={({ field: f }) => (
                <Input
                  value={f.value?.join(", ") ?? ""}
                  onChange={(e) =>
                    f.onChange(
                      e.target.value
                        .split(",")
                        .map((v) => parseFloat(v.trim()))
                        .filter((n) => !isNaN(n)),
                    )
                  }
                  placeholder={t("admin.example", { value: "0, 0.5, 1" })}
                />
              )}
            />
          </Form.Item>
          <Form.Item label={t("admin.fieldWeight")} style={{ marginBottom: 0 }}>
            <Controller
              name={`criteria.${idx}.weight` as `criteria.${number}.weight`}
              control={control}
              render={({ field: f }) => (
                <InputNumber {...f} min={0.1} step={0.1} style={{ width: 100 }} />
              )}
            />
          </Form.Item>
        </div>
      ))}

      <Space style={{ marginTop: 8 }}>
        <Button icon={<PlusOutlined />} onClick={() => append(emptyCriterion())}>
          {t("admin.taskAddCoreCriterion")}
        </Button>
        <Button type="primary" htmlType="submit" loading={updateCore.isPending}>
          {t("admin.taskSaveCoreCriteria")}
        </Button>
      </Space>
    </Form>
  );
}

export default function TaskEditor() {
  const t = useT();
  const [editing, setEditing] = useState<Task | null>(null);
  const [open, setOpen] = useState(false);
  const { data: tasksData } = useTasks();
  const upsert = useUpsertTask();
  const remove = useDeleteTask();

  const now = new Date().toISOString();

  const {
    control,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<TaskFormData>({
    resolver: zodResolver(TaskSchema),
  });

  const { fields, append, remove: removeField } = useFieldArray({
    control,
    name: "extraCriteria",
  });

  function openNew() {
    setEditing(null);
    reset({
      id: "",
      name: "",
      description: "",
      systemPrompt: "",
      extraCriteria: [],
      createdAt: now,
      updatedAt: now,
    });
    setOpen(true);
  }

  function openEdit(task: Task) {
    setEditing(task);
    reset({ ...task, updatedAt: now });
    setOpen(true);
  }

  function handleClose() {
    setOpen(false);
    setEditing(null);
  }

  async function onSubmit(data: TaskFormData) {
    if (!tasksData) return;
    // An emptied field removes the instructions instead of storing a blank string.
    const { judgeInstructions, ...rest } = data as Task;
    const task: Task = judgeInstructions?.trim()
      ? { ...rest, judgeInstructions: judgeInstructions.trim() }
      : rest;
    await upsert.mutateAsync({ current: tasksData, task });
    handleClose();
  }

  async function handleDelete(taskId: string) {
    if (!tasksData) return;
    await remove.mutateAsync({ current: tasksData, taskId });
  }

  async function handleToggleHidden(task: Task, hidden: boolean) {
    if (!tasksData) return;
    await upsert.mutateAsync({
      current: tasksData,
      task: { ...task, hidden, updatedAt: new Date().toISOString() },
    });
  }

  const columns = [
    { title: t("admin.colId"), dataIndex: "id", key: "id", width: 160 },
    {
      title: t("admin.colName"),
      key: "name",
      render: (_: unknown, record: Task) => (
        <Space>
          <span>{record.name}</span>
          {record.hidden && <Tag>{t("admin.taskHiddenTag")}</Tag>}
        </Space>
      ),
    },
    {
      title: t("admin.taskColExtraCriteria"),
      key: "extra",
      width: 120,
      render: (_: unknown, record: Task) => record.extraCriteria.length,
    },
    {
      title: t("admin.taskColVisible"),
      key: "visible",
      width: 90,
      render: (_: unknown, record: Task) => (
        <Switch
          size="small"
          checked={!record.hidden}
          onChange={(checked) => handleToggleHidden(record, !checked)}
        />
      ),
    },
    {
      title: t("admin.colActions"),
      key: "actions",
      width: 120,
      render: (_: unknown, record: Task) => (
        <Space>
          <Button
            icon={<EditOutlined />}
            size="small"
            onClick={() => openEdit(record)}
          />
          <Popconfirm
            title={t("admin.taskDeleteConfirm")}
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

  const tabItems = [
    {
      key: "tasks",
      label: t("admin.taskTabTasks"),
      children: (
        <div>
          <Space style={{ marginBottom: 16 }}>
            <Button type="primary" icon={<PlusOutlined />} onClick={openNew}>
              {t("admin.taskNew")}
            </Button>
          </Space>
          <Table
            columns={columns}
            dataSource={tasksData?.tasks ?? []}
            rowKey="id"
            size="middle"
            pagination={false}
          />
        </div>
      ),
    },
    {
      key: "core",
      label: t("admin.taskTabCore"),
      children: <CoreCriteriaTab />,
    },
  ];

  return (
    <div>
      <Title level={3}>{t("admin.taskPageTitle")}</Title>
      <Tabs items={tabItems} />

      <Modal
        title={editing ? t("admin.taskEditTitle", { name: editing.name }) : t("admin.taskNew")}
        open={open}
        onCancel={handleClose}
        onOk={handleSubmit(onSubmit)}
        confirmLoading={upsert.isPending}
        destroyOnClose
        width={700}
      >
        <Form layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item
            label={t("admin.fieldId")}
            validateStatus={errors.id ? "error" : ""}
            help={errors.id?.message}
          >
            <Controller
              name="id"
              control={control}
              render={({ field }) => (
                <Input {...field} disabled={!!editing} placeholder={t("admin.example", { value: "snake" })} />
              )}
            />
          </Form.Item>

          <Form.Item
            label={t("admin.fieldName")}
            validateStatus={errors.name ? "error" : ""}
            help={errors.name?.message}
          >
            <Controller
              name="name"
              control={control}
              render={({ field }) => (
                <Input {...field} placeholder={t("admin.taskNamePlaceholder")} />
              )}
            />
          </Form.Item>

          <Form.Item label={t("admin.fieldDescription")}>
            <Controller
              name="description"
              control={control}
              render={({ field }) => (
                <Input.TextArea
                  {...field}
                  rows={3}
                  placeholder={t("admin.taskDescPlaceholder")}
                />
              )}
            />
          </Form.Item>

          <Form.Item label={t("admin.taskHidden")}>
            <Controller
              name="hidden"
              control={control}
              render={({ field }) => (
                <Switch checked={!!field.value} onChange={field.onChange} />
              )}
            />
          </Form.Item>

          <Form.Item label={t("admin.taskSystemPrompt")}>
            <Controller
              name="systemPrompt"
              control={control}
              render={({ field }) => (
                <Input.TextArea
                  {...field}
                  rows={5}
                  placeholder={t("admin.taskSystemPromptPlaceholder")}
                  style={{ fontFamily: "monospace" }}
                />
              )}
            />
          </Form.Item>

          <Form.Item
            label={t("admin.taskJudgeInstructions")}
            extra={t("admin.taskJudgeInstructionsExtra")}
          >
            <Controller
              name="judgeInstructions"
              control={control}
              render={({ field }) => (
                <Input.TextArea
                  {...field}
                  value={field.value ?? ""}
                  rows={6}
                  placeholder={t("admin.taskJudgeInstructionsPlaceholder")}
                />
              )}
            />
          </Form.Item>

          <Divider>{t("admin.taskExtraCriteriaDivider")}</Divider>

          <CriterionFields
            control={control}
            prefix="extraCriteria"
            fields={fields}
            append={append}
            remove={removeField}
          />
        </Form>
      </Modal>
    </div>
  );
}
