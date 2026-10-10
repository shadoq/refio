import { Image } from "antd";
import { useT } from "@/i18n/LanguageProvider";

interface ImageViewerProps {
  src: string;
  caption?: string;
}

export function ImageViewer({ src, caption }: ImageViewerProps) {
  const t = useT();
  const url = src.startsWith("http") ? src : `/data/${src}`;
  return (
    <div style={{ textAlign: "center" }}>
      <Image
        src={url}
        alt={caption ?? t("resultView.attachmentAlt")}
        style={{ maxWidth: "100%", maxHeight: 500 }}
      />
      {caption && (
        <div style={{ marginTop: 8, color: "#666", fontSize: 12 }}>{caption}</div>
      )}
    </div>
  );
}
