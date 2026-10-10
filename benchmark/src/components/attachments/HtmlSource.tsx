import { useEffect, useState } from "react";
import { Spin, Typography } from "antd";
import { useT } from "@/i18n/LanguageProvider";
import { dataUrl } from "@/lib/adminArtifacts";

const { Text } = Typography;

// Carries a failed response's status out of the fetch chain.
export class HttpStatusError extends Error {
  constructor(readonly status: number) {
    super(`HTTP ${status}`);
  }
}

interface HtmlSourceProps {
  src: string;
  height?: number;
}

// Read-only view of an artifact's raw HTML, for reviewing what the model actually wrote
// without executing it.
export function HtmlSource({ src, height = 360 }: HtmlSourceProps) {
  const t = useT();
  const [code, setCode] = useState<string | null>(null);
  const [error, setError] = useState<{ status: number } | { detail: string } | null>(null);

  useEffect(() => {
    fetch(dataUrl(src))
      .then((r) => {
        if (!r.ok) throw new HttpStatusError(r.status);
        return r.text();
      })
      .then(setCode)
      .catch((e: unknown) =>
        setError(e instanceof HttpStatusError ? { status: e.status } : { detail: String(e) }),
      );
  }, [src]);

  if (error) {
    const detail =
      "status" in error
        ? t("resultView.loadFailedStatus", { status: error.status })
        : error.detail;
    return (
      <div style={{ color: "red", padding: 16 }}>{t("resultView.loadError", { detail })}</div>
    );
  }

  if (code === null) {
    return (
      <div style={{ display: "flex", justifyContent: "center", padding: 40 }}>
        <Spin />
      </div>
    );
  }

  const lines = code.split(/\r?\n/);
  const gutter = String(lines.length).length;

  return (
    <div>
      <pre
        style={{
          maxHeight: height,
          overflow: "auto",
          margin: 0,
          padding: 8,
          fontSize: 12,
          lineHeight: 1.45,
          fontFamily: "ui-monospace, SFMono-Regular, Consolas, monospace",
          background: "rgba(0,0,0,0.04)",
          border: "1px solid rgba(0,0,0,0.15)",
          borderRadius: 4,
        }}
      >
        {lines.map((line, i) => (
          <div key={i} style={{ whiteSpace: "pre" }}>
            <span
              style={{
                display: "inline-block",
                width: `${gutter + 1}ch`,
                opacity: 0.45,
                userSelect: "none",
              }}
            >
              {i + 1}
            </span>
            {line}
          </div>
        ))}
      </pre>
      <Text type="secondary" copyable={{ text: code }} style={{ fontSize: 12 }}>
        {t("resultView.sourceStats", {
          lines: lines.length,
          chars: code.length.toLocaleString(),
          charCount: code.length,
        })}
      </Text>
    </div>
  );
}
