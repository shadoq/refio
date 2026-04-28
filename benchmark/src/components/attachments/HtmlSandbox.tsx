import { useEffect, useState } from "react";
import { Spin } from "antd";

interface HtmlSandboxProps {
  src: string;
  height?: number;
  caption?: string;
}

export function HtmlSandbox({ src, height = 600, caption }: HtmlSandboxProps) {
  const [html, setHtml] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const url = src.startsWith("http") ? src : `/data/${src}`;
    fetch(url)
      .then((r) => {
        if (!r.ok) throw new Error(`Failed to load: ${r.status}`);
        return r.text();
      })
      .then(setHtml)
      .catch((e: unknown) => setError(String(e)));
  }, [src]);

  if (error) {
    return <div style={{ color: "red", padding: 16 }}>Error: {error}</div>;
  }

  if (html === null) {
    return (
      <div style={{ display: "flex", justifyContent: "center", padding: 40 }}>
        <Spin />
      </div>
    );
  }

  return (
    <div>
      <iframe
        sandbox="allow-scripts"
        srcDoc={html}
        style={{ width: "100%", height, border: "1px solid #eee", borderRadius: 4 }}
        title={caption ?? "HTML attachment"}
      />
      {caption && (
        <div style={{ marginTop: 8, color: "#666", fontSize: 12 }}>{caption}</div>
      )}
    </div>
  );
}
