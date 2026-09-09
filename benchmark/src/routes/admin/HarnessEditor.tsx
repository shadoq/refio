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
import { HarnessSchema, type Harness } from "@/schema/results";
import { useResults } from "@/data/queries";
import { useUpsertHarness, useDeleteHarness } from "@/data/mutations";

const { Title, Paragraph } = Typography;

type FormData = Harness;

// A harness is what drove the agent. "refio" is our own headless CLI and is the only
// track the main leaderboard shows; an external agent (Claude Code, Codex) brings its
// own planning, tools and self-checking, so what it scores is the whole system, not the
// model alone. `conditions` is what makes that comparison readable, so fill it in.
export default function HarnessEditor() {
  const [editing, setEditing] = useState<Harness | null>(null);
  const [open, setOpen] = useState(false);
  const { data: resultsData } = useResults();
  const upsert = useUpsertHarness();
  const remove = useDeleteHarness();

  const {
    control,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<FormData>({
    resolver: zodResolver(HarnessSchema),
  });

  function openNew() {
    setEditing(null);
    reset({ id: "", name: "", kind: "external" });
    setOpen(true);
  }

  function openEdit(harness: Harness) {
    setEditing(harness);
    reset(harness);
    setOpen(true);
  }

  function handleClose() {
    setOpen(false);
    setEditing(null);
  }

  async function onSubmit(data: FormData) {
    if (!resultsData) return;
    await upsert.mutateAsync({ current: resultsData, harness: data });
    handleClose();
  }

  async function handleDelete(harnessId: string) {
    if (!resultsData) return;
    await remove.mutateAsync({ current: resultsData, harnessId });
  }

  const columns = [
    { title: "ID", dataIndex: "id", key: "id", width: 160 },
    { title: "Name", dataIndex: "name", key: "name", width: 180 },
    {
      title: "Kind",
      dataIndex: "kind",
      key: "kind",
      width: 110,
      render: (kind: string) => (
        <Tag color={kind === "refio" ? "geekblue" : "orange"}>{kind}</Tag>
      ),
    },
    { title: "Version", dataIndex: "version", key: "version", width: 120 },
    { title: "Run conditions", dataIndex: "conditions", key: "conditions" },
    {
      title: "Actions",
      key: "actions",
      width: 120,
      render: (_: unknown, record: Harness) => (
        <Space>
          <Button icon={<EditOutlined />} size="small" onClick={() => openEdit(record)} />
          <Popconfirm
            title="Delete this harness?"
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
      <Space style={{ marginBottom: 8 }}>
        <Title level={3} style={{ margin: 0 }}>
          Harnesses
        </Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={openNew}>
          New Harness
        </Button>
      </Space>
      <Paragraph type="secondary">
        What drove the agent. Only the Refio harness appears in the main leaderboard;
        external agents form the reference track. Record the run conditions - network
        access, permission mode, turn limit - so the comparison can be read honestly.
      </Paragraph>

      <Table
        columns={columns}
        dataSource={resultsData?.harnesses ?? []}
        rowKey="id"
        size="middle"
        pagination={false}
      />

      <Modal
        title={editing ? "Edit Harness" : "New Harness"}
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
                <Input {...field} disabled={!!editing} placeholder="e.g. claude-code" />
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
            label="Kind"
            validateStatus={errors.kind ? "error" : ""}
            help={errors.kind?.message}
          >
            <Controller
              name="kind"
              control={control}
              render={({ field }) => (
                <Select
                  {...field}
                  options={[
                    { label: "Refio (main track)", value: "refio" },
                    { label: "External agent (reference track)", value: "external" },
                  ]}
                />
              )}
            />
          </Form.Item>

          <Form.Item label="Version">
            <Controller
              name="version"
              control={control}
              render={({ field }) => (
                <Input {...field} value={field.value ?? ""} placeholder="e.g. 2.1.266" />
              )}
            />
          </Form.Item>

          <Form.Item label="Run conditions">
            <Controller
              name="conditions"
              control={control}
              render={({ field }) => (
                <Input.TextArea
                  {...field}
                  value={field.value ?? ""}
                  rows={2}
                  placeholder="e.g. network on, acceptEdits, max 60 turns"
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
