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

const { Title } = Typography;

type FormData = Environment;

export default function EnvironmentEditor() {
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
    { title: "ID", dataIndex: "id", key: "id", width: 180 },
    { title: "Name", dataIndex: "name", key: "name" },
    {
      title: "Type",
      dataIndex: "type",
      key: "type",
      width: 90,
      render: (type: string) => (
        <Tag color={type === "cloud" ? "blue" : "green"}>{type}</Tag>
      ),
    },
    { title: "Hardware", dataIndex: "hardware", key: "hardware" },
    {
      title: "Actions",
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
            title="Delete this environment?"
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
      <Space style={{ marginBottom: 16 }}>
        <Title level={3} style={{ margin: 0 }}>
          Environments
        </Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={openNew}>
          New Environment
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
        title={editing ? "Edit Environment" : "New Environment"}
        open={open}
        onCancel={handleClose}
        onOk={handleSubmit(onSubmit)}
        confirmLoading={upsert.isPending}
        destroyOnClose
      >
        <Form layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item
            label="ID"
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
                  placeholder="e.g. dgx-local"
                />
              )}
            />
          </Form.Item>

          <Form.Item
            label="Name"
            validateStatus={errors.name ? "error" : ""}
            help={errors.name?.message}
          >
            <Controller
              name="name"
              control={control}
              render={({ field }) => <Input {...field} placeholder="Display name" />}
            />
          </Form.Item>

          <Form.Item
            label="Type"
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
                    { label: "Local", value: "local" },
                    { label: "Cloud", value: "cloud" },
                  ]}
                />
              )}
            />
          </Form.Item>

          <Form.Item label="Hardware">
            <Controller
              name="hardware"
              control={control}
              render={({ field }) => (
                <Input
                  {...field}
                  value={field.value ?? ""}
                  placeholder="e.g. DGX Spark, RTX 4090"
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
                  rows={2}
                  placeholder="Optional notes"
                />
              )}
            />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
}
