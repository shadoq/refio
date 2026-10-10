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
import { useT } from "@/i18n/LanguageProvider";

const { Title, Paragraph } = Typography;

type FormData = Harness;

// A harness is what drove the agent. "refio" is our own headless CLI and is the only
// track the main leaderboard shows; an external agent (Claude Code, Codex) brings its
// own planning, tools and self-checking, so what it scores is the whole system, not the
// model alone. `conditions` is what makes that comparison readable, so fill it in.
export default function HarnessEditor() {
  const t = useT();
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
    { title: t("admin.colId"), dataIndex: "id", key: "id", width: 160 },
    { title: t("admin.colName"), dataIndex: "name", key: "name", width: 180 },
    {
      title: t("admin.harnessKind"),
      dataIndex: "kind",
      key: "kind",
      width: 110,
      render: (kind: string) => (
        <Tag color={kind === "refio" ? "geekblue" : "orange"}>{kind}</Tag>
      ),
    },
    { title: t("admin.harnessVersion"), dataIndex: "version", key: "version", width: 120 },
    { title: t("admin.harnessConditions"), dataIndex: "conditions", key: "conditions" },
    {
      title: t("admin.colActions"),
      key: "actions",
      width: 120,
      render: (_: unknown, record: Harness) => (
        <Space>
          <Button icon={<EditOutlined />} size="small" onClick={() => openEdit(record)} />
          <Popconfirm
            title={t("admin.harnessDeleteConfirm")}
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
      <Space style={{ marginBottom: 8 }}>
        <Title level={3} style={{ margin: 0 }}>
          {t("admin.harnessTitle")}
        </Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={openNew}>
          {t("admin.harnessNew")}
        </Button>
      </Space>
      <Paragraph type="secondary">{t("admin.harnessIntro")}</Paragraph>

      <Table
        columns={columns}
        dataSource={resultsData?.harnesses ?? []}
        rowKey="id"
        size="middle"
        pagination={false}
      />

      <Modal
        title={editing ? t("admin.harnessEditTitle") : t("admin.harnessNew")}
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
                <Input {...field} disabled={!!editing} placeholder={t("admin.example", { value: "claude-code" })} />
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
            label={t("admin.harnessKind")}
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
                    { label: t("admin.harnessKindRefio"), value: "refio" },
                    { label: t("admin.harnessKindExternal"), value: "external" },
                  ]}
                />
              )}
            />
          </Form.Item>

          <Form.Item label={t("admin.harnessVersion")}>
            <Controller
              name="version"
              control={control}
              render={({ field }) => (
                <Input {...field} value={field.value ?? ""} placeholder={t("admin.example", { value: "2.1.266" })} />
              )}
            />
          </Form.Item>

          <Form.Item label={t("admin.harnessConditions")}>
            <Controller
              name="conditions"
              control={control}
              render={({ field }) => (
                <Input.TextArea
                  {...field}
                  value={field.value ?? ""}
                  rows={2}
                  placeholder={t("admin.harnessConditionsPlaceholder")}
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
