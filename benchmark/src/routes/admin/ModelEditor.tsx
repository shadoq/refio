import { useState } from "react";
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
} from "antd";
import { useForm, Controller } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { PlusOutlined, EditOutlined, DeleteOutlined } from "@ant-design/icons";
import { ModelSchema, type Model } from "@/schema/results";
import { useResults } from "@/data/queries";
import { useUpsertModel, useDeleteModel } from "@/data/mutations";
import { generateId } from "@/lib/ids";
import { formatModelSpec } from "@/lib/format";
import { useT } from "@/i18n/LanguageProvider";

const CAPABILITY_OPTIONS = ["tools", "thinking", "vision"].map((c) => ({ label: c, value: c }));

const { Title } = Typography;

type FormData = Model;

export default function ModelEditor() {
  const t = useT();
  const [editing, setEditing] = useState<Model | null>(null);
  const [open, setOpen] = useState(false);
  const { data: resultsData } = useResults();
  const upsert = useUpsertModel();
  const remove = useDeleteModel();

  const {
    control,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<FormData>({
    resolver: zodResolver(ModelSchema),
  });

  function openNew() {
    setEditing(null);
    reset({ id: generateId(), name: "", provider: "" });
    setOpen(true);
  }

  function openEdit(model: Model) {
    setEditing(model);
    reset(model);
    setOpen(true);
  }

  function handleClose() {
    setOpen(false);
    setEditing(null);
  }

  async function onSubmit(data: FormData) {
    if (!resultsData) return;
    await upsert.mutateAsync({ current: resultsData, model: data });
    handleClose();
  }

  async function handleDelete(modelId: string) {
    if (!resultsData) return;
    await remove.mutateAsync({ current: resultsData, modelId });
  }

  const columns = [
    { title: t("admin.colId"), dataIndex: "id", key: "id", width: 200 },
    { title: t("admin.colName"), dataIndex: "name", key: "name" },
    { title: t("admin.modelColProvider"), dataIndex: "provider", key: "provider", width: 120 },
    { title: t("admin.modelColParams"), dataIndex: "parameterCount", key: "parameterCount", width: 80 },
    {
      title: t("admin.modelColWeights"),
      key: "spec",
      render: (_: unknown, record: Model) => formatModelSpec(record) || "-",
    },
    {
      title: t("admin.colActions"),
      key: "actions",
      width: 120,
      render: (_: unknown, record: Model) => (
        <Space>
          <Button
            icon={<EditOutlined />}
            size="small"
            onClick={() => openEdit(record)}
          />
          <Popconfirm
            title={t("admin.modelDeleteConfirm")}
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

  return (
    <div>
      <Space style={{ marginBottom: 16 }}>
        <Title level={3} style={{ margin: 0 }}>
          {t("admin.modelTitle")}
        </Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={openNew}>
          {t("admin.modelNew")}
        </Button>
      </Space>

      <Table
        columns={columns}
        dataSource={resultsData?.models ?? []}
        rowKey="id"
        size="middle"
        pagination={false}
      />

      <Modal
        title={editing ? t("admin.modelEditTitle") : t("admin.modelNew")}
        open={open}
        onCancel={handleClose}
        onOk={handleSubmit(onSubmit)}
        confirmLoading={upsert.isPending}
        destroyOnClose
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
                <Input {...field} disabled={!!editing} placeholder={t("admin.example", { value: "qwen3.5:9b" })} />
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
              render={({ field }) => <Input {...field} placeholder={t("admin.displayNamePlaceholder")} />}
            />
          </Form.Item>

          <Form.Item
            label={t("admin.modelProvider")}
            validateStatus={errors.provider ? "error" : ""}
            help={errors.provider?.message}
          >
            <Controller
              name="provider"
              control={control}
              render={({ field }) => (
                <Input {...field} placeholder={t("admin.example", { value: "ollama, anthropic, openai" })} />
              )}
            />
          </Form.Item>

          <Form.Item label={t("admin.modelParameterCount")}>
            <Controller
              name="parameterCount"
              control={control}
              render={({ field }) => (
                <Input
                  {...field}
                  value={field.value ?? ""}
                  placeholder={t("admin.example", { value: "9B, 70B" })}
                />
              )}
            />
          </Form.Item>

          <Space wrap size="middle" align="start">
            <Form.Item label={t("admin.modelQuantization")}>
              <Controller
                name="quantization"
                control={control}
                render={({ field }) => (
                  <Input
                    {...field}
                    value={field.value ?? ""}
                    onChange={(e) => field.onChange(e.target.value || undefined)}
                    placeholder={t("admin.example", { value: "Q4_K_M, MXFP4" })}
                    style={{ width: 160 }}
                  />
                )}
              />
            </Form.Item>
            <Form.Item label={t("admin.modelArchitecture")}>
              <Controller
                name="architecture"
                control={control}
                render={({ field }) => (
                  <Select
                    {...field}
                    allowClear
                    options={[
                      { label: t("admin.modelArchDense"), value: "dense" },
                      { label: "MoE", value: "moe" },
                    ]}
                    onChange={(v) => field.onChange(v ?? undefined)}
                    style={{ width: 120 }}
                  />
                )}
              />
            </Form.Item>
            <Form.Item label={t("admin.modelActiveParams")}>
              <Controller
                name="activeParameterCount"
                control={control}
                render={({ field }) => (
                  <Input
                    {...field}
                    value={field.value ?? ""}
                    onChange={(e) => field.onChange(e.target.value || undefined)}
                    placeholder={t("admin.example", { value: "3B" })}
                    style={{ width: 120 }}
                  />
                )}
              />
            </Form.Item>
          </Space>

          <Space wrap size="middle" align="start">
            <Form.Item label={t("admin.modelContextWindow")}>
              <Controller
                name="contextWindow"
                control={control}
                render={({ field }) => (
                  <InputNumber
                    value={field.value ?? null}
                    onChange={(v) => field.onChange(v ?? undefined)}
                    min={1}
                    step={1024}
                    style={{ width: 160 }}
                  />
                )}
              />
            </Form.Item>
            <Form.Item label={t("admin.modelSizeOnDisk")}>
              <Controller
                name="sizeGb"
                control={control}
                render={({ field }) => (
                  <InputNumber
                    value={field.value ?? null}
                    onChange={(v) => field.onChange(v ?? undefined)}
                    min={0.1}
                    step={0.1}
                    style={{ width: 140 }}
                  />
                )}
              />
            </Form.Item>
            <Form.Item label={t("admin.modelDigest")}>
              <Controller
                name="digest"
                control={control}
                render={({ field }) => (
                  <Input
                    {...field}
                    value={field.value ?? ""}
                    onChange={(e) => field.onChange(e.target.value || undefined)}
                    placeholder={t("admin.example", { value: "a50eda8ed977" })}
                    style={{ width: 160 }}
                  />
                )}
              />
            </Form.Item>
          </Space>

          <Space wrap size="middle" align="start">
            {(["releasedAt", "knowledgeCutoff"] as const).map((name) => (
              <Form.Item key={name} label={name === "releasedAt" ? t("admin.modelReleased") : t("admin.modelKnowledgeCutoff")}>
                <Controller
                  name={name}
                  control={control}
                  render={({ field }) => (
                    <Input
                      {...field}
                      value={field.value ?? ""}
                      onChange={(e) => field.onChange(e.target.value || undefined)}
                      placeholder={t("admin.example", { value: "2026-04" })}
                      style={{ width: 140 }}
                    />
                  )}
                />
              </Form.Item>
            ))}
            {(["inputPricePerMTok", "outputPricePerMTok"] as const).map((name) => (
              <Form.Item
                key={name}
                label={name === "inputPricePerMTok" ? t("admin.modelInputPrice") : t("admin.modelOutputPrice")}
              >
                <Controller
                  name={name}
                  control={control}
                  render={({ field }) => (
                    <InputNumber
                      value={field.value ?? null}
                      onChange={(v) => field.onChange(v ?? undefined)}
                      min={0}
                      step={0.05}
                      style={{ width: 140 }}
                    />
                  )}
                />
              </Form.Item>
            ))}
          </Space>

          <Form.Item label={t("admin.modelCapabilities")}>
            <Controller
              name="capabilities"
              control={control}
              render={({ field }) => (
                <Select
                  mode="tags"
                  value={field.value ?? []}
                  onChange={(v: string[]) => field.onChange(v.length ? v : undefined)}
                  options={CAPABILITY_OPTIONS}
                  placeholder="tools, thinking, vision"
                />
              )}
            />
          </Form.Item>

          <Form.Item label={t("admin.modelLicense")}>
            <Controller
              name="license"
              control={control}
              render={({ field }) => (
                <Input
                  {...field}
                  value={field.value ?? ""}
                  onChange={(e) => field.onChange(e.target.value || undefined)}
                  placeholder={t("admin.example", { value: "Apache-2.0, MIT" })}
                />
              )}
            />
          </Form.Item>

          <Form.Item label={t("admin.fieldNotes")}>
            <Controller
              name="notes"
              control={control}
              render={({ field }) => (
                <Input.TextArea
                  {...field}
                  value={field.value ?? ""}
                  rows={2}
                  placeholder={t("admin.notesPlaceholder")}
                />
              )}
            />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
