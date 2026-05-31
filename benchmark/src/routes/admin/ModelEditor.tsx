import { useState } from "react";
import {
  Table,
  Button,
  Modal,
  Form,
  Input,
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

const { Title } = Typography;

type FormData = Model;

export default function ModelEditor() {
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
    { title: "ID", dataIndex: "id", key: "id", width: 200 },
    { title: "Name", dataIndex: "name", key: "name" },
    { title: "Provider", dataIndex: "provider", key: "provider", width: 120 },
    { title: "Params", dataIndex: "parameterCount", key: "parameterCount", width: 80 },
    {
      title: "Actions",
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
            title="Delete this model?"
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
          Models
        </Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={openNew}>
          New Model
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
        title={editing ? "Edit Model" : "New Model"}
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
                <Input {...field} disabled={!!editing} placeholder="e.g. qwen3.5:9b" />
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
            label="Provider"
            validateStatus={errors.provider ? "error" : ""}
            help={errors.provider?.message}
          >
            <Controller
              name="provider"
              control={control}
              render={({ field }) => (
                <Input {...field} placeholder="e.g. ollama, anthropic, openai" />
              )}
            />
          </Form.Item>

          <Form.Item label="Parameter Count">
            <Controller
              name="parameterCount"
              control={control}
              render={({ field }) => (
                <Input
                  {...field}
                  value={field.value ?? ""}
                  placeholder="e.g. 9B, 70B"
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
