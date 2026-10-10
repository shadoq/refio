import { useEffect, useState } from "react";
import { Spin } from "antd";
import { useT } from "@/i18n/LanguageProvider";
import { HttpStatusError } from "./HtmlSource";

interface HtmlSandboxProps {
  src: string;
  height?: number;
  caption?: string;
  fill?: boolean;
}

export function HtmlSandbox({ src, height = 600, caption, fill = false }: HtmlSandboxProps) {
  const t = useT();
  const [html, setHtml] = useState<string | null>(null);
  const [error, setError] = useState<{ status: number } | { detail: string } | null>(null);

  useEffect(() => {
    const url = src.startsWith("http") ? src : `/data/${src}`;
    fetch(url)
      .then((r) => {
        if (!r.ok) throw new HttpStatusError(r.status);
        return r.text();
      })
      .then(setHtml)
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

  if (html === null) {
    return (
      <div style={{ display: "flex", justifyContent: "center", padding: 40 }}>
        <Spin />
      </div>
    );
  }

  if (fill) {
    return (
      <iframe
        sandbox="allow-scripts"
        srcDoc={html}
        style={{ width: "100%", height: "100%", border: "none", display: "block" }}
        title={caption ?? t("resultView.htmlAttachmentTitle")}
      />
    );
  }

  return (
    <div>
      <iframe
        sandbox="allow-scripts"
        srcDoc={html}
        style={{ width: "100%", height, border: "1px solid #eee", borderRadius: 4 }}
        title={caption ?? t("resultView.htmlAttachmentTitle")}
      />
      {caption && (
        <div style={{ marginTop: 8, color: "#666", fontSize: 12 }}>{caption}</div>
      )}
    </div>
  );
}
