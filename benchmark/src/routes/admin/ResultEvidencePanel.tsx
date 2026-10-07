import { useState } from "react";
import { Button, Empty, Space, Tabs, Typography } from "antd";
import { PlayCircleOutlined } from "@ant-design/icons";
import { AttachmentViewer } from "@/components/attachments/AttachmentViewer";
import { HtmlSandbox } from "@/components/attachments/HtmlSandbox";
import { JudgeBreakdown } from "@/components/results/JudgeBreakdown";
import { TraceSummaryTags } from "@/components/results/TraceSummaryTags";
import type { Result } from "@/schema/results";
import type { Criterion } from "@/schema/tasks";

const { Text } = Typography;

interface ResultEvidencePanelProps {
  result: Result;
  // Name lookup for the judge breakdown: core + task extra + judge-only criteria.
  criteria: Criterion[];
}

// What a reviewer looks at while scoring one result: the live artifact on the left,
// the judges' verdicts, the run summary and the screenshots on the right. Shows the
// stored result, so it does not change while the scores above are being edited.
// The artifact is model-written code that may loop forever, so it only runs once the
// reviewer asks for it; opening the editor must never be able to hang the browser.
export function ResultEvidencePanel({ result, criteria }: ResultEvidencePanelProps) {
  const htmls = result.attachments.filter((att) => att.type === "html");
  const others = result.attachments.filter((att) => att.type !== "html");
  const [htmlIdx, setHtmlIdx] = useState(0);
  // Which artifact the reviewer chose to run; switching tabs stops the previous one.
  const [runningSrc, setRunningSrc] = useState<string | null>(null);
  const activeHtml = htmls[Math.min(htmlIdx, htmls.length - 1)];

  return (
    <div style={{ display: "flex", gap: 16, height: "70vh", minHeight: 420 }}>
      <div style={{ flex: "1 1 62%", minWidth: 0, display: "flex", flexDirection: "column" }}>
        {htmls.length > 1 && (
          <Tabs
            size="small"
            activeKey={String(htmlIdx)}
            onChange={(key) => setHtmlIdx(Number(key))}
            items={htmls.map((att, i) => ({ key: String(i), label: att.caption ?? `HTML ${i + 1}` }))}
          />
        )}
        <div style={{ flex: 1, minHeight: 0, background: "#fff", borderRadius: 8, overflow: "hidden" }}>
          {activeHtml && runningSrc !== activeHtml.src ? (
            <Empty description="The artifact is not running." style={{ paddingTop: 80 }}>
              <Button
                type="primary"
                icon={<PlayCircleOutlined />}
                onClick={() => setRunningSrc(activeHtml.src)}
              >
                Run artifact
              </Button>
            </Empty>
          ) : activeHtml ? (
            <HtmlSandbox key={activeHtml.src} src={activeHtml.src} caption={activeHtml.caption} fill />
          ) : (
            <Empty description="No HTML artifact for this result." style={{ paddingTop: 80 }} />
          )}
        </div>
      </div>

      <div style={{ flex: "1 1 38%", minWidth: 280, overflow: "auto", paddingRight: 4 }}>
        <Space direction="vertical" size="middle" style={{ width: "100%" }}>
          <JudgeBreakdown detailResult={result} criteria={criteria} />
          {result.trace && (
            <div>
              <Text strong style={{ display: "block", marginBottom: 6 }}>
                Run trace
              </Text>
              <TraceSummaryTags trace={result.trace} />
            </div>
          )}
          {others.map((att, index) => (
            <div key={`${att.src}-${index}`}>
              <Text strong style={{ display: "block", marginBottom: 6 }}>
                {att.caption ?? att.src.split("/").pop()}
              </Text>
              <AttachmentViewer attachment={att} />
            </div>
          ))}
        </Space>
      </div>
    </div>
  );
}
