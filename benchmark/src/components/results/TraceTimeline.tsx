import { useEffect, useState } from "react";
import { Table, Tag, Empty, Spin, Typography } from "antd";
import type { ColumnsType } from "antd/es/table";
import type { TraceSummary } from "@/schema/results";
import type { TraceEvent } from "@/lib/trace/types";

const { Text } = Typography;

const CLASS_COLOR: Record<string, string> = {
  read: "blue",
  write: "orange",
  shell: "purple",
  search: "green",
  other: "default",
};

function formatTime(tMs: number | null): string {
  if (tMs === null) return "-";
  const total = Math.round(tMs / 1000);
  return `${String(Math.floor(total / 60)).padStart(2, "0")}:${String(total % 60).padStart(2, "0")}`;
}

// Every action the agent took, in order. The log is a separate file rather than part
// of the result row: it is large, and only opened when someone looks at one run.
//
// Keyed by the log path so switching runs remounts with empty state instead of
// briefly showing the previous run's steps.
export function TraceTimeline({ trace }: { trace: TraceSummary }) {
  return <TraceTimelineForPath key={trace.path} path={trace.path} />;
}

function TraceTimelineForPath({ path }: { path: string }) {
  const [events, setEvents] = useState<TraceEvent[] | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    fetch(`/data/${path}`)
      .then((r) => (r.ok ? r.text() : Promise.reject(new Error(String(r.status)))))
      .then((text) => {
        if (cancelled) return;
        const parsed: TraceEvent[] = [];
        for (const line of text.split("\n")) {
          if (line.trim() === "") continue;
          try {
            parsed.push(JSON.parse(line) as TraceEvent);
          } catch {
            continue; // a damaged line costs one row, not the whole log
          }
        }
        setEvents(parsed);
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });
    return () => {
      cancelled = true;
    };
  }, [path]);

  if (failed) return <Empty description="Run log not found next to this result" />;
  if (events === null) return <Spin />;
  if (events.length === 0) return <Empty description="The run log is empty" />;

  const columns: ColumnsType<TraceEvent> = [
    { title: "#", dataIndex: "i", width: 60 },
    { title: "t", key: "t", width: 70, render: (_, e) => formatTime(e.tMs) },
    { title: "turn", dataIndex: "turn", width: 60 },
    { title: "kind", dataIndex: "kind", width: 120 },
    {
      title: "tool",
      key: "tool",
      width: 180,
      render: (_, e) =>
        e.tool ? <Tag color={CLASS_COLOR[e.cls ?? "other"]}>{e.tool}</Tag> : null,
    },
    {
      title: "detail",
      key: "detail",
      render: (_, e) => (
        <Text type="secondary" style={{ fontSize: 12 }}>
          {e.args || e.text || ""}
        </Text>
      ),
    },
    {
      title: "ok",
      key: "ok",
      width: 60,
      render: (_, e) =>
        e.ok === null ? null : <Tag color={e.ok ? "green" : "red"}>{e.ok ? "ok" : "err"}</Tag>,
    },
  ];

  return (
    <Table
      dataSource={events}
      columns={columns}
      rowKey={(e) => e.i}
      size="small"
      pagination={{ pageSize: 25, showSizeChanger: false }}
      scroll={{ x: true }}
    />
  );
}
