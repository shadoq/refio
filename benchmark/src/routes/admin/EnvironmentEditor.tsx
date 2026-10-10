import { useState } from "react";
import {
  Table,
  Button,
  Modal,
  Form,
  Input,
  Select,
  Space,
  Popconfirm,
  Typography,
  Tag,
} from "antd";
import { useForm, Controller } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { PlusOutlined, EditOutlined, DeleteOutlined } from "@ant-design/icons";
import { EnvironmentSchema, type Environment } from "@/schema/results";
import { useResults } from "@/data/queries";
import { useUpsertEnvironment, useDeleteEnvironment } from "@/data/mutations";
import { generateId } from "@/lib/ids";
import { useT } from "@/i18n/LanguageProvider";

const { Title } = Typography;

type FormData = Environment;

export default function EnvironmentEditor() {
  const t = useT();
  const [editing, setEditing] = useState<Environment | null>(null);
  const [open, setOpen] = useState(false);
  const { data: resultsData } = useResults();
  const upsert = useUpsertEnvironment();
  const remove = useDeleteEnvironment();

  const {
    control,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<FormData>({
    resolver: zodResolver(EnvironmentSchema),
  });

  function openNew() {
    setEditing(null);
    reset({ id: generateId(), name: "", type: "local" });
    setOpen(true);
  }

  function openEdit(env: Environment) {
    setEditing(env);
    reset(env);
    setOpen(true);
  }

  function handleClose() {
    setOpen(false);
    setEditing(null);
  }

  async function onSubmit(data: FormData) {
    if (!resultsData) return;
    await upsert.mutateAsync({ current: resultsData, environment: data });
    handleClose();
  }

  async function handleDelete(environmentId: string) {
    if (!resultsData) return;
    await remove.mutateAsync({ current: resultsData, environmentId });
  }

  const columns = [
    { title: t("admin.colId"), dataIndex: "id", key: "id", width: 180 },
    { title: t("admin.colName"), dataIndex: "name", key: "name" },
    {
      title: t("admin.envType"),
      dataIndex: "type",
      key: "type",
      width: 90,
      render: (type: string) => (
        <Tag color={type === "cloud" ? "blue" : "green"}>{type === "cloud" ? t("admin.envTypeCloud") : type === "local" ? t("admin.envTypeLocal") : type}</Tag>
      ),
    },
    { title: t("admin.envHardware"), dataIndex: "hardware", key: "hardware" },
    {
      title: t("admin.colActions"),
      key: "actions",
      width: 120,
      render: (_: unknown, record: Environment) => (
        <Space>
          <Button
            icon={<EditOutlined />}
            size="small"
            onClick={() => openEdit(record)}
          />
          <Popconfirm
            title={t("admin.envDeleteConfirm")}
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
          {t("admin.envTitle")}
        </Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={openNew}>
          {t("admin.envNew")}
        </Button>
      </Space>

      <Table
        columns={columns}
        dataSource={resultsData?.environments ?? []}
        rowKey="id"
        size="middle"
        pagination={false}
      />

      <Modal
        title={editing ? t("admin.envEditTitle") : t("admin.envNew")}
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
                <Input
                  {...field}
                  disabled={!!editing}
                  placeholder={t("admin.example", { value: "dgx-local" })}
                />
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
            label={t("admin.envType")}
            validateStatus={errors.type ? "error" : ""}
            help={errors.type?.message}
          >
            <Controller
              name="type"
              control={control}
              render={({ field }) => (
                <Select
                  {...field}
                  options={[
                    { label: t("admin.envTypeLocal"), value: "local" },
                    { label: t("admin.envTypeCloud"), value: "cloud" },
                  ]}
                />
              )}
            />
          </Form.Item>

          <Form.Item label={t("admin.envHardware")}>
            <Controller
              name="hardware"
              control={control}
              render={({ field }) => (
                <Input
                  {...field}
                  value={field.value ?? ""}
                  placeholder={t("admin.example", { value: "DGX Spark, RTX 4090" })}
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
